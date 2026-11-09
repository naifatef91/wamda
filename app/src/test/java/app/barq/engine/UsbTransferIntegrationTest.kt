package app.barq.engine

import app.barq.net.usb.Mux
import app.barq.net.usb.UsbPipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.file.Files
import java.security.MessageDigest
import java.util.BitSet
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The real Sender and Receiver (frame protocol, NEED, parallel streams, resume) running over the real
 * USB multiplexer, through a pipe whose frames are measured. Proves that 4 MiB protocol chunks cross a
 * cable whose multiplexer rejects frames above 60 KiB, that a 50 MB file plus 500 small ones arrive
 * byte-identical, and that after the cable is pulled mid-transfer only the missing chunks are sent.
 */
class UsbTransferIntegrationTest {
    private val work: File = Files.createTempDirectory("wamda-usb").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun cleanup() { scope.cancel(); work.deleteRecursively() }

    // ---- a duplex pipe that records every write and can be "unplugged" after N bytes ----

    private class Cable(private val rx: Pipe.SourceChannel, private val tx: Pipe.SinkChannel, private val pullAfter: Long = Long.MAX_VALUE) : UsbPipe {
        val written = AtomicLong(0)
        val largestWrite = AtomicInteger(0)
        override val description = "test-cable"
        override fun read(buf: ByteArray, off: Int, len: Int): Int = try { rx.read(ByteBuffer.wrap(buf, off, len)) } catch (e: IOException) { -1 }
        override fun write(buf: ByteArray, off: Int, len: Int) {
            if (written.get() + len > pullAfter) { close(); throw IOException("cable unplugged") }
            written.addAndGet(len.toLong())
            largestWrite.accumulateAndGet(len) { a, b -> maxOf(a, b) }
            val bb = ByteBuffer.wrap(buf, off, len)
            while (bb.hasRemaining()) tx.write(bb)
        }
        override fun close() { runCatching { rx.close() }; runCatching { tx.close() } }
    }

    // ---- a ChunkSink on a temp directory that remembers progress across sessions, like ReceiveStore ----

    private class DirSink(private val dir: File) : ChunkSink {
        private class F(val raf: RandomAccessFile, val size: Long) { val done = BitSet(); @Synchronized fun mark(i: Int) = done.set(i) }
        private val files = HashMap<Int, F>()
        private val known = HashMap<String, F>()
        val bytesWritten = AtomicLong(0)

        @Synchronized override fun prepare(manifest: TransferManifest): Map<Int, List<Int>> {
            val missing = LinkedHashMap<Int, List<Int>>()
            for (e in manifest.files) {
                val key = "${e.name}|${e.size}"
                val f = known.getOrPut(key) { F(RandomAccessFile(File(dir, e.name), "rw").also { it.setLength(e.size) }, e.size) }
                files[e.id] = f
                val n = Protocol.chunkCount(e.size)
                val todo = (0 until n).filter { !f.done.get(it) }
                if (todo.isNotEmpty()) missing[e.id] = todo
            }
            return missing
        }
        override fun write(fileId: Int, offset: Long, buf: ByteArray, len: Int) {
            val f = files[fileId] ?: throw ProtocolException("unknown file $fileId")
            synchronized(f.raf) { f.raf.seek(offset); f.raf.write(buf, 0, len) }
            f.mark((offset / Protocol.CHUNK_SIZE).toInt())
            bytesWritten.addAndGet(len.toLong())
        }
        @Synchronized override fun bytesAlready(fileId: Int): Long {
            val f = files[fileId] ?: return 0
            return (0 until Protocol.chunkCount(f.size)).filter { f.done.get(it) }.sumOf { minOf(Protocol.CHUNK_SIZE.toLong(), f.size - it * Protocol.CHUNK_SIZE.toLong()) }
        }
        @Synchronized override fun incompleteIds(): List<Int> =
            files.filter { (_, f) -> f.done.cardinality() < Protocol.chunkCount(f.size) }.keys.sorted()
        override fun finish() {}
        fun closeAll() = known.values.forEach { runCatching { it.raf.close() } }
    }

    private class Session(val result: SenderResult?, val error: Throwable?, val cable: Cable, val sessionDone: Boolean)

    private fun item(id: Int, name: String, size: Int, src: File): SendItem {
        val data = ByteArray(size).also { Random(id.toLong()).nextBytes(it) }
        File(src, name).writeBytes(data)
        val f = File(src, name)
        return SendItem(FileEntry(id, name, size.toLong(), "application/octet-stream", 0, "", null)) {
            val raf = RandomAccessFile(f, "r"); ReadableFile(raf.channel) { raf.close() }
        }
    }

    private fun runSession(items: List<SendItem>, sink: DirSink, pullAfter: Long): Session = runBlocking {
        val a2b = Pipe.open(); val b2a = Pipe.open()
        val sender = Cable(rx = b2a.source(), tx = a2b.sink(), pullAfter = pullAfter)
        val receiver = Cable(rx = a2b.source(), tx = b2a.sink())
        val muxA = Mux(sender).also { it.start() }
        val muxB = Mux(receiver).also { it.start() }
        val done = CompletableDeferred<ReceiverResult>()
        val rx = Receiver(
            port = 0, token = "123456", deviceName = "rx", store = sink,
            onIncoming = { _, _ -> true }, onPlanned = { _, _ -> }, onSessionDone = { done.complete(it) },
            streams = 4, encrypted = false,
        )
        scope.launch { for (link in muxB.incoming) launch { rx.admit(link) } }

        val ids = AtomicInteger(1)
        val transport = object : SenderTransport {
            override val writesHello = true
            override suspend fun control(): Link = muxA.open(ids.getAndIncrement())
            override suspend fun data(): Link = muxA.open(ids.getAndIncrement())
        }
        var result: SenderResult? = null; var error: Throwable? = null
        try { result = withTimeout(80_000) { Sender(transport, "123456", "tx", items, onPlanned = { _, _ -> }).run() } } catch (e: Throwable) { error = e }
        val finished = if (error == null) runCatching { withTimeout(20_000) { done.await() } }.isSuccess else false
        muxA.close(); muxB.close()
        Session(result, error, sender, finished)
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()

    @Test(timeout = 300_000)
    fun bigFileAndFiveHundredSmallOnesCrossA60KiBCableIntact() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val items = listOf(item(1, "big.bin", 50 * 1024 * 1024, src)) +
            (2..501).map { item(it, "small-$it.dat", 1_000 + (it * 197) % 90_000, src) }
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
        val items = listOf(item(1, "big.bin", 50 * 1024 * 1024, src)) + (2..201).map { item(it, "small-$it.dat", 5_000 + it * 100, src) }
        val total = items.sumOf { it.entry.size }
        val sink = DirSink(dst)

        val first = runSession(items, sink, pullAfter = 20L * 1024 * 1024)
        assertTrue("the cable pull must surface as a failure", first.error != null)
        val got = sink.bytesWritten.get()
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
