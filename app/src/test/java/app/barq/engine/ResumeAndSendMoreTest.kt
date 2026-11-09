package app.barq.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A 10-chunk file whose connection is cut right after chunk 5 arrives. After reconnecting, the receiver's
 * NEED must ask for chunks 6 to 10 only (indexes 5..9), the sender must send exactly those, and a second
 * batch must then go through the same open session without pairing again.
 */
class ResumeAndSendMoreTest {
    private val work: File = Files.createTempDirectory("wamda-resume").toFile()
    private val scope = newScope()
    @After fun cleanup() { scope.shutdown(); work.deleteRecursively() }

    private val chunk = Protocol.CHUNK_SIZE.toInt()
    /** Nine full chunks and a short tenth one. */
    private val bigSize = 9 * chunk + 1_234_567

    @Test(timeout = 240_000)
    fun cutAtChunkFiveThenNeedAsksForSixToTenOnlyThenASecondBatchInTheSameSession() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val big = testItem(1, "movie.bin", bigSize, src)
        assertEquals(10, Protocol.chunkCount(bigSize.toLong()))
        val sink = DirSink(dst)

        // ---- first connection: one data stream, so chunks arrive in order; cut the instant chunk 5 (index 4) is stored ----
        val first = Rig(scope, sink, streams = 1)
        sink.afterChunk = { _, index -> if (index == 4) { sink.cut(); first.unplug() } }
        val s1 = try { first.send(listOf(big)) } finally { first.close() }
        assertNotNull("cutting the connection must fail the first session", s1.error)
        assertEquals("exactly chunks 1..5 are on the receiver", listOf(0, 1, 2, 3, 4), sink.doneChunks(1))
        assertEquals(listOf(1), sink.incompleteIds())

        // ---- reconnect: a new cable, same receiver-side store ----
        sink.afterChunk = { _, _ -> }
        val second = Rig(scope, sink, streams = 1)
        try {
            val s2 = second.send(listOf(big))
            assertEquals("resume failed: ${s2.error}", null, s2.error)
            assertTrue(s2.sessionDone)

            // What the receiver told the sender in NeedResponse: chunks 6..10, nothing else.
            assertEquals(mapOf(1 to listOf(5, 6, 7, 8, 9)), sink.needs.last())
            val expectedBytes = bigSize.toLong() - 5L * chunk
            assertEquals("only the missing chunks were sent", expectedBytes, s2.result!!.bytesSent)
            assertEquals("nothing was skipped as already complete", emptyList<Int>(), s2.result!!.skippedFiles)
            assertEquals(emptyList<Int>(), s2.result!!.incompleteFiles)
            assertTrue(sink.incompleteIds().isEmpty())
            assertEquals(sha(File(src, "movie.bin")), sha(sink.fileOf(big.entry)))

            // ---- "send more": a second batch through the same open session, no new pairing ----
            val more = listOf(testItem(2, "notes.txt", 10_000, src), testItem(3, "photo.jpg", 3 * chunk + 5, src))
            val s3 = second.send(more)
            assertEquals("second batch failed: ${s3.error}", null, s3.error)
            assertTrue(s3.sessionDone)
            assertEquals(mapOf(2 to listOf(0), 3 to listOf(0, 1, 2, 3)), sink.needs.last())
            assertEquals(more.sumOf { it.entry.size }, s3.result!!.bytesSent)
            for (m in more) assertEquals("${m.entry.name} differs", sha(File(src, m.entry.name)), sha(sink.fileOf(m.entry)))
            // The first file is still intact and was not touched again.
            assertEquals(sha(File(src, "movie.bin")), sha(sink.fileOf(big.entry)))

            // Offering the very same files again sends nothing: everything is already there.
            val s4 = second.send(more)
            assertEquals(null, s4.error)
            assertEquals(0L, s4.result!!.bytesSent)
            assertEquals(listOf(2, 3), s4.result!!.skippedFiles)
        } finally { second.close(); sink.closeAll() }
    }
}
