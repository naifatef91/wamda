package app.barq.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkBundleTest {
    private val dir: File = Files.createTempDirectory("wamda-apks").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private fun apk(name: String, size: Int, seed: Long): File = File(dir, name).also { f -> f.writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) }) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun packedBundleIsUncompressedAndByteExact() {
        val parts = listOf(apk("base.apk", 3_000_000, 1), apk("split_config.arm64_v8a.apk", 1_200_000, 2), apk("split_config.ar.apk", 40_000, 3))
        val out = File(dir, "app.apks")
        ApkBundle.pack(parts, out)

        ZipFile(out).use { z ->
            assertEquals(3, z.size())
            for (p in parts) {
                val e = z.getEntry(p.name)!!
                assertEquals("${p.name} must be STORED, not deflated", ZipEntry.STORED, e.method)
                assertEquals(p.length(), e.size)
                assertEquals(p.length(), e.compressedSize)
                assertArrayEquals(p.readBytes(), z.getInputStream(e).readBytes())
            }
        }
        // Uncompressed means the bundle is only a little bigger than its parts (headers), never smaller.
        val total = parts.sumOf { it.length() }
        assertTrue(out.length() in total..(total + 2_000))
    }

    @Test fun readerHandsOverEveryApkInOrderWithItsContent() {
        val parts = listOf(apk("base.apk", 100_000, 1), apk("split_a.apk", 50_000, 2))
        val out = File(dir, "app.apks"); ApkBundle.pack(parts, out)
        val seen = LinkedHashMap<String, ByteArray>()
        val n = out.inputStream().use { ApkBundle.forEachApk(it) { name, data -> seen[name] = data.readBytes() } }
        assertEquals(2, n)
        assertEquals(listOf("base.apk", "split_a.apk"), seen.keys.toList())
        assertArrayEquals(parts[0].readBytes(), seen["base.apk"]); assertArrayEquals(parts[1].readBytes(), seen["split_a.apk"])
    }

    @Test fun readerIgnoresNonApkEntriesAndStripsPaths() {
        val bundle = zipOf(
            "../../evil.apk" to byteArrayOf(1), "toc.pb" to byteArrayOf(2), "dir/nested/split_x.apk" to byteArrayOf(3), "readme.txt" to byteArrayOf(4),
        )
        val names = ArrayList<String>()
        val n = ApkBundle.forEachApk(bundle.inputStream()) { name, data -> names += name; data.readBytes() }
        assertEquals(2, n)
        assertEquals(listOf("evil.apk", "split_x.apk"), names)
        assertTrue("no path separators may survive", names.none { it.contains('/') || it.contains('\\') || it.contains("..") })
    }

    @Test fun readerRefusesABundleWithTooManyEntries() {
        val bundle = zipOf(*(1..(ApkBundle.MAX_ENTRIES + 1)).map { "s$it.apk" to byteArrayOf(1) }.toTypedArray())
        try { ApkBundle.forEachApk(bundle.inputStream()) { _, d -> d.readBytes() }; fail("should have refused") } catch (e: IOException) { /* expected */ }
    }

    @Test fun emptyOrNonZipInputYieldsNothingToInstall() {
        assertEquals(0, ApkBundle.forEachApk(ByteArray(0).inputStream()) { _, _ -> fail() })
        assertEquals(0, ApkBundle.forEachApk(zipOf("only.txt" to byteArrayOf(1)).inputStream()) { _, _ -> fail() })
    }

    @Test fun duplicateNamesGetUniqueEntries() {
        val a = apk("base.apk", 10, 1); val subdir = File(dir, "other").apply { mkdirs() }
        val b = File(subdir, "base.apk").also { it.writeBytes(ByteArray(10) { 7 }) }
        val out = File(dir, "dup.apks"); ApkBundle.pack(listOf(a, b), out)
        ZipFile(out).use { z -> assertEquals(setOf("base.apk", "base-1.apk"), z.entries().asSequence().map { it.name }.toSet()) }
    }

    @Test fun failedPackLeavesNoHalfWrittenFile() {
        val out = File(dir, "bad.apks")
        try { ApkBundle.pack(listOf(File(dir, "missing.apk")), out); fail("a missing part must fail") } catch (e: Exception) { /* expected */ }
        assertFalse(out.exists())
    }

    @Test fun onlyApkAndApksAreInstallable() {
        assertTrue(ApkBundle.isInstallable("App.APK")); assertTrue(ApkBundle.isInstallable("x.apks"))
        assertFalse(ApkBundle.isInstallable("photo.jpg")); assertFalse(ApkBundle.isInstallable("apk")); assertFalse(ApkBundle.isInstallable("a.apk.txt"))
    }
}
