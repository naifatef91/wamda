package app.barq.crypto

import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Transport security (spec section 08).
 *
 * Every connection is TLS 1.3. The receiver presents a self-signed EC P-256
 * certificate generated in software when the process starts (never written to
 * disk, never in the hardware keystore, so it behaves the same on every OEM).
 * Its SHA-256 fingerprint travels inside the QR / BLE payload and the sender
 * pins it. Bulk encryption is AES-GCM, hardware-accelerated on ARMv8.
 */
object Tls {
    private val PROTOCOLS = arrayOf("TLSv1.3")

    class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
        val fingerprint: String = fingerprintOf(certificate)
    }

    @Volatile private var cached: Identity? = null

    @Synchronized
    fun identity(): Identity {
        cached?.let { if (it.certificate.notAfter.after(Date())) return it }
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Wamda")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger(63, SecureRandom()),
            Date(now - 24L * 3600 * 1000), Date(now + 365L * 24 * 3600 * 1000),
            name, kp.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        return Identity(kp.private, cert).also { cached = it }
    }

    /** Unbound TLS server socket backed by this process's identity. Bind it after setting buffer sizes. */
    fun serverSocket(): SSLServerSocket {
        val id = identity()
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(id.certificate)
            override fun getPrivateKey(alias: String?) = id.privateKey
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(km), null, null) }
        val server = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.enabledProtocols = PROTOCOLS
        server.needClientAuth = false
        server.useClientMode = false
        return server
    }

    /** Layers TLS over an already-connected socket and pins the receiver's certificate fingerprint. */
    fun wrapClient(plain: Socket, host: String, port: Int, expectedFingerprint: String): SSLSocket {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("client auth not used")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
                val fp = fingerprintOf(leaf)
                if (!MessageDigest.isEqual(fp.toByteArray(), expectedFingerprint.toByteArray())) {
                    throw CertificateException("receiver certificate does not match the QR code")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val ssl = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.enabledProtocols = PROTOCOLS
        ssl.useClientMode = true
        ssl.startHandshake()
        return ssl
    }

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private const val ALIAS = "wamda"
}
