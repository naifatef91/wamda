package app.barq.engine

import app.barq.net.usb.Mux
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real Sender and Receiver (frame protocol, NEED, parallel streams, resume) running over the real
 * USB multiplexer, through a pipe whose frames are measured. Proves that 4 MiB protocol chunks cross a
 * cable whose multiplexer rejects frames above 60 KiB, that a 50 MB file plus 500 small ones arrive
 * byte-identical, and that after the cable is pulled mid-transfer only the missing chunks are sent.
 */
class UsbTransferIntegrationTest {
    private val work: File = Files.createTempDirectory("wamda-usb").toFile()
    private val scope = newScope()
    @After fun cleanup() { scope.shutdown(); work.deleteRecursively() }

    private fun runSession(items: List<SendItem>, sink: DirSink, pullAfter: Long): Session {
        val rig = Rig(scope, sink, pullAfter = pullAfter, streams = 4)
        return try { rig.send(items) } finally { rig.close() }
    }

    @Test(timeout = 300_000)
    fun bigFileAndFiveHundredSmallOnesCrossA60KiBCableIntact() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val items = listOf(testItem(1, "big.bin", 50 * 1024 * 1024, src)) +
            (2..501).map { testItem(it, "small-$it.dat", 1_000 + (it * 197) % 90_000, src) }
        val sink = DirSink(dst)

        val s = runSession(items, sink, pullAfter = Long.MAX_VALUE)

        assertEquals("sender failed: ${s.error}", null, s.error)
        assertTrue("receiver never reported the session complete", s.sessionDone)
        assertTrue("a protocol chunk is 4 MiB but no USB write may exceed one mux frame, saw ${s.cable.largestWrite.get()}",
            s.cable.largestWrite.get() <= 4 + Mux.MAX_FRAME)
        assertTrue("the big file's chunks really were split across frames", s.cable.written.get() > 50L * 1024 * 1024)
        assertTrue(sink.incompleteIds().isEmpty())
        for (it in items) assertEquals("${it.entry.name} differs", sha(File(src, it.entry.name)), sha(File(dst, it.entry.name)))
        sink.closeAll()
    }

    @Test(timeout = 300_000)
    fun unpluggingMidTransferThenReconnectingSendsOnlyWhatIsMissing() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val items = listOf(testItem(1, "big.bin", 50 * 1024 * 1024, src)) + (2..201).map { testItem(it, "small-$it.dat", 5_000 + it * 100, src) }
        val total = items.sumOf { it.entry.size }
        val sink = DirSink(dst)

        val first = runSession(items, sink, pullAfter = 20L * 1024 * 1024)
        assertTrue("the cable pull must surface as a failure", first.error != null)
        // Frames already in flight when the cable was pulled may still be stored for a moment: wait until the count stops moving.
        var got = sink.bytesWritten.get()
        while (true) { Thread.sleep(400); val now = sink.bytesWritten.get(); if (now == got) break; got = now }
        assertTrue("some, but not all, bytes arrived before the cut ($got of $total)", got in 1 until total)
        assertFalse(sink.incompleteIds().isEmpty())

        val second = runSession(items, sink, pullAfter = Long.MAX_VALUE)
        assertEquals("resume failed: ${second.error}", null, second.error)
        assertTrue(second.sessionDone)
        val resent = second.result!!.bytesSent
        assertTrue("second session sent $resent bytes, which is not less than everything ($total); chunks already on the phone were re-sent", resent < total)
        assertTrue("it must still send at least what was missing (${total - got})", resent >= total - got)
        assertTrue(sink.incompleteIds().isEmpty())
        for (it in items) assertEquals("${it.entry.name} differs after resume", sha(File(src, it.entry.name)), sha(File(dst, it.entry.name)))
        sink.closeAll()
    }
}
