package app.barq.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random

class QuickHashTest {
    private val dir: File = Files.createTempDirectory("wamda-qh").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private fun file(name: String, size: Int, seed: Long = 1): File = File(dir, name).also { f ->
        f.writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) })
    }

    private fun hash(f: File): String = RandomAccessFile(f, "r").use { QuickHash.of(it.channel, f.length()) }

    private fun mutate(f: File, at: Long) {
        RandomAccessFile(f, "rw").use { r -> r.seek(at); val b = r.read(); r.seek(at); r.write(b xor 0xFF) }
    }

    @Test fun fingerprintIsDeterministicAndVersioned() {
        val f = file("a.bin", 10_000)
        assertEquals(hash(f), hash(f))
        assertTrue(hash(f).startsWith("q2:"))
    }

    @Test fun smallFilesAreHashedWhole() {
        // Up to 128 KiB every byte counts, including the very last one and ones deep in the middle.
        val f = file("small.bin", QuickHash.WHOLE_FILE_MAX)
        val base = hash(f)
        for (at in listOf(0L, 1_000L, 70_000L, f.length() - 1)) {
            val g = file("small-$at.bin", QuickHash.WHOLE_FILE_MAX).also { mutate(it, at) }
            assertNotEquals("byte $at must be covered", base, hash(g))
        }
    }

    @Test fun largeFilesSampleStartMiddleAndEnd() {
        val size = 5 * 1024 * 1024
        val f = file("big.bin", size)
        val base = hash(f)
        val mid = (size - QuickHash.SAMPLE) / 2 + 100L
        for (at in listOf(10L, mid, size - 10L)) {
            val g = file("big-$at.bin", size).also { mutate(it, at) }
            assertNotEquals("sampled byte $at must change the fingerprint", base, hash(g))
        }
        // Outside the three windows nothing is read, which is the point: that is what makes it cheap.
        val h = file("big-out.bin", size).also { mutate(it, 1_000_000) }
        assertEquals(base, hash(h))
    }

    @Test fun sizeIsPartOfTheFingerprint() {
        val a = File(dir, "a").also { it.writeBytes(ByteArray(300_000)) }
        val b = File(dir, "b").also { it.writeBytes(ByteArray(300_001)) }
        assertNotEquals(hash(a), hash(b)) // identical sampled bytes (all zero), different length
    }

    @Test fun emptyAndTinyFilesWork() {
        assertTrue(hash(File(dir, "empty").also { it.writeBytes(ByteArray(0)) }).startsWith("q2:"))
        assertNotEquals(hash(File(dir, "e1").also { it.writeBytes(ByteArray(1)) }), hash(File(dir, "e2").also { it.writeBytes(ByteArray(2)) }))
    }

    @Test(timeout = 60_000) fun threeThousandFilesAreFingerprintedInParallelWellUnderTwoSeconds() = runBlocking {
        val files = (1..3000).map { i -> File(dir, "p$i.jpg").also { RandomAccessFile(it, "rw").use { r -> r.setLength(200_000L + i) } } }
        val t0 = System.nanoTime()
        val hashes = files.mapParallel(8) { _, f -> hash(f) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("QuickHash: 3000 files (~200 KB each) fingerprinted in $ms ms on this machine")
        assertEquals(3000, hashes.size)
        assertEquals("results keep their order", hash(files[1234]), hashes[1234])
        assertTrue("took $ms ms", ms < 2000)
    }
}
