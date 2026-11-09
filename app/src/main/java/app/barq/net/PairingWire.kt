package app.barq.net

import app.barq.engine.NameSanitizer
import app.barq.engine.QrPayload
import app.barq.engine.wireJson
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Base64

/** The receiver's user said no. */
class PairingDeclined : IOException("the other device declined the connection")

/** Nobody answered the approval prompt in time. */
class PairingTimeout : IOException("the other device did not answer in time")

/**
 * Wire format of the two-step pairing, identical over BLE (GATT) and Wi-Fi Aware (messages).
 *
 * Request (requester -> receiver): who I am and a one-time key. It carries no secret.
 * Reply (receiver -> requester): one status byte, then for "approved" the receiver's one-time
 * key and the pairing payload sealed under the key both sides derive (see [PairingCrypto]).
 */
object PairingWire {
    const val VERSION = 1
    const val ST_PENDING = 0
    const val ST_APPROVED = 1
    const val ST_DECLINED = 2
    const val ST_TIMEOUT = 3
    const val ST_BAD = 4

    /** Advertised capability bits (one byte of BLE service data). */
    const val CAP_READY = 1
    const val CAP_ENCRYPTED = 2

    private const val MAX_FP = 64
    private const val MAX_LABEL = 40

    fun capabilities(ready: Boolean, encrypted: Boolean): Byte =
        ((if (ready) CAP_READY else 0) or (if (encrypted) CAP_ENCRYPTED else 0)).toByte()

    @Serializable
    private data class RequestJson(val v: Int, val n: String, val f: String, val k: String)

    class Request(val name: String, val fp: String, val key: ByteArray)

    /** [maxNameBytes] lets Aware (255-byte messages) use a tighter bound than BLE. */
    fun encodeRequest(name: String, fp: String, eph: PairingCrypto.Ephemeral, maxNameBytes: Int = 60): ByteArray {
        val label = clipUtf8(NameSanitizer.displayLabel(name, MAX_LABEL), maxNameBytes)
        val json = RequestJson(VERSION, label, fp, Base64.getEncoder().withoutPadding().encodeToString(eph.raw))
        return wireJson.encodeToString(RequestJson.serializer(), json).toByteArray(Charsets.UTF_8)
    }

    fun decodeRequest(bytes: ByteArray): Request? = runCatching {
        require(bytes.size in 1..400)
        val j = wireJson.decodeFromString(RequestJson.serializer(), String(bytes, Charsets.UTF_8))
        require(j.v == VERSION)
        require(j.f.length in 8..MAX_FP && j.f.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val key = Base64.getDecoder().decode(j.k)
        require(PairingCrypto.isValidPoint(key))
        Request(NameSanitizer.displayLabel(j.n, MAX_LABEL).ifBlank { "?" }, j.f, key)
    }.getOrNull()

    fun status(code: Int): ByteArray = byteArrayOf(code.toByte())

    /** Approved reply carrying [plaintext] sealed for the requester only. */
    fun approved(receiver: PairingCrypto.Ephemeral, request: Request, plaintext: ByteArray): ByteArray =
        byteArrayOf(ST_APPROVED.toByte()) + receiver.raw + PairingCrypto.seal(receiver, request.key, aad(request.fp), plaintext)

    sealed interface Reply {
        data object Pending : Reply
        data object Declined : Reply
        data object Timeout : Reply
        data object Bad : Reply
        class Approved(val plaintext: ByteArray) : Reply
    }

    fun openReply(bytes: ByteArray, requester: PairingCrypto.Ephemeral, requesterFp: String): Reply {
        if (bytes.isEmpty()) return Reply.Bad
        return when (bytes[0].toInt()) {
            ST_PENDING -> Reply.Pending
            ST_DECLINED -> Reply.Declined
            ST_TIMEOUT -> Reply.Timeout
            ST_APPROVED -> {
                if (bytes.size <= 1 + PairingCrypto.POINT_BYTES) return Reply.Bad
                val receiverKey = bytes.copyOfRange(1, 1 + PairingCrypto.POINT_BYTES)
                if (!PairingCrypto.isValidPoint(receiverKey)) return Reply.Bad
                val sealed = bytes.copyOfRange(1 + PairingCrypto.POINT_BYTES, bytes.size)
                PairingCrypto.open(requester, receiverKey, aad(requesterFp), sealed)?.let { Reply.Approved(it) } ?: Reply.Bad
            }
            else -> Reply.Bad
        }
    }

    /** The BLE payload is the same text the QR code carries. */
    fun qrOf(plaintext: ByteArray): QrPayload? = QrPayload.decode(String(plaintext, Charsets.UTF_8))

    private fun aad(fp: String) = "wamda-pairing|$fp".toByteArray(Charsets.UTF_8)

    private fun clipUtf8(s: String, max: Int): String {
        var out = s
        while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > max) out = out.dropLast(1)
        return out
    }
}
