package app.barq.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the per-process TLS identity with nothing but platform classes: an EC P-256 key pair
 * and a self-signed X.509 v3 certificate (ecdsa-with-SHA256), hand-encoded in DER. This replaced
 * BouncyCastle, which was a large share of the APK for a single call.
 */
object SelfSignedCert {
    class Result(val keyPair: KeyPair, val certificate: X509Certificate)

    fun generate(
        commonName: String = "Wamda",
        now: Long = System.currentTimeMillis(),
        validDays: Long = 365,
        random: SecureRandom = SecureRandom(),
    ): Result {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

        val sigAlg = seq(oid(OID_ECDSA_SHA256)) // no parameters, as RFC 5758 requires
        val name = seq(set(seq(oid(OID_COMMON_NAME), tlv(TAG_UTF8, commonName.toByteArray(Charsets.UTF_8)))))
        val serial = BigInteger(63, random).add(BigInteger.ONE) // positive, never zero
        val validity = seq(
            utcTime(now - 24L * 3600 * 1000),
            utcTime(now + validDays * 24L * 3600 * 1000),
        )
        val tbs = seq(
            tlv(TAG_CONTEXT_0, tlv(TAG_INTEGER, byteArrayOf(2))), // version v3
            tlv(TAG_INTEGER, serial.toByteArray()),
            sigAlg,
            name, // issuer
            validity,
            name, // subject (self-signed)
            kp.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private, random)
            update(tbs)
            sign()
        }
        val der = seq(tbs, sigAlg, tlv(TAG_BIT_STRING, byteArrayOf(0) + signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        return Result(kp, cert)
    }

    // ---- minimal DER encoder ----

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OID = 0x06
    private const val TAG_UTF8 = 0x0C
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_CONTEXT_0 = 0xA0

    // 1.2.840.10045.4.3.2 (ecdsa-with-SHA256) and 2.5.4.3 (commonName)
    private val OID_ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    private fun oid(encoded: ByteArray) = tlv(TAG_OID, encoded)
    private fun seq(vararg parts: ByteArray) = tlv(TAG_SEQUENCE, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(TAG_SET, concat(parts))

    private fun utcTime(millis: Long): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(TAG_UTC_TIME, fmt.format(Date(millis)).toByteArray(Charsets.US_ASCII))
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> error("DER value too large: $n")
        }
        out.write(content)
        return out.toByteArray()
    }
}
