package app.barq.engine

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Base64

/**
 * Cheap content fingerprint for duplicate detection (FR-10). It is computed for every picked file
 * before the manifest can be sent, so its cost is the delay before the first byte moves.
 *
 * - Files up to [WHOLE_FILE_MAX] (128 KiB, most photos' thumbnails, documents, chat media): the whole
 *   file is hashed.
 * - Larger files: three [SAMPLE]-byte windows (start, middle, end) = 192 KiB read, however big the file.
 *
 * The file size is always mixed in, and the result carries a version prefix so a fingerprint made
 * by another algorithm never matches by accident (it only costs a re-send). Hashing is SHA-256,
 * which Android runs in hardware on ARMv8.
 */
object QuickHash {
    const val WHOLE_FILE_MAX = 128 * 1024
    const val SAMPLE = 64 * 1024
    private const val VERSION = "q2:"

    fun of(channel: FileChannel, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(size).array())
        if (size <= WHOLE_FILE_MAX) {
            hashRange(channel, md, 0, size.toInt())
        } else {
            hashRange(channel, md, 0, SAMPLE)
            hashRange(channel, md, (size - SAMPLE) / 2, SAMPLE)
            hashRange(channel, md, size - SAMPLE, SAMPLE)
        }
        return VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
    }

    private fun hashRange(ch: FileChannel, md: MessageDigest, offset: Long, length: Int) {
        if (length <= 0) return
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        md.update(buf.array(), 0, buf.position())
    }
}
