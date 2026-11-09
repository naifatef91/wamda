package app.barq.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class IdleGuardTest {
    private fun pair(): Triple<ServerSocket, Socket, Socket> {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        return Triple(server, client, server.accept())
    }

    @Test fun connectionThatSendsNothingIsClosedAfterTheDeadline() {
        val (server, client, accepted) = pair()
        client.soTimeout = 3000
        val t0 = System.nanoTime()
        IdleGuard.arm(accepted, 300)
        val n = try { client.getInputStream().read() } catch (e: SocketTimeoutException) { Int.MIN_VALUE }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("the server side must hang up, giving the client EOF", -1, n)
        assertTrue("closed after ${ms}ms", ms in 250..2500)
        assertTrue(accepted.isClosed)
        client.close(); server.close()
    }

    @Test fun connectionThatTricklesBytesIsStillClosedByTheWallClockDeadline() {
        val (server, client, accepted) = pair()
        accepted.soTimeout = 150 // a per-read timeout alone would never fire for a trickle
        IdleGuard.arm(accepted, 500)
        val writer = Thread { runCatching { repeat(40) { client.getOutputStream().write(0x41); client.getOutputStream().flush(); Thread.sleep(50) } } }
        writer.start()
        val t0 = System.nanoTime()
        runCatching { while (accepted.getInputStream().read() >= 0) { /* keep reading the trickle */ } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("trickle cut off after ${ms}ms", ms in 400..2500)
        assertTrue(accepted.isClosed)
        writer.join(); client.close(); server.close()
    }

    @Test fun disarmedConnectionIsLeftAlone() {
        val (server, client, accepted) = pair()
        val watch = IdleGuard.arm(accepted, 300)
        client.getOutputStream().write(1); client.getOutputStream().flush()
        assertEquals(1, accepted.getInputStream().read())
        watch.disarm()
        Thread.sleep(700)
        assertTrue("a request that arrived in time must not be closed", !accepted.isClosed)
        client.close(); accepted.close(); server.close()
    }
}
