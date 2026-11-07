package app.barq.net.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import app.barq.engine.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * USB-C phone-to-phone transport (spec 2.0). One phone is the USB host (OTG),
 * the other is switched into Android Open Accessory mode; both ends then share
 * a single duplex bulk pipe. [Mux] turns that pipe into many independent
 * streams, each exposed as a [Link], so the ordinary frame protocol runs over
 * the cable unchanged, and USB streams can join a Wi-Fi transfer as extra
 * workers (multipath).
 */

/** The raw duplex pipe: bulk endpoints on the host, the accessory file descriptor on the device. */
interface UsbPipe : Closeable {
    fun read(buf: ByteArray, off: Int, len: Int): Int
    fun write(buf: ByteArray, off: Int, len: Int)
    val description: String
}

class AccessoryPipe(private val pfd: ParcelFileDescriptor, override val description: String) : UsbPipe {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    override fun read(buf: ByteArray, off: Int, len: Int): Int = input.read(buf, off, len)
    override fun write(buf: ByteArray, off: Int, len: Int) { output.write(buf, off, len) }
    override fun close() { runCatching { input.close() }; runCatching { output.close() }; runCatching { pfd.close() } }
}

class HostPipe(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    override val description: String,
) : UsbPipe {
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val tmp = if (off == 0) buf else ByteArray(minOf(len, MAX_BULK))
        while (true) {
            val n = conn.bulkTransfer(epIn, tmp, minOf(len, MAX_BULK, tmp.size), 1000)
            if (n > 0) { if (tmp !== buf) System.arraycopy(tmp, 0, buf, off, n); return n }
            if (n < 0 && closed) return -1
        }
    }
    override fun write(buf: ByteArray, off: Int, len: Int) {
        var pos = off
        var left = len
        while (left > 0) {
            val chunk = minOf(left, MAX_BULK)
            val slice = if (pos == 0 && chunk == buf.size) buf else buf.copyOfRange(pos, pos + chunk)
            val n = conn.bulkTransfer(epOut, slice, chunk, 5000)
            if (n < 0) throw IOException("USB write failed")
            pos += n; left -= n
        }
    }
    @Volatile private var closed = false
    override fun close() { closed = true; runCatching { conn.close() } }

    companion object { const val MAX_BULK = 16 * 1024 }
}

/** Android Open Accessory handshake (host side). */
object Aoa {
    const val VID_GOOGLE = 0x18D1
    private val ACCESSORY_PIDS = setOf(0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05)
    const val MANUFACTURER = "Wamda"
    const val MODEL = "Wamda Link"
    const val DESCRIPTION = "Wamda cable transfer"
    const val VERSION = "1"
    const val URI = "https://github.com/naifatef91/wamda"
    const val SERIAL = "0001"

    fun isAccessory(d: UsbDevice) = d.vendorId == VID_GOOGLE && d.productId in ACCESSORY_PIDS

    /** Asks a plain Android device to re-enumerate in accessory mode; returns false if it does not speak AOA. */
    fun switchToAccessory(conn: UsbDeviceConnection): Boolean {
        val proto = ByteArray(2)
        val r = conn.controlTransfer(0xC0, 51, 0, 0, proto, 2, 1000)
        if (r < 2 || (proto[0].toInt() or (proto[1].toInt() shl 8)) < 1) return false
        fun str(index: Int, s: String) {
            val b = (s + "\u0000").toByteArray(Charsets.UTF_8)
            conn.controlTransfer(0x40, 52, 0, index, b, b.size, 1000)
        }
        str(0, MANUFACTURER); str(1, MODEL); str(2, DESCRIPTION); str(3, VERSION); str(4, URI); str(5, SERIAL)
        conn.controlTransfer(0x40, 53, 0, 0, null, 0, 1000)
        return true
    }

    /** Bulk IN/OUT endpoints of an accessory-mode device. */
    fun endpoints(d: UsbDevice): Pair<UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = inEp ?: ep else outEp = outEp ?: ep
            }
            if (inEp != null && outEp != null) return inEp to outEp
        }
        return null
    }
}

/**
 * Many streams over one pipe. Frame: id (1 byte) + length (3 bytes, big-endian) +
 * payload; length 0 closes that stream. Stream 0 is the session channel; the
 * sender opens 1..N for the frame protocol. The remote's new ids surface on [incoming].
 */
class Mux(private val pipe: UsbPipe, private val kind: String = "usb") : Closeable {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val streams = ConcurrentHashMap<Int, StreamIn>()
    private val writeLock = Any()
    private var reader: Job? = null
    @Volatile private var closed = false

    /** Links opened by the other side, in the order their first bytes arrived. */
    val incoming = Channel<Link>(Channel.UNLIMITED)

    fun start() {
        reader = scope.launch {
            val header = ByteArray(4)
            try {
                while (!closed) {
                    readFully(header, 4)
                    val id = header[0].toInt() and 0xFF
                    val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                    val s = streams[id] ?: StreamIn().also { streams[id] = it; incoming.trySend(link(id, it)) }
                    if (len == 0) { s.eof(); continue }
                    if (len > MAX_FRAME) throw java.io.IOException("usb frame too large: $len")
                    val payload = ByteArray(len)
                    readFully(payload, len)
                    s.push(payload)
                }
            } catch (e: Exception) {
                if (!closed) android.util.Log.w("Wamda", "usb mux reader ended: ${e.message}")
            } finally {
                streams.values.forEach { it.eof() }
                incoming.close()
            }
        }
    }

    /** Opens a stream from this side. */
    fun open(id: Int): Link {
        val s = StreamIn().also { streams[id] = it }
        return link(id, s)
    }

    private fun link(id: Int, s: StreamIn): Link = object : Link {
        override val input: InputStream = s
        override val output: OutputStream = StreamOut(id)
        override val kind: String = this@Mux.kind
        override val remote: String = pipe.description
        override fun close() {
            runCatching { output.close() }
            s.eof()
            streams.remove(id)
        }
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var pos = 0
        while (pos < len) {
            val n = pipe.read(buf, pos, len - pos)
            if (n < 0) throw IOException("USB pipe closed")
            pos += n
        }
    }

    private fun writeFrame(id: Int, buf: ByteArray, off: Int, len: Int) {
        val frame = ByteArray(4 + len)
        frame[0] = id.toByte(); frame[1] = (len shr 16).toByte(); frame[2] = (len shr 8).toByte(); frame[3] = len.toByte()
        System.arraycopy(buf, off, frame, 4, len)
        synchronized(writeLock) { pipe.write(frame, 0, frame.size) }
    }

    override fun close() {
        closed = true
        reader?.cancel()
        runCatching { pipe.close() }
        streams.values.forEach { it.eof() }
        incoming.close()
    }

    private inner class StreamOut(private val id: Int) : OutputStream() {
        private var closedOut = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off; var left = len
            while (left > 0) {
                val n = minOf(left, MAX_FRAME)
                writeFrame(id, b, pos, n)
                pos += n; left -= n
            }
        }
        override fun close() {
            if (closedOut) return
            closedOut = true
            runCatching { writeFrame(id, ByteArray(0), 0, 0) }
        }
    }

    /** Blocking byte source fed by the reader coroutine. */
    private class StreamIn : InputStream() {
        /** Bounded: a peer that floods one stream blocks the shared pipe instead of growing the heap. */
        private val queue = LinkedBlockingQueue<ByteArray>(256)
        private var current: ByteArray? = null
        private var pos = 0
        @Volatile private var done = false
        private val EOF = ByteArray(0)

        fun push(b: ByteArray) { if (!done) queue.put(b) }
        fun eof() { if (!done) { done = true; if (!queue.offer(EOF)) { queue.clear(); queue.offer(EOF) } } }

        private fun fill(): Boolean {
            val c = current
            if (c != null && pos < c.size) return true
            while (true) {
                val next = queue.poll(60, TimeUnit.SECONDS) ?: if (done) return false else continue
                if (next === EOF) { queue.put(EOF); return false }
                current = next; pos = 0
                if (next.isNotEmpty()) return true
            }
        }
        override fun read(): Int = if (!fill()) -1 else (current!![pos++].toInt() and 0xFF)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val c = current!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }
        override fun available(): Int = (current?.let { it.size - pos } ?: 0)
    }

    companion object { const val MAX_FRAME = 60 * 1024 }
}
