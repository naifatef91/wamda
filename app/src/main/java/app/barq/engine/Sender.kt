package app.barq.engine

import android.net.Network
import app.barq.crypto.Tls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/** A readable file the sender can open on demand; opening may happen on several threads. */
class SendItem(val entry: FileEntry, val open: () -> ReadableFile)

class ReadableFile(val channel: FileChannel, private val onClose: () -> Unit) : Closeable {
    override fun close() {
        runCatching { channel.close() }
        onClose()
    }
}

private data class ChunkJob(val fileId: Int, val index: Int)

class SenderResult(val bytesSent: Long, val skippedFiles: List<Int>, val incompleteFiles: List<Int>)

/**
 * Where the sender's sockets come from. Normally the sender dials the receiver
 * ([DialTransport]); in broadcast mode the receiver dialed us and the sockets
 * are handed over already connected ([HostedTransport]).
 */
interface SenderTransport {
    /** The control connection; [writesHello] says whether this side must introduce itself on it. */
    suspend fun control(): Socket
    /** One more data connection, called once per stream after NEED. */
    suspend fun data(): Socket
    val writesHello: Boolean
}

/** Sender dials the receiver's TLS server (phone-to-phone default). */
class DialTransport(
    private val network: Network?,
    private val host: String,
    private val port: Int,
    private val fingerprint: String,
    private val encrypted: Boolean = true,
    /** Resolved address to dial (Wi-Fi Aware gives a scoped IPv6 one); [host] is then only the TLS peer name. */
    private val address: java.net.InetAddress? = null,
) : SenderTransport {
    override val writesHello = true
    override suspend fun control(): Socket = connect()
    override suspend fun data(): Socket = connect()

    private fun connect(): Socket {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = false
        socket.sendBufferSize = Protocol.SOCKET_BUFFER
        socket.receiveBufferSize = Protocol.SOCKET_BUFFER
        socket.connect(if (address != null) InetSocketAddress(address, port) else InetSocketAddress(host, port), 15_000)
        socket.soTimeout = 60_000
        if (!encrypted) return socket
        if (fingerprint.isBlank()) throw ProtocolException("QR code has no certificate fingerprint; refusing unencrypted transfer")
        return Tls.wrapClient(socket, host, port, fingerprint)
    }
}

/** Receiver dialed us (broadcast mode): control socket is ready, data sockets arrive on a channel. */
class HostedTransport(private val controlSocket: Socket, private val dataSockets: Channel<Socket>) : SenderTransport {
    override val writesHello = false
    override suspend fun control(): Socket = controlSocket
    override suspend fun data(): Socket = dataSockets.receive()
}

class Sender(
    private val transport: SenderTransport,
    private val token: String,
    private val deviceName: String,
    private val items: List<SendItem>,
    private val onPlanned: (TransferStats, peerName: String) -> Unit,
) {
    /** Phone-to-phone convenience constructor: dial the receiver. */
    constructor(
        network: Network?, host: String, port: Int, token: String, fingerprint: String, deviceName: String,
        items: List<SendItem>, onPlanned: (TransferStats, peerName: String) -> Unit, encrypted: Boolean = true,
    ) : this(DialTransport(network, host, port, fingerprint, encrypted), token, deviceName, items, onPlanned)

    private val byId = items.associateBy { it.entry.id }

    suspend fun run(): SenderResult = withContext(Dispatchers.IO) {
        val manifest = TransferManifest(
            sessionId = java.util.UUID.randomUUID().toString(),
            senderName = deviceName,
            files = items.map { it.entry },
        )

        transport.control().use { control ->
            val out = DataOutputStream(BufferedOutputStream(control.getOutputStream(), 64 * 1024))
            val inp = DataInputStream(BufferedInputStream(control.getInputStream(), 64 * 1024))

            if (transport.writesHello) {
                FrameIO.writeJson(out, Protocol.Type.HELLO, wireJson.encodeToString(Hello.serializer(), Hello(token = token, role = Protocol.Role.CONTROL, deviceName = deviceName)))
            }
            FrameIO.writeJson(out, Protocol.Type.MANIFEST, wireJson.encodeToString(TransferManifest.serializer(), manifest))

            val need = wireJson.decodeFromString(NeedResponse.serializer(), FrameIO.readJson(inp, Protocol.Type.NEED))
            if (!need.accepted) throw ProtocolException(need.reason ?: "declined by receiver")

            val skipped = manifest.files.map { it.id }.filter { it !in need.missing }
            val prefilled = need.missing.mapValues { (id, chunks) ->
                val size = byId.getValue(id).entry.size
                size - chunks.sumOf { idx -> chunkLength(size, idx).toLong() }
            }
            val stats = TransferStats(manifest.files, skipped.toSet(), prefilled)
            val streams = need.streams.coerceIn(1, Protocol.MAX_STREAMS)
            stats.streams = streams
            onPlanned(stats, need.receiverName)

            val queue = Channel<ChunkJob>(Channel.UNLIMITED)
            for (entry in manifest.files) {
                val chunks = need.missing[entry.id] ?: continue
                for (idx in chunks.sorted()) queue.send(ChunkJob(entry.id, idx))
            }
            queue.close()

            val sentBytes = java.util.concurrent.atomic.AtomicLong()
            coroutineScope {
                repeat(streams) {
                    launch { dataWorker(queue, stats, sentBytes) }
                }
            }

            FrameIO.writeJson(out, Protocol.Type.DONE, wireJson.encodeToString(DoneMessage.serializer(), DoneMessage(sentBytes.get())))
            val ack = wireJson.decodeFromString(AckMessage.serializer(), FrameIO.readJson(inp, Protocol.Type.ACK))
            SenderResult(sentBytes.get(), skipped, ack.incomplete)
        }
    }

    private suspend fun dataWorker(queue: Channel<ChunkJob>, stats: TransferStats, sentBytes: java.util.concurrent.atomic.AtomicLong) {
        val open = HashMap<Int, ReadableFile>()
        val buf = ByteArray(Protocol.CHUNK_SIZE.toInt())
        try {
            transport.data().use { socket ->
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), Protocol.STREAM_BUFFER))
                if (transport.writesHello) {
                    FrameIO.writeJson(out, Protocol.Type.HELLO, wireJson.encodeToString(Hello.serializer(), Hello(token = token, role = Protocol.Role.DATA, deviceName = deviceName)))
                }

                for (job in queue) {
                    val item = byId.getValue(job.fileId)
                    val size = item.entry.size
                    val offset = job.index * Protocol.CHUNK_SIZE
                    val len = chunkLength(size, job.index)
                    val file = open.getOrPut(job.fileId) { item.open() }
                    val t0 = System.nanoTime()
                    readFully(file.channel, buf, offset, len)
                    val t1 = System.nanoTime()

                    val last = job.index == Protocol.chunkCount(size) - 1
                    FrameIO.writeHeader(out, Protocol.Type.DATA, if (last) Protocol.Flag.LAST_CHUNK else 0, job.fileId, offset, len)
                    out.write(buf, 0, len)
                    val t2 = System.nanoTime()
                    stats.add(job.fileId, len)
                    stats.addTime("read", t1 - t0)
                    stats.addTime("net", t2 - t1)
                    sentBytes.addAndGet(len.toLong())
                }
                // SSLSocket has no half-close; close() flushes and sends close_notify.
                out.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            open.values.forEach { runCatching { it.close() } }
        }
    }

    private fun chunkLength(size: Long, index: Int): Int {
        val offset = index * Protocol.CHUNK_SIZE
        return min(Protocol.CHUNK_SIZE, size - offset).coerceAtLeast(0).toInt()
    }

    private fun readFully(ch: FileChannel, buf: ByteArray, offset: Long, len: Int) {
        val bb = ByteBuffer.wrap(buf, 0, len)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = ch.read(bb, pos)
            if (n < 0) throw IOException("file shrank while sending")
            pos += n
        }
    }
}
