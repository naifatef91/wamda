package app.barq.engine

import app.barq.crypto.Tls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Broadcast mode (FR-12): the SENDER owns the Wi-Fi Direct group and listens.
 * Each receiver that joins dials in with one control connection and, after
 * NEED, its data connections, all tagged with a session id. Every session gets
 * its own [Sender] over a [HostedTransport], so receivers transfer in parallel
 * and independently (a slow or dropped one never stalls the others).
 */
class BroadcastHost(
    private val port: Int,
    private val token: String,
    private val deviceName: String,
    private val items: List<SendItem>,
    private val onJoined: (session: String, peer: String) -> Unit,
    private val onPlanned: (session: String, peer: String, stats: TransferStats) -> Unit,
    private val onDone: (session: String, result: SenderResult) -> Unit,
    private val onFailed: (session: String, cause: Throwable) -> Unit,
) {
    private class Peer(val name: String, val data: Channel<Socket> = Channel(Channel.UNLIMITED))

    private val peers = ConcurrentHashMap<String, Peer>()

    /** Runs until cancelled. */
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
                    socket.sendBufferSize = Protocol.SOCKET_BUFFER
                    socket.soTimeout = 120_000
                    launch { admit(socket) }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("unreachable")
            }
        } finally {
            runCatching { server.close() }
            peers.values.forEach { it.data.close() }
        }
    }

    private suspend fun admit(socket: Socket) {
        val hello = try {
            (socket as? javax.net.ssl.SSLSocket)?.startHandshake()
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            // Peek-free: HELLO is the first frame on every connection; the control stream is
            // re-wrapped by Sender afterwards, so nothing else may be buffered here.
            wireJson.decodeFromString(Hello.serializer(), FrameIO.readJson(inp, Protocol.Type.HELLO))
        } catch (e: Exception) {
            android.util.Log.w("Wamda", "broadcast: rejected connection from ${socket.inetAddress}", e)
            runCatching { socket.close() }
            return
        }
        if (hello.magic != Protocol.MAGIC || hello.token != token || hello.session.isBlank()) {
            runCatching { FrameIO.writeJson(DataOutputStream(socket.getOutputStream()), Protocol.Type.ERROR, "bad token") }
            runCatching { socket.close() }
            return
        }
        when (hello.role) {
            Protocol.Role.CONTROL -> {
                val peer = Peer(hello.deviceName)
                peers[hello.session] = peer
                onJoined(hello.session, hello.deviceName)
                try {
                    val sender = Sender(HostedTransport(socket, peer.data), token, deviceName, items) { stats, _ ->
                        onPlanned(hello.session, hello.deviceName, stats)
                    }
                    val result = sender.run()
                    onDone(hello.session, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Wamda", "broadcast: session with ${hello.deviceName} failed", e)
                    onFailed(hello.session, e)
                } finally {
                    peers.remove(hello.session)?.data?.close()
                    runCatching { socket.close() }
                }
            }
            Protocol.Role.DATA -> {
                val peer = peers[hello.session]
                if (peer == null) { runCatching { socket.close() }; return }
                peer.data.send(socket)
            }
            else -> runCatching { socket.close() }
        }
    }
}
