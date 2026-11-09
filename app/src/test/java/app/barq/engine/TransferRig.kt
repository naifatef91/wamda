package app.barq.engine

import app.barq.net.usb.Mux
import app.barq.net.usb.UsbPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.security.MessageDigest
import java.util.BitSet
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A duplex pipe that records every write and can be "unplugged" after N bytes or on demand. */
class Cable(private val rx: Pipe.SourceChannel, private val tx: Pipe.SinkChannel, private val pullAfter: Long = Long.MAX_VALUE) : UsbPipe {
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

/**
 * A ChunkSink on a temp directory that remembers progress across sessions, like ReceiveStore does.
 * Names and folders go through the same NameSanitizer the phone applies, so what lands on disk is what
 * the user would see in the Download/Wamda folder.
 */
class DirSink(private val dir: File) : ChunkSink {
    private class F(val raf: RandomAccessFile, val size: Long) { val done = BitSet(); @Synchronized fun mark(i: Int) = done.set(i) }
    private val files = HashMap<Int, F>()
    private val known = HashMap<String, F>()
    val bytesWritten = AtomicLong(0)

    /** Every missing-chunk map this sink returned, one per session, in order: what the sender is told by NEED. */
    val needs = ArrayList<Map<Int, List<Int>>>()

    /** Called after each chunk is stored, with (fileId, chunkIndex); lets a test pull the cable at an exact chunk. */
    @Volatile var afterChunk: (fileId: Int, index: Int) -> Unit = { _, _ -> }
    @Volatile private var dead = false
    /** After this, every further write fails, as if the connection had been cut at that instant. */
    fun cut() { dead = true }

    fun fileOf(e: FileEntry): File {
        val folder = NameSanitizer.relativePath(e.path)
        val parent = if (folder.isEmpty()) dir else File(dir, folder)
        return File(parent, NameSanitizer.fileName(e.name))
    }

    @Synchronized override fun prepare(manifest: TransferManifest): Map<Int, List<Int>> {
        dead = false
        val missing = LinkedHashMap<Int, List<Int>>()
        for (e in manifest.files) {
            val target = fileOf(e)
            val key = "${target.path}|${e.size}"
            val f = known.getOrPut(key) {
                target.parentFile?.mkdirs()
                F(RandomAccessFile(target, "rw").also { it.setLength(e.size) }, e.size)
            }
            files[e.id] = f
            val todo = (0 until Protocol.chunkCount(e.size)).filter { !f.done.get(it) }
            if (todo.isNotEmpty()) missing[e.id] = todo
        }
        needs += missing
        return missing
    }

    override fun write(fileId: Int, offset: Long, buf: ByteArray, len: Int) {
        if (dead) throw IOException("connection cut")
        val f = files[fileId] ?: throw ProtocolException("unknown file $fileId")
        if (!ChunkMath.writeAllowed(f.size, offset, len)) throw ProtocolException("write outside file bounds")
        synchronized(f.raf) { f.raf.seek(offset); f.raf.write(buf, 0, len) }
        val idx = ChunkMath.indexOf(offset)
        f.mark(idx)
        bytesWritten.addAndGet(len.toLong())
        afterChunk(fileId, idx)
    }

    @Synchronized override fun bytesAlready(fileId: Int): Long {
        val f = files[fileId] ?: return 0
        return (0 until Protocol.chunkCount(f.size)).filter { f.done.get(it) }.sumOf { ChunkMath.lengthOf(f.size, it).toLong() }
    }

    @Synchronized override fun incompleteIds(): List<Int> =
        files.filter { (_, f) -> f.done.cardinality() < Protocol.chunkCount(f.size) }.keys.sorted()

    /** Chunk indexes of [fileId] that have arrived. */
    @Synchronized fun doneChunks(fileId: Int): List<Int> = files[fileId]?.done?.stream()?.toArray()?.toList().orEmpty()

    override fun finish() {}
    fun closeAll() = known.values.forEach { runCatching { it.raf.close() } }
}

class Session(val result: SenderResult?, val error: Throwable?, val cable: Cable, val sessionDone: Boolean)

/** Deterministic pseudo-random file of [size] bytes under [src], offered as a send item. */
fun testItem(id: Int, name: String, size: Int, src: File, path: String = ""): SendItem {
    val data = ByteArray(size).also { Random(id.toLong()).nextBytes(it) }
    val dir = if (path.isEmpty()) src else File(src, path).also { it.mkdirs() }
    val f = File(dir, name).also { it.writeBytes(data) }
    return SendItem(FileEntry(id, name, size.toLong(), "application/octet-stream", 0, path, null)) {
        val raf = RandomAccessFile(f, "r"); ReadableFile(raf.channel) { raf.close() }
    }
}

fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()

/**
 * One cable with a real Sender on one end and a real Receiver on the other, kept open so several
 * transfers can go through it one after another (the "send more" case). Streams default to 1 so
 * chunks travel in order and a test can cut the connection at an exact chunk.
 */
class Rig(
    private val scope: CoroutineScope,
    private val sink: ChunkSink,
    pullAfter: Long = Long.MAX_VALUE,
    streams: Int = 1,
    private val token: String = "123456",
    onIncoming: suspend (TransferManifest, String?) -> Boolean = { _, _ -> true },
) {
    private val a2b = Pipe.open()
    private val b2a = Pipe.open()
    val senderCable = Cable(rx = b2a.source(), tx = a2b.sink(), pullAfter = pullAfter)
    private val receiverCable = Cable(rx = a2b.source(), tx = b2a.sink())
    private val muxA = Mux(senderCable).also { it.start() }
    private val muxB = Mux(receiverCable).also { it.start() }
    private val results = Channel<ReceiverResult>(Channel.UNLIMITED)
    private val ids = AtomicInteger(1)
    val dropped = Channel<Throwable>(Channel.UNLIMITED)

    private val receiver = Receiver(
        port = 0, token = token, deviceName = "rx", store = sink,
        onIncoming = onIncoming, onPlanned = { _, _ -> }, onSessionDone = { results.trySend(it) },
        onDropped = { dropped.trySend(it) },
        streams = streams, encrypted = false,
    )

    init { scope.launch { for (link in muxB.incoming) launch { receiver.admit(link) } } }

    private val transport = object : SenderTransport {
        override val writesHello = true
        override suspend fun control(): Link = muxA.open(ids.getAndIncrement())
        override suspend fun data(): Link = muxA.open(ids.getAndIncrement())
    }

    /** Pulls the cable now, from either side. */
    fun unplug() { senderCable.close(); receiverCable.close() }

    fun send(items: List<SendItem>, timeoutMs: Long = 80_000): Session = runBlocking {
        var result: SenderResult? = null
        var error: Throwable? = null
        try { result = withTimeout(timeoutMs) { Sender(transport, token, "tx", items, onPlanned = { _, _ -> }).run() } } catch (e: Throwable) { error = e }
        val finished = if (error == null) runCatching { withTimeout(20_000) { results.receive() } }.isSuccess else false
        Session(result, error, senderCable, finished)
    }

    fun close() { muxA.close(); muxB.close() }
}

fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
fun CoroutineScope.shutdown() = cancel()
