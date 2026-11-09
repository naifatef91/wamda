package app.barq.net

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the pairing details (network name, password, token) for the one device the user approved.
 *
 * Neither BLE advertising nor Wi-Fi Aware messages are encrypted on the air, so the receiver never
 * sends secrets in clear: the requester includes a throw-away P-256 public key, the receiver answers
 * with its own, and the payload travels under AES-256-GCM keyed from the ECDH secret (HKDF-SHA256).
 * Someone who only listens sees ciphertext; someone who asks gets nothing unless the receiver's
 * user taps Accept.
 */
object PairingCrypto {
    const val POINT_BYTES = 64 // X || Y, 32 bytes each
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val INFO = "wamda-pairing-v1".toByteArray(Charsets.US_ASCII)

    class Ephemeral internal constructor(internal val keyPair: KeyPair) {
        val raw: ByteArray = rawOf(keyPair.public as ECPublicKey)
    }

    fun newEphemeral(random: SecureRandom = SecureRandom()): Ephemeral {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        return Ephemeral(gen.generateKeyPair())
    }

    /** Returns IV || ciphertext || tag. [aad] binds the message to the request it answers. */
    fun seal(own: Ephemeral, peerRaw: ByteArray, aad: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val key = deriveKey(own, peerRaw)
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }
        return iv + cipher.doFinal(plaintext)
    }

    /** Null when the key, the context or any byte of the message is wrong. */
    fun open(own: Ephemeral, peerRaw: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        require(sealed.size > IV_BYTES + TAG_BITS / 8)
        val key = deriveKey(own, peerRaw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
            updateAAD(aad)
        }
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }.getOrNull()

    /** True when [raw] is a well-formed point on P-256 (rejects invalid-curve inputs). */
    fun isValidPoint(raw: ByteArray): Boolean = raw.size == POINT_BYTES && runCatching { pointFrom(raw, params()) }.isSuccess

    // ---- internals ----

    private fun deriveKey(own: Ephemeral, peerRaw: ByteArray): ByteArray {
        val peer = publicFromRaw(peerRaw, (own.keyPair.public as ECPublicKey).params)
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(own.keyPair.private); doPhase(peer, true); generateSecret()
        }
        // Both sides must derive the same salt, whichever of them is "own".
        val (a, b) = if (compare(own.raw, peerRaw) <= 0) own.raw to peerRaw else peerRaw to own.raw
        val salt = MessageDigest.getInstance("SHA-256").digest(a + b)
        val prk = hmac(salt, secret)
        return hmac(prk, INFO + byteArrayOf(1)) // HKDF-Expand, first (and only) 32-byte block
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun compare(x: ByteArray, y: ByteArray): Int {
        for (i in x.indices) { val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF); if (d != 0) return d }
        return 0
    }

    private fun rawOf(pub: ECPublicKey): ByteArray = fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // strip the sign byte
            else -> ByteArray(32 - b.size) + b
        }
    }

    private val P256: ECParameterSpec by lazy { (newEphemeral().keyPair.public as ECPublicKey).params }

    private fun params(): ECParameterSpec = P256

    private fun pointFrom(raw: ByteArray, spec: ECParameterSpec): ECPoint {
        require(raw.size == POINT_BYTES)
        val x = BigInteger(1, raw.copyOfRange(0, 32))
        val y = BigInteger(1, raw.copyOfRange(32, 64))
        val p = (spec.curve.field as ECFieldFp).p
        require(x < p && y < p)
        // y^2 = x^3 + ax + b (mod p)
        val lhs = y.modPow(BigInteger.TWO, p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p)
        require(lhs == rhs) { "point is not on the curve" }
        return ECPoint(x, y)
    }

    private fun publicFromRaw(raw: ByteArray, spec: ECParameterSpec): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pointFrom(raw, spec), spec))
}
