package app.barq.engine

import app.barq.crypto.Tls
import app.barq.data.TrustPolicy
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full sender/receiver handshake over real TLS 1.3 on loopback, each side with its own certificate.
 * "Trusted device" is bound to the certificate fingerprint, never to the name: a device that keeps the
 * name but presents another certificate is not auto-accepted, and a receiver whose certificate is not the
 * one in the QR code is not talked to at all.
 */
class MutualCertHandshakeTest {
    private val work: File = Files.createTempDirectory("wamda-tls").toFile()
    private val scope = newScope()
    @After fun cleanup() { scope.shutdown(); work.deleteRecursively() }

    private val rxId = Tls.newIdentity()
    private val phoneId = Tls.newIdentity()      // the genuine, previously trusted sender
    private val impostorId = Tls.newIdentity()   // same name, different certificate
    private val name = "هاتف أحمد"

    private class Seen(val name: String, val peerId: String?, val auto: Boolean)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val port: Int = ServerSocket(0).use { it.localPort }

    /** A receiver that auto-accepts only (name AND fingerprint) pairs saved when the user trusted a device. */
    private fun startReceiver(sink: ChunkSink, trusted: Map<String, String>) {
        val rx = Receiver(
            port = port, token = "654321", deviceName = "rx", store = sink,
            onIncoming = { m, peerId ->
                val auto = TrustPolicy.autoAccept(true, trusted, m.senderName, peerId)
                seen += Seen(m.senderName, peerId, auto)
                auto // a real phone would show the dialog here; "not trusted" means the user has to say yes, so the test declines
            },
            onPlanned = { _, _ -> }, onSessionDone = { }, streams = 2, encrypted = true, identity = rxId,
        )
        scope.launch { rx.run() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; return } catch (_: Exception) { Thread.sleep(50) }
        }
        error("receiver did not start listening")
    }

    private fun send(items: List<SendItem>, senderId: Tls.Identity, pinned: String, deviceName: String = name): Pair<SenderResult?, Throwable?> {
        var result: SenderResult? = null; var error: Throwable? = null
        try {
            result = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(60_000) {
                    Sender(DialTransport(null, "127.0.0.1", port, pinned, true, null, senderId), "654321", deviceName, items, onPlanned = { _, _ -> }).run()
                }
            }
        } catch (e: Throwable) { error = e }
        return result to error
    }

    @Test fun identitiesAreDistinctAndFingerprintsAreStable() {
        assertNotEquals(phoneId.fingerprint, impostorId.fingerprint)
        assertNotEquals(phoneId.fingerprint, rxId.fingerprint)
        assertEquals(phoneId.fingerprint, Tls.fingerprintOf(phoneId.certificate))
        assertEquals("SHA-256 as unpadded base64url is 43 characters", 43, phoneId.fingerprint.length)
        assertTrue(phoneId.fingerprint.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun theTrustRuleNeedsTheNameAndTheSameFingerprint() {
        val trusted = mapOf(name to phoneId.fingerprint)
        assertTrue(TrustPolicy.autoAccept(true, trusted, name, phoneId.fingerprint))
        assertFalse("same name, new certificate", TrustPolicy.autoAccept(true, trusted, name, impostorId.fingerprint))
        assertFalse("no certificate presented", TrustPolicy.autoAccept(true, trusted, name, null))
        assertFalse("other name, trusted certificate", TrustPolicy.autoAccept(true, trusted, "someone else", phoneId.fingerprint))
        assertFalse("auto-accept switched off", TrustPolicy.autoAccept(false, trusted, name, phoneId.fingerprint))
        assertFalse("nothing saved for the name", TrustPolicy.autoAccept(true, emptyMap(), name, phoneId.fingerprint))
        assertFalse("a prefix of the saved fingerprint is not a match", TrustPolicy.sameFingerprint(phoneId.fingerprint, phoneId.fingerprint.dropLast(1)))
    }

    @Test(timeout = 180_000) fun theGenuineTrustedPhoneIsAcceptedAndTheReceiverSeesItsFingerprint() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val sink = DirSink(dst)
        startReceiver(sink, mapOf(name to phoneId.fingerprint))
        val items = listOf(testItem(1, "ok.bin", 300_000, src))

        val (result, error) = send(items, phoneId, pinned = rxId.fingerprint)

        assertNull("genuine trusted phone failed: $error", error)
        assertNotNull(result)
        assertEquals(1, seen.size)
        assertEquals("the receiver identifies the sender by the certificate it presented", phoneId.fingerprint, seen[0].peerId)
        assertTrue(seen[0].auto)
        assertEquals(sha(File(src, "ok.bin")), sha(sink.fileOf(items[0].entry)))
        sink.closeAll()
    }

    @Test(timeout = 180_000) fun sameNameButAChangedCertificateIsRefusedAndNoBytesAreWritten() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val sink = DirSink(dst)
        startReceiver(sink, mapOf(name to phoneId.fingerprint))
        val items = listOf(testItem(1, "secret.bin", 300_000, src))

        // The impostor pins the right receiver certificate (it saw the QR code), uses the trusted name, but has its own key.
        val (result, error) = send(items, impostorId, pinned = rxId.fingerprint, deviceName = name)

        assertNull(result)
        assertNotNull("the transfer must be refused", error)
        assertTrue("refusal should be the receiver declining, got: $error", error!!.message.orEmpty().contains("declin", ignoreCase = true))
        assertEquals(1, seen.size)
        assertEquals(name, seen[0].name)
        assertEquals(impostorId.fingerprint, seen[0].peerId)
        assertNotEquals(phoneId.fingerprint, seen[0].peerId)
        assertFalse("not auto-accepted just because the name matches", seen[0].auto)
        assertEquals("nothing may be written", 0L, sink.bytesWritten.get())
        assertFalse(sink.fileOf(items[0].entry).exists())
        sink.closeAll()
    }

    @Test(timeout = 180_000) fun aReceiverWhoseCertificateIsNotTheOneInTheQrCodeIsNeverSpokenTo() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        val sink = DirSink(dst)
        startReceiver(sink, mapOf(name to phoneId.fingerprint))
        val items = listOf(testItem(1, "x.bin", 1_000, src))

        // The QR code (or BLE payload) carried a different fingerprint than the certificate this receiver presents.
        val (result, error) = send(items, phoneId, pinned = impostorId.fingerprint)

        assertNull(result)
        assertNotNull("the pinned fingerprint did not match, the handshake has to fail", error)
        assertTrue("no frame may reach the receiver, onIncoming ran for ${seen.map { it.name }}", seen.isEmpty())
        assertEquals(0L, sink.bytesWritten.get())
        sink.closeAll()
    }

    @Test(timeout = 180_000) fun anEmptyPinIsRefusedBeforeAnythingIsSent() {
        val src = File(work, "src").apply { mkdirs() }; val dst = File(work, "dst").apply { mkdirs() }
        startReceiver(DirSink(dst), mapOf(name to phoneId.fingerprint))
        val (_, error) = send(listOf(testItem(1, "x.bin", 10, src)), phoneId, pinned = "")
        assertNotNull(error)
        assertTrue("nothing reached the receiver", seen.isEmpty())
        assertTrue(error!!.message.orEmpty().contains("fingerprint"))
    }
}
