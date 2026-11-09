package app.barq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Applies [transform] to every element with at most [parallelism] running at once, keeping the order of
 * the results. Used to fingerprint thousands of picked files without doing it one after another: the
 * work is small reads, so overlapping them hides the storage latency.
 */
suspend fun <T, R> List<T>.mapParallel(parallelism: Int = 8, transform: (index: Int, item: T) -> R): List<R> = coroutineScope {
    val gate = Semaphore(parallelism.coerceAtLeast(1))
    mapIndexed { i, item -> async(Dispatchers.IO) { gate.withPermit { transform(i, item) } } }.awaitAll()
}
