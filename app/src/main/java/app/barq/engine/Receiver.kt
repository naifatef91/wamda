package app.barq.engine

import app.barq.crypto.Tls
import app.barq.net.IdleGuard
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
    /** Asked once per session; [peerId] is the sender's certificate fingerprint on authenticated links, else null. */
    private val onIncoming: suspend (manifest: TransferManifest, peerId: String?) -> Boolean,
    private val onPlanned: (TransferStats, peerName: String) -> Unit,
    private val onSessionDone: (ReceiverResult) -> Unit,
    private val onDropped: (Throwable) -> Unit = {},
    /** Parallel data streams the sender is asked to open (4–8). */
    private val streams: Int = Protocol.DEFAULT_STREAMS,
    /** false only in benchmark mode; the QR payload tells the sender. */
    private val encrypted: Boolean = true,
    /** Local address to listen on (the P2P group owner IP); null binds every interface. */
    private val bindAddress: String? = null,
) {
    @Volatile private var stats: TransferStats? = null
    private val sessionActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activeData = AtomicInteger(0)
    private val concurrent = AtomicInteger(0)
    private val badTokens = AtomicInteger(0)

    /** Runs until the calling coroutine is cancelled. */
    suspend fun run(): Nothing = withContext(Dispatchers.IO) {
        val server: ServerSocket = if (encrypted) Tls.serverSocket() else ServerSocket()
        server.receiveBufferSize = Protocol.SOCKET_BUFFER
        server.reuseAddress = true
        server.bind(if (bindAddress != null) InetSocketAddress(java.net.InetAddress.getByName(bindAddress), port) else InetSocketAddress(port))
        try {
            coroutineScope {
                while (true) {
                    val socket = runInterruptible { server.accept() }
                    if (concurrent.get() >= MAX_CONNECTIONS) { runCatching { socket.close() }; continue }
                    socket.receiveBufferSize = Protocol.SOCKET_BUFFER
                    socket.soTimeout = 120_000
                    // The TLS handshake and HELLO must finish within the deadline; see [IdleGuard].
                    val firstFrame = IdleGuard.arm(socket)
                    launch {
                        // Handshake explicitly so a TLS failure is logged with its real cause.
                        try {
                            (socket as? javax.net.ssl.SSLSocket)?.startHandshake()
                        } catch (e: Exception) {
                            android.util.Log.e("Wamda", "TLS handshake with ${socket.inetAddress} failed", e)
                            runCatching { socket.close() }
                            return@launch
                        }
                        admit(SocketLink(socket), firstFrame)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("unreachable")
            }
        } finally {
            runCatching { server.close() }
        }
    }

    /**
     * Serves one incoming link of any kind: a TLS socket accepted by [run], or a USB
     * stream handed over by the cable session (multipath data streams included).
     */
    suspend fun admit(link: Link, firstFrame: IdleGuard.Watch? = null) {
        var isControl = false
        concurrent.incrementAndGet()
        try {
            link.use {
                val inp = DataInputStream(BufferedInputStream(link.input, Protocol.STREAM_BUFFER))
                val hello = try {
                    wireJson.decodeFromString(Hello.serializer(), FrameIO.readJson(inp, Protocol.Type.HELLO))
                } finally { firstFrame?.disarm() }
                if (hello.magic != Protocol.MAGIC || !java.security.MessageDigest.isEqual(hello.token.toByteArray(), token.toByteArray())) {
                    // Online guessing of the pairing code: slow down, then give up on this listener
                    // entirely (the controller shows a fresh code).
                    val n = badTokens.incrementAndGet()
                    if (n > MAX_BAD_TOKENS) throw TokenAbuse()
                    if (n > 3) delay(2_000)
                    val out = DataOutputStream(link.output)
                    FrameIO.writeJson(out, Protocol.Type.ERROR, "bad token")
                    return
                }
                when (hello.role) {
                    Protocol.Role.CONTROL -> { isControl = true; control(link, inp, hello) }
                    Protocol.Role.DATA -> data(inp, link.kind)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenAbuse) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Wamda", "receiver ${link.kind} link from ${link.remote} failed (control=$isControl)", e)
            if (isControl && sessionActive.get()) dropSession(e)
            // A failing data stream is noticed by the control handler when the sender tears down.
        } finally {
            concurrent.decrementAndGet()
        }
    }

    /** Too many wrong pairing codes: the listener stops and the user gets a new code. */
    class TokenAbuse : ProtocolException("too many wrong pairing codes")

    /**
     * Broadcast mode: this phone joined the SENDER's network and dials it. One control
     * connection plus [streams] data connections, all tagged with one session id so the
     * host can route them; the protocol on each is identical to the hosted case.
     */
    suspend fun runClient(network: android.net.Network?, host: String, port: Int, fingerprint: String) = withContext(Dispatchers.IO) {
        val session = java.util.UUID.randomUUID().toString()
        fun dial(): Socket {
            val s = network?.socketFactory?.createSocket() ?: Socket()
            s.sendBufferSize = Protocol.SOCKET_BUFFER
            s.receiveBufferSize = Protocol.SOCKET_BUFFER
            s.connect(InetSocketAddress(host, port), 15_000)
            s.soTimeout = 120_000
            return if (encrypted) Tls.wrapClient(s, host, port, fingerprint) else s
        }
        coroutineScope {
            SocketLink(dial()).use { control ->
                val out = DataOutputStream(BufferedOutputStream(control.output, 64 * 1024))
                FrameIO.writeJson(out, Protocol.Type.HELLO, wireJson.encodeToString(Hello.serializer(), Hello(token = token, role = Protocol.Role.CONTROL, deviceName = deviceName, session = session)))
                val inp = DataInputStream(BufferedInputStream(control.input, Protocol.STREAM_BUFFER))
                control(control, inp, Hello(token = token, role = Protocol.Role.CONTROL, deviceName = "", session = session)) { n ->
                    repeat(n) {
                        launch {
                            SocketLink(dial()).use { ds ->
                                val dout = DataOutputStream(BufferedOutputStream(ds.output, 4096))
                                FrameIO.writeJson(dout, Protocol.Type.HELLO, wireJson.encodeToString(Hello.serializer(), Hello(token = token, role = Protocol.Role.DATA, deviceName = deviceName, session = session)))
                                data(DataInputStream(BufferedInputStream(ds.input, Protocol.STREAM_BUFFER)), "wifi")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun control(link: Link, inp: DataInputStream, hello: Hello, afterNeed: suspend (streams: Int) -> Unit = {}) {
        if (sessionActive.get()) throw ProtocolException("another session is active")
        val out = DataOutputStream(BufferedOutputStream(link.output, 64 * 1024))
        val manifest = wireJson.decodeFromString(TransferManifest.serializer(), FrameIO.readJson(inp, Protocol.Type.MANIFEST))
        validate(manifest)
        val peerName = hello.deviceName.ifBlank { manifest.senderName }.take(64)

        val accepted = onIncoming(manifest, link.peerId)
        if (!accepted) {
            FrameIO.writeJson(out, Protocol.Type.NEED, wireJson.encodeToString(NeedResponse.serializer(), NeedResponse(false, reason = "declined")))
            throw ProtocolException("declined")
        }

        if (!sessionActive.compareAndSet(false, true)) throw ProtocolException("another session is active")
        val missing = store.prepare(manifest)
        val skipped = manifest.files.map { it.id }.filter { it !in missing }
        val prefilled = missing.keys.associateWith { store.bytesAlready(it) }
        val n = streams.coerceIn(1, Protocol.MAX_STREAMS)
        val s = TransferStats(manifest.files, skipped.toSet(), prefilled)
        s.streams = n
        stats = s
        onPlanned(s, peerName)

        FrameIO.writeJson(
            out, Protocol.Type.NEED,
            wireJson.encodeToString(NeedResponse.serializer(), NeedResponse(true, n, deviceName, missing)),
        )
        afterNeed(n)

        // Sender sends DONE after closing its data sockets.
        val done = wireJson.decodeFromString(DoneMessage.serializer(), FrameIO.readJson(inp, Protocol.Type.DONE))

        // Drain: data handlers may still be flushing buffered frames.
        withTimeoutOrNull(30_000) { while (activeData.get() > 0) delay(20) }

        store.finish()
        val incomplete = store.incompleteIds()
        FrameIO.writeJson(out, Protocol.Type.ACK, wireJson.encodeToString(AckMessage.serializer(), AckMessage(incomplete.isEmpty(), incomplete)))
        stats = null
        sessionActive.set(false)
        onSessionDone(ReceiverResult(done.bytesSent, incomplete, skipped))
    }

    /** Sender vanished mid-session: persist what arrived, then go back to listening. */
    private suspend fun dropSession(cause: Throwable) {
        withTimeoutOrNull(3_000) { while (activeData.get() > 0) delay(20) }
        runCatching { store.finish() }
        stats = null
        sessionActive.set(false)
        onDropped(cause)
    }

    /** Rejects manifests that would exhaust the receiver before the user even sees the dialog. */
    private fun validate(m: TransferManifest) {
        if (m.files.isEmpty() || m.files.size > MAX_FILES) throw ProtocolException("bad file count ${m.files.size}")
        if (m.senderName.length > 64) throw ProtocolException("sender name too long")
        val ids = HashSet<Int>(m.files.size)
        for (f in m.files) {
            if (f.size < 0 || f.size > MAX_FILE_SIZE) throw ProtocolException("bad file size ${f.size}")
            if (f.name.isEmpty() || f.name.length > 255) throw ProtocolException("bad file name length")
            if (f.path.length > 1024) throw ProtocolException("bad path length")
            if (!ids.add(f.id)) throw ProtocolException("duplicate file id ${f.id}")
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 32
        const val MAX_BAD_TOKENS = 20
        const val MAX_FILES = 20_000
        const val MAX_FILE_SIZE = 8L shl 40
    }

    private fun data(inp: DataInputStream, kind: String = "wifi") {
        activeData.incrementAndGet()
        try {
            val buf = ByteArray(Protocol.CHUNK_SIZE.toInt())
            while (true) {
                val t0 = System.nanoTime()
                val h = FrameIO.readHeader(inp) ?: break
                when (h.type) {
                    Protocol.Type.DATA -> {
                        if (h.length < 0 || h.length > Protocol.CHUNK_SIZE) throw ProtocolException("bad chunk length ${h.length}")
                        inp.readFully(buf, 0, h.length)
                        val t1 = System.nanoTime()
                        store.write(h.fileId, h.offset, buf, h.length)
                        val t2 = System.nanoTime()
                        stats?.add(h.fileId, h.length)
                        stats?.addTime(if (kind == "wifi") "net" else kind, t1 - t0)
                        stats?.addTime("write", t2 - t1)
                        stats?.addBytes(kind, h.length)
                    }
                    else -> throw ProtocolException("unexpected frame ${h.type} on data stream")
                }
            }
        } finally {
            activeData.decrementAndGet()
        }
    }
}
