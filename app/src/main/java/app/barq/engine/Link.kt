package app.barq.engine

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * One duplex byte stream the frame protocol runs over. Wi-Fi gives a TCP/TLS
 * socket; USB gives a multiplexed stream over the accessory pipe. The engine
 * never looks past this interface, which is what lets several links of
 * different kinds feed the same transfer (multipath).
 */
interface Link : Closeable {
    val input: InputStream
    val output: OutputStream
    /** Short label for logs and the profile ("wifi", "usb"). */
    val kind: String
    /** Remote identity for logs; address or device name. */
    val remote: String
}

class SocketLink(val socket: Socket, override val kind: String = "wifi") : Link {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override val remote: String get() = socket.inetAddress?.hostAddress ?: "?"
    override fun close() = socket.close()
}

class StreamLink(
    override val input: InputStream,
    override val output: OutputStream,
    override val kind: String,
    override val remote: String,
    private val onClose: () -> Unit = {},
) : Link {
    override fun close() {
        runCatching { output.flush() }
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}
