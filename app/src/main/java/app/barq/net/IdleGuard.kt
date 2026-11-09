package app.barq.net

import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Hard deadline for the first request on a freshly accepted socket. A plain read timeout is not
 * enough: a peer that trickles one byte every few seconds never trips it and holds one of the
 * limited connection slots forever. The guard closes the socket when the wall-clock deadline
 * passes, unless the server disarmed it after the first complete request (or HELLO) arrived.
 */
object IdleGuard {
    const val FIRST_REQUEST_MS = 10_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wamda-idle-guard").apply { isDaemon = true }
    }

    class Watch internal constructor(private val task: ScheduledFuture<*>) {
        fun disarm() { task.cancel(false) }
    }

    fun arm(socket: Socket, timeoutMs: Long = FIRST_REQUEST_MS): Watch =
        Watch(scheduler.schedule({ runCatching { socket.close() } }, timeoutMs, TimeUnit.MILLISECONDS))
}
