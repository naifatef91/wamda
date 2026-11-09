package app.barq.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** A virtual 5 GB file: every offset is 8 bytes wide and nothing wraps a 32-bit integer. */
class LargeFileOffsetTest {
    private val gib = 1L shl 30
    private val five = 5 * gib
    private val chunk = Protocol.CHUNK_SIZE
    private val work: File = Files.createTempDirectory("wamda-big").toFile()
    @After fun cleanup() { work.deleteRecursively() }

    @Test fun chunkCountAndOffsetsForFiveGigabytes() {
        assertEquals(1280, Protocol.chunkCount(five))
        assertEquals(1281, Protocol.chunkCount(five + 7))
        assertEquals(1, Protocol.chunkCount(0))
        for (i in 0 until Protocol.chunkCount(five)) {
            val off = ChunkMath.offsetOf(i)
            assertEquals(i.toLong() * chunk, off)
            assertTrue("offset must never be negative, chunk $i gave $off", off >= 0)
        }
        // The naive 32-bit product wraps long before the end of a 5 GB file; the Long one does not.
        val last = 1279
        assertEquals(5_364_514_816L, ChunkMath.offsetOf(last))
        assertTrue(ChunkMath.offsetOf(last) > Int.MAX_VALUE)
        assertTrue("a 32-bit multiply would have wrapped here", (last * chunk.toInt()).toLong() != ChunkMath.offsetOf(last))
        assertEquals(ChunkMath.offsetOf(last), (last.toLong() * (4 * 1024 * 1024)))
    }

    @Test fun everyChunkLengthAddsUpToTheFileSizeExactly() {
        for (size in listOf(five, five + 7, five - 1, 4L * gib, 4L * gib + 1, 8L shl 40)) {
            // Summing 2 million chunk lengths for the 8 TiB case is cheap; the total must equal the size.
            var total = 0L
            for (i in 0 until Protocol.chunkCount(size)) total += ChunkMath.lengthOf(size, i)
            assertEquals("lengths for $size", size, total)
        }
        assertEquals(7, ChunkMath.lengthOf(five + 7, 1280))
        assertEquals(chunk.toInt(), ChunkMath.lengthOf(five, 1279))
        assertEquals(0, ChunkMath.lengthOf(five, 1280)) // past the end
    }

    @Test fun writesAreBoundedBy64BitArithmetic() {
        // Inside the file, on a chunk boundary.
        assertTrue(ChunkMath.writeAllowed(five, ChunkMath.offsetOf(1279), chunk.toInt()))
        assertTrue(ChunkMath.writeAllowed(five + 7, ChunkMath.offsetOf(1280), 7))
        // One byte too long, or past the end.
        assertFalse(ChunkMath.writeAllowed(five + 7, ChunkMath.offsetOf(1280), 8))
        assertFalse(ChunkMath.writeAllowed(five, ChunkMath.offsetOf(1280), 1))
        assertFalse(ChunkMath.writeAllowed(five, ChunkMath.offsetOf(1279), chunk.toInt() + 1))
        // Not on a chunk boundary, or negative.
        assertFalse(ChunkMath.writeAllowed(five, ChunkMath.offsetOf(1279) + 1, 10))
        assertFalse(ChunkMath.writeAllowed(five, -chunk, 10))
        assertFalse(ChunkMath.writeAllowed(five, 0, -1))
        // The classic wrap: offset 4 GiB truncated to 32 bits is 0, which would "fit" in a 3 GiB file. It must not.
        assertFalse(ChunkMath.writeAllowed(3 * gib, 4 * gib, chunk.toInt()))
        assertFalse(ChunkMath.writeAllowed(3 * gib, (4 * gib) + chunk, chunk.toInt()))
        assertEquals(0, (4 * gib).toInt())
        // 4 GiB is a perfectly good offset in a 5 GiB file.
        assertTrue(ChunkMath.writeAllowed(five, 4 * gib, chunk.toInt()))
        // The largest the receiver accepts (8 TiB) and a size at the Long limit do not overflow the comparison.
        assertTrue(ChunkMath.writeAllowed(8L shl 40, (8L shl 40) - chunk, chunk.toInt()))
        assertFalse(ChunkMath.writeAllowed(Long.MAX_VALUE, Long.MAX_VALUE - 1, 10))
        assertFalse(ChunkMath.writeAllowed(10, Long.MAX_VALUE - (Long.MAX_VALUE % chunk), 1))
    }

    @Test fun theOffsetIsEightBytesOnTheWire() {
        val offsets = listOf(0L, 4 * gib - 1, 4 * gib, five - chunk, Long.MAX_VALUE)
        for (off in offsets) {
            val bytes = ByteArrayOutputStream().also { FrameIO.writeHeader(DataOutputStream(it), Protocol.Type.DATA, Protocol.Flag.LAST_CHUNK, 7, off, 123) }.toByteArray()
            assertEquals("header is 1+1+4+8+4 bytes", 18, bytes.size)
            val h = FrameIO.readHeader(DataInputStream(ByteArrayInputStream(bytes)))!!
            assertEquals(off, h.offset)
            assertEquals(7, h.fileId); assertEquals(123, h.length)
        }
    }

    @Test(timeout = 120_000) fun aRealFileAbove4GbTakesAWriteAtItsLastChunk() {
        // Sparse, so the 5 GB is never actually allocated on disk. Skipped where the file system cannot do that.
        val f = File(work, "five-gb.bin")
        val size = five + 7
        try {
            Files.newByteChannel(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { ch ->
                val off = ChunkMath.offsetOf(ChunkMath.indexOf(size - 1))
                assertEquals(five, off)
                ch.position(off)
                ch.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7)))
                assertEquals(size, ch.size())
            }
        } catch (e: java.io.IOException) { assumeNoException("file system cannot make a sparse 5 GB file", e) }
        assertEquals(size, f.length())
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(five)
            val tail = ByteArray(7); raf.readFully(tail)
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7), tail.toList())
            raf.seek(five - 1)
            assertEquals(0, raf.read()) // the hole reads back as zeros, not as the tail wrapped to the start
            raf.seek(0)
            assertEquals(0, raf.read())
        }
    }
}
