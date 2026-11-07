package app.barq.engine

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Cheap content fingerprint for duplicate detection: SHA-256 over the file size,
 * its first [WINDOW] bytes and its last [WINDOW] bytes. Reads at most 512 KiB per
 * file, so a 2,000-photo folder is fingerprinted in a couple of seconds, while
 * two different files of equal size and name practically never collide.
 */
object QuickHash {
    private const val WINDOW = 256 * 1024

    fun of(channel: FileChannel, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(size).array())
        val head = ByteBuffer.allocate(minOf(WINDOW.toLong(), size).toInt())
        readFully(channel, head, 0)
        md.update(head.array(), 0, head.position())
        if (size > WINDOW) {
            val tailStart = maxOf(size - WINDOW, WINDOW.toLong())
            val tail = ByteBuffer.allocate((size - tailStart).toInt())
            readFully(channel, tail, tailStart)
            md.update(tail.array(), 0, tail.position())
        }
        return Base64.encodeToString(md.digest(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, offset: Long) {
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) break
            pos += n
        }
    }
}
