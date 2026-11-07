package app.barq.engine

import app.barq.crypto.Tls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ReceiverResult(val bytesReceived: Long, val incompleteFiles: List<Int>, val skippedFiles: List<Int>)

/**
 * Listens for sessions until cancelled. Each completed session is reported
 * through [onSessionDone] and the receiver keeps listening, so the same sender
 * can send more without pairing again. A session whose sender drops mid-way is
 * closed cleanly (partial files persisted) and reported through [onDropped].
 */
class Receiver(
    private val port: Int,
    private val token: String,
    private val deviceName: String,
    private val store: ReceiveStore,
    private val onIncoming: suspend (TransferManifest) -> Boolean,
    private val onPlanned: (TransferStats, peerName: String) -> Unit,
    private val onSessionDone: (ReceiverResult) -> Unit,
    private val onDropped: (Throwable) -> Unit = {},
) {
    @Volatile private var stats: TransferStats? = null
    @Volatile private var sessionActive = false
    private val activeData = AtomicInteger(0)

    /** Runs until the calling coroutine is cancelled. */
    suspend fun run(): Nothing = withContext(Dispatchers.IO) {
        val server: ServerSocket = Tls.serverSocket()
        server.receiveBufferSize = Protocol.SOCKET_BUFFER
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        try {
            coroutineScope {
                while (true) {
                    val socket = runInterruptible { server.accept() }
                    socket.receiveBufferSize = Protocol.SOCKET_BUFFER
                    socket.soTimeout = 120_000
                    launch {
                        // Handshake explicitly so a TLS failure is logged with its real cause.
                        try {
                            (socket as? javax.net.ssl.SSLSocket)?.startHandshake()
                        } catch (e: Exception) {
                            android.util.Log.e("Wamda", "TLS handshake with ${socket.inetAddress} failed", e)
                            runCatching { socket.close() }
                            return@launch
                        }
                        handle(socket)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("unreachable")
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private suspend fun handle(socket: Socket) {
        var isControl = false
        try {
            socket.use {
                val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), Protocol.STREAM_BUFFER))
                val hello = wireJson.decodeFromString(Hello.serializer(), FrameIO.readJson(inp, Protocol.Type.HELLO))
                if (hello.magic != Protocol.MAGIC || hello.token != token) {
                    val out = DataOutputStream(socket.getOutputStream())
                    FrameIO.writeJson(out, Protocol.Type.ERROR, "bad token")
                    return
                }
                when (hello.role) {
                    Protocol.Role.CONTROL -> { isControl = true; control(socket, inp, hello) }
                    Protocol.Role.DATA -> data(inp)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Wamda", "receiver connection from ${socket.inetAddress} failed (control=$isControl)", e)
            if (isControl && sessionActive) dropSession(e)
            // A failing data stream is noticed by the control handler when the sender tears down.
        }
    }

    private suspend fun control(socket: Socket, inp: DataInputStream, hello: Hello) {
        if (sessionActive) throw ProtocolException("another session is active")
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        val manifest = wireJson.decodeFromString(TransferManifest.serializer(), FrameIO.readJson(inp, Protocol.Type.MANIFEST))

        val accepted = onIncoming(manifest)
        if (!accepted) {
            FrameIO.writeJson(out, Protocol.Type.NEED, wireJson.encodeToString(NeedResponse.serializer(), NeedResponse(false, reason = "declined")))
            throw ProtocolException("declined")
        }

        sessionActive = true
        val missing = store.prepare(manifest)
        val skipped = manifest.files.map { it.id }.filter { it !in missing }
        val prefilled = missing.keys.associateWith { store.bytesAlready(it) }
        val s = TransferStats(manifest.files, skipped.toSet(), prefilled)
        s.streams = Protocol.DEFAULT_STREAMS
        stats = s
        onPlanned(s, hello.deviceName)

        FrameIO.writeJson(
            out, Protocol.Type.NEED,
            wireJson.encodeToString(NeedResponse.serializer(), NeedResponse(true, Protocol.DEFAULT_STREAMS, deviceName, missing)),
        )

        // Sender sends DONE after closing its data sockets.
        val done = wireJson.decodeFromString(DoneMessage.serializer(), FrameIO.readJson(inp, Protocol.Type.DONE))

        // Drain: data handlers may still be flushing buffered frames.
        withTimeoutOrNull(30_000) { while (activeData.get() > 0) delay(20) }

        store.finish()
        val incomplete = store.incompleteIds()
        FrameIO.writeJson(out, Protocol.Type.ACK, wireJson.encodeToString(AckMessage.serializer(), AckMessage(incomplete.isEmpty(), incomplete)))
        stats = null
        sessionActive = false
        onSessionDone(ReceiverResult(done.bytesSent, incomplete, skipped))
    }

    /** Sender vanished mid-session: persist what arrived, then go back to listening. */
    private suspend fun dropSession(cause: Throwable) {
        withTimeoutOrNull(3_000) { while (activeData.get() > 0) delay(20) }
        runCatching { store.finish() }
        stats = null
        sessionActive = false
        onDropped(cause)
    }

    private fun data(inp: DataInputStream) {
        activeData.incrementAndGet()
        try {
            val buf = ByteArray(Protocol.CHUNK_SIZE.toInt())
            while (true) {
                val h = FrameIO.readHeader(inp) ?: break
                when (h.type) {
                    Protocol.Type.DATA -> {
                        if (h.length < 0 || h.length > Protocol.CHUNK_SIZE) throw ProtocolException("bad chunk length ${h.length}")
                        inp.readFully(buf, 0, h.length)
                        store.write(h.fileId, h.offset, buf, h.length)
                        stats?.add(h.fileId, h.length)
                    }
                    else -> throw ProtocolException("unexpected frame ${h.type} on data stream")
                }
            }
        } finally {
            activeData.decrementAndGet()
        }
    }
}
