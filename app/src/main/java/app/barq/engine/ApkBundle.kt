package app.barq.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Apps installed from the Play Store are usually a base APK plus split APKs (language, density, ABI).
 * To move one as a single file the sender packs them into an uncompressed ZIP ("STORE", APKs are already
 * compressed) with the extension .apks; the receiving phone installs the whole set in one
 * PackageInstaller session.
 */
object ApkBundle {
    const val MAX_ENTRIES = 64
    private const val COPY_BUFFER = 256 * 1024

    fun isInstallable(name: String): Boolean = name.endsWith(".apk", ignoreCase = true) || name.endsWith(".apks", ignoreCase = true)

    /** Writes [parts] (base first) into [out] without compressing them. */
    fun pack(parts: List<File>, out: File) {
        require(parts.isNotEmpty() && parts.size <= MAX_ENTRIES) { "an app has 1 to $MAX_ENTRIES APKs, got ${parts.size}" }
        val used = HashSet<String>()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out), COPY_BUFFER)).use { zip ->
                for (part in parts) {
                    val e = ZipEntry(uniqueName(part.name, used))
                    // STORED entries must declare size and CRC up front, so each file is read twice.
                    e.method = ZipEntry.STORED
                    e.size = part.length(); e.compressedSize = part.length(); e.crc = crc(part)
                    zip.putNextEntry(e)
                    FileInputStream(part).use { it.copyTo(zip, COPY_BUFFER) }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /**
     * Calls [action] for every .apk inside an .apks stream, with the stream positioned on that entry.
     * Names are reduced to a bare file name, anything that is not an .apk is ignored, and a bundle with
     * more than [MAX_ENTRIES] entries is refused. Returns how many APKs were handed over.
     */
    fun forEachApk(input: InputStream, action: (name: String, data: InputStream) -> Unit): Int {
        var count = 0
        var seen = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++seen > MAX_ENTRIES) throw IOException("too many entries in the bundle")
                if (entry.isDirectory) continue
                val name = NameSanitizer.fileName(entry.name)
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                action(name, NoClose(zip))
                count++
            }
        }
        return count
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        var n = name.ifBlank { "base.apk" }
        var i = 1
        while (!used.add(n)) n = name.removeSuffix(".apk") + "-${i++}.apk"
        return n
    }

    private fun crc(f: File): Long {
        val c = CRC32()
        val buf = ByteArray(COPY_BUFFER)
        FileInputStream(f).use { while (true) { val n = it.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
        return c.value
    }

    /** Lets the consumer read one entry to its end without closing the whole archive. */
    private class NoClose(private val s: ZipInputStream) : InputStream() {
        override fun read(): Int = s.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
        override fun available(): Int = s.available()
        override fun close() {}
    }
}
