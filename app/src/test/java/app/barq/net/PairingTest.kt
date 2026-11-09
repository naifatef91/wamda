package app.barq.net

import app.barq.engine.NameSanitizer
import app.barq.engine.QrPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private val qr = QrPayload(
        ssid = "DIRECT-X4-Wamda", pass = "p4ssw0rd-SECRET", ip = "192.168.49.1", port = 48080,
        token = "493817", name = "Receiver Phone", freq = 5180, fp = "RECEIVER_FINGERPRINT_0123456789abcdefghijk", enc = true,
    )
    private val senderFp = "SENDER_FINGERPRINT_0123456789abcdefghijklmn"

    private fun ByteArray.containsText(s: String) = String(this, Charsets.ISO_8859_1).contains(s)

    // ---------- crypto ----------

    @Test fun sealedPayloadRoundTripsAndNeitherSideSendsItInClear() {
        val a = PairingCrypto.newEphemeral(); val b = PairingCrypto.newEphemeral()
        val sealed = PairingCrypto.seal(b, a.raw, "ctx".toByteArray(), "secret".toByteArray())
        assertFalse(sealed.containsText("secret"))
        assertArrayEquals("secret".toByteArray(), PairingCrypto.open(a, b.raw, "ctx".toByteArray(), sealed))
    }

    @Test fun wrongKeyWrongContextOrTamperedBytesAreRejected() {
        val a = PairingCrypto.newEphemeral(); val b = PairingCrypto.newEphemeral(); val eve = PairingCrypto.newEphemeral()
        val sealed = PairingCrypto.seal(b, a.raw, "ctx".toByteArray(), "secret".toByteArray())
        assertNull("an eavesdropper's key must not open it", PairingCrypto.open(eve, b.raw, "ctx".toByteArray(), sealed))
        assertNull("another request's context must not open it", PairingCrypto.open(a, b.raw, "other".toByteArray(), sealed))
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(PairingCrypto.open(a, b.raw, "ctx".toByteArray(), flipped))
        assertNull(PairingCrypto.open(a, b.raw, "ctx".toByteArray(), ByteArray(5)))
    }

    @Test fun pointsOffTheCurveAreRejected() {
        val good = PairingCrypto.newEphemeral().raw
        assertTrue(PairingCrypto.isValidPoint(good))
        assertFalse(PairingCrypto.isValidPoint(good.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }))
        assertFalse(PairingCrypto.isValidPoint(ByteArray(64)))
        assertFalse(PairingCrypto.isValidPoint(ByteArray(10)))
    }

    // ---------- wire format ----------

    @Test fun requestCarriesNoSecretAndSurvivesTheAwareSizeLimit() {
        val eph = PairingCrypto.newEphemeral()
        val bytes = PairingWire.encodeRequest("اسم جهاز عربي طويل جدا جدا جدا", senderFp, eph, maxNameBytes = 24)
        assertTrue("Aware messages are limited to 255 bytes, was ${bytes.size}", bytes.size <= 255)
        val parsed = PairingWire.decodeRequest(bytes)
        assertNotNull(parsed)
        assertEquals(senderFp, parsed!!.fp)
        assertArrayEquals(eph.raw, parsed.key)
    }

    @Test fun malformedRequestsAreIgnored() {
        assertNull(PairingWire.decodeRequest(ByteArray(0)))
        assertNull(PairingWire.decodeRequest("not json".toByteArray()))
        assertNull(PairingWire.decodeRequest("{\"v\":1,\"n\":\"x\",\"f\":\"abc\",\"k\":\"AAAA\"}".toByteArray()))
        assertNull(PairingWire.decodeRequest(ByteArray(1000) { 'a'.code.toByte() }))
    }

    @Test fun hostileDeviceNameIsCleanedBeforeItReachesADialog() {
        val eph = PairingCrypto.newEphemeral()
        val req = PairingWire.decodeRequest(PairingWire.encodeRequest("Ali‮\u0000\n  Phone", senderFp, eph))!!
        assertEquals("Ali Phone", req.name)
        assertEquals("Ali Phone", NameSanitizer.displayLabel("Ali‮\u0000\n  Phone"))
        assertTrue(NameSanitizer.displayLabel("x".repeat(500)).length <= 40)
    }

    // ---------- the receiver's state machine ----------

    private fun broker(decision: suspend (String, String) -> Decision, timeoutMs: Long = 30_000, approved: () -> Unit = {}) =
        ConnectBroker(scope, qr, decision, approved, decisionTimeoutMs = timeoutMs)

    private fun awaitReply(b: ConnectBroker, key: String, until: (ByteArray) -> Boolean): ByteArray {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) { val r = b.reply(key); if (until(r)) return r; Thread.sleep(20) }
        return b.reply(key)
    }

    @Test fun approvedRequesterGetsTheNetworkDetailsAndOnlyAfterApproval() = runBlocking {
        val gate = CompletableDeferred<Decision>()
        var approvedCalls = 0
        val b = broker({ _, _ -> gate.await() }, approved = { approvedCalls++ })
        val eph = PairingCrypto.newEphemeral()
        assertTrue(b.onRequest("dev1", PairingWire.encodeRequest("Sender", senderFp, eph)))

        val pending = b.reply("dev1")
        assertEquals(PairingWire.ST_PENDING, pending[0].toInt())
        assertFalse("nothing sensitive while pending", pending.containsText(qr.pass) || pending.containsText(qr.token) || pending.containsText(qr.ssid))
        assertEquals(PairingWire.Reply.Pending, PairingWire.openReply(pending, eph, senderFp))
        assertEquals(0, approvedCalls)

        gate.complete(Decision.APPROVED)
        val reply = awaitReply(b, "dev1") { it[0].toInt() == PairingWire.ST_APPROVED }
        assertEquals(1, approvedCalls)
        for (secret in listOf(qr.pass, qr.token, qr.ssid, qr.ip)) assertFalse("'$secret' visible on the air", reply.containsText(secret))
        val open = PairingWire.openReply(reply, eph, senderFp) as PairingWire.Reply.Approved
        val got = PairingWire.qrOf(open.plaintext)!!
        assertEquals(qr.pass, got.pass); assertEquals(qr.token, got.token); assertEquals(qr.ssid, got.ssid)
    }

    @Test fun anotherDeviceCannotOpenAnApprovedReply() = runBlocking {
        val b = broker({ _, _ -> Decision.APPROVED })
        val honest = PairingCrypto.newEphemeral()
        b.onRequest("dev1", PairingWire.encodeRequest("Sender", senderFp, honest))
        val reply = awaitReply(b, "dev1") { it[0].toInt() == PairingWire.ST_APPROVED }
        val eve = PairingCrypto.newEphemeral()
        assertEquals(PairingWire.Reply.Bad, PairingWire.openReply(reply, eve, senderFp))
        assertEquals("a wrong claimed fingerprint fails too", PairingWire.Reply.Bad, PairingWire.openReply(reply, honest, "SOMEONE_ELSE_0123456789"))
    }

    @Test fun declinedRequesterLearnsItAndGetsNothing() = runBlocking {
        val b = broker({ _, _ -> Decision.DECLINED })
        val eph = PairingCrypto.newEphemeral()
        b.onRequest("dev1", PairingWire.encodeRequest("Sender", senderFp, eph))
        val reply = awaitReply(b, "dev1") { it[0].toInt() != PairingWire.ST_PENDING }
        assertEquals(PairingWire.Reply.Declined, PairingWire.openReply(reply, eph, senderFp))
        assertEquals(1, reply.size)
    }

    @Test fun silenceFromTheUserTimesOutInsteadOfWaitingForever() = runBlocking {
        val b = broker({ _, _ -> awaitCancellation() }, timeoutMs = 200)
        val eph = PairingCrypto.newEphemeral()
        b.onRequest("dev1", PairingWire.encodeRequest("Sender", senderFp, eph))
        val reply = awaitReply(b, "dev1") { it[0].toInt() != PairingWire.ST_PENDING }
        assertEquals(PairingWire.Reply.Timeout, PairingWire.openReply(reply, eph, senderFp))
    }

    @Test fun whenTheRequesterDisconnectsThePromptIsCancelled() = runBlocking {
        val started = CompletableDeferred<Boolean>()
        val cancelled = CompletableDeferred<Boolean>()
        val b = broker({ _, _ -> started.complete(true); try { awaitCancellation() } finally { cancelled.complete(true) } })
        b.onRequest("dev1", PairingWire.encodeRequest("Sender", senderFp, PairingCrypto.newEphemeral()))
        withTimeout(3000) { started.await() } // the prompt is on screen
        b.forget("dev1")
        assertTrue("the user must stop being asked about a device that left", withTimeout(3000) { cancelled.await() })
        assertEquals(PairingWire.ST_BAD, b.reply("dev1")[0].toInt())
    }

    @Test fun floodingIsBoundedAndGarbageIsRefused() {
        val b = broker({ _, _ -> awaitCancellation() })
        val ok = (1..10).count { b.onRequest("dev$it", PairingWire.encodeRequest("S$it", senderFp, PairingCrypto.newEphemeral())) }
        assertEquals(4, ok)
        assertFalse(b.onRequest("devX", "garbage".toByteArray()))
        assertTrue("a known device may retry", b.onRequest("dev1", PairingWire.encodeRequest("S1", senderFp, PairingCrypto.newEphemeral())))
        b.clear()
    }

    // ---------- nothing sensitive in anything that is broadcast ----------

    @Test fun awareAdvertisementHasNoTokenPasswordOrNetworkName() {
        val advert = Aware.Advert.of(qr).bytes()
        for (secret in listOf(qr.token, qr.pass, qr.ssid, qr.ip)) assertFalse("'$secret' is in the Aware publish", advert.containsText(secret))
        assertTrue("the fingerprint is the public pin, the one thing it may carry", advert.containsText(qr.fp))
        assertTrue(advert.size < 255)
        val parsed = Aware.Advert.parse(advert)!!
        assertEquals(qr.port, parsed.p)
    }

    @Test fun awareGrantIsFreshPerApprovalAndShapedForThePassphraseLimit() {
        val g1 = Aware.Grant.fresh("111111"); val g2 = Aware.Grant.fresh("111111")
        assertTrue(g1.secret != g2.secret)
        assertTrue(Aware.psk(g1.secret).length in 8..63)
        val back = Aware.Grant.parse(g1.bytes())!!
        assertEquals(g1.secret, back.secret); assertEquals("111111", back.token)
        assertNull(Aware.Grant.parse("no-colon".toByteArray()))
    }

    @Test fun bleCapabilityByteIsTheOnlyNonNameDataAndNamesNoSecret() {
        val caps = PairingWire.capabilities(ready = true, encrypted = true)
        assertEquals((PairingWire.CAP_READY or PairingWire.CAP_ENCRYPTED).toByte(), caps)
        assertEquals(1, byteArrayOf(caps).size)
        assertEquals(0, PairingWire.capabilities(ready = false, encrypted = false).toInt())
    }

    @Test fun approvedAwareReplyDoesNotExposeTheTokenOnTheAir() {
        val eph = PairingCrypto.newEphemeral()
        val req = PairingWire.decodeRequest(PairingWire.encodeRequest("S", senderFp, eph, 24))!!
        val grant = Aware.Grant.fresh("493817")
        val reply = PairingWire.approved(PairingCrypto.newEphemeral(), req, grant.bytes())
        assertTrue("fits one Aware message, was ${reply.size}", reply.size <= 255)
        assertFalse(reply.containsText("493817")); assertFalse(reply.containsText(grant.secret))
        val open = PairingWire.openReply(reply, eph, senderFp) as PairingWire.Reply.Approved
        assertEquals("493817", Aware.Grant.parse(open.plaintext)!!.token)
    }
}
