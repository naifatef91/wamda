package app.barq.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class FileProgress(
    val id: Int,
    val name: String,
    val size: Long,
    val done: Long,
    val skipped: Boolean = false,
)

data class Snapshot(
    val bytesDone: Long,
    val bytesTotal: Long,
    val speedBps: Double,
    val avgBps: Double,
    val etaSec: Long,
    val elapsedMs: Long,
    val streams: Int,
    val files: List<FileProgress>,
    /** Milliseconds spent per stage, summed across streams: "read" (disk), "net" (socket + TLS), "write" (disk). */
    val profileMs: Map<String, Long> = emptyMap(),
) {
    val fraction: Float get() = if (bytesTotal == 0L) 1f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

/**
 * Thread-safe byte counters shared by all streams, sampled periodically by the UI.
 * Instantaneous speed is an exponential moving average over the sample interval.
 */
class TransferStats(entries: List<FileEntry>, skippedIds: Set<Int> = emptySet(), prefilled: Map<Int, Long> = emptyMap()) {
    private val entries = entries.associateBy { it.id }
    private val skipped = skippedIds
    private val perFile = ConcurrentHashMap<Int, AtomicLong>()
    private val total = AtomicLong()

    val bytesTotal: Long = entries.filter { it.id !in skippedIds }.sumOf { it.size } - prefilled.values.sum()
    private val startNs = System.nanoTime()
    private var lastSampleNs = startNs
    private var lastSampleBytes = 0L
    private var ema = 0.0
    @Volatile var streams: Int = 0

    init {
        prefilled.forEach { (id, bytes) -> perFile.getOrPut(id) { AtomicLong() }.set(bytes) }
    }

    fun add(fileId: Int, n: Int) {
        if (n <= 0) return
        perFile.getOrPut(fileId) { AtomicLong() }.addAndGet(n.toLong())
        total.addAndGet(n.toLong())
    }

    private val stages = ConcurrentHashMap<String, AtomicLong>()

    /** Accumulates time spent in a stage (nanoseconds), from any stream. */
    fun addTime(stage: String, nanos: Long) {
        if (nanos > 0) stages.getOrPut(stage) { AtomicLong() }.addAndGet(nanos)
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = System.nanoTime()
        val bytes = total.get()
        val dt = (now - lastSampleNs) / 1e9
        if (dt > 0.2) {
            val inst = (bytes - lastSampleBytes) / dt
            ema = if (ema == 0.0) inst else ema * 0.6 + inst * 0.4
            lastSampleNs = now
            lastSampleBytes = bytes
        }
        val elapsed = (now - startNs) / 1e9
        val avg = if (elapsed > 0) bytes / elapsed else 0.0
        val remaining = (bytesTotal - bytes).coerceAtLeast(0)
        val eta = if (ema > 1) (remaining / ema).toLong() else -1L
        val files = entries.values.sortedBy { it.id }.map { e ->
            FileProgress(e.id, e.name, e.size, perFile[e.id]?.get()?.coerceAtMost(e.size) ?: 0L, e.id in skipped)
        }
        val profile = stages.entries.associate { it.key to it.value.get() / 1_000_000 }
        return Snapshot(bytes, bytesTotal, ema, avg, eta, (elapsed * 1000).toLong(), streams, files, profile)
    }
}
