package app.barq.crypto

import android.util.Base64
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
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
        val made = SelfSignedCert.generate()
        return Identity(made.keyPair.private, made.certificate).also { cached = it }
    }

    /** Key manager that offers this process's certificate in both the server and the client role. */
    private fun keyManager(id: Identity) = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(id.certificate)
        override fun getPrivateKey(alias: String?) = id.privateKey
    }

    /**
     * Unbound TLS server socket backed by this process's identity. Bind it after setting buffer sizes.
     * A client certificate is requested but not required: a peer that presents one is identified by
     * its fingerprint, which is what "trusted device" auto-accept is bound to; a peer without one
     * always gets the accept dialog.
     */
    fun serverSocket(): SSLServerSocket {
        val id = identity()
        val anyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("empty chain")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("server role only")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager(id)), arrayOf(anyClient), null) }
        val server = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.enabledProtocols = PROTOCOLS
        server.wantClientAuth = true
        server.needClientAuth = false
        server.useClientMode = false
        return server
    }

    /** Fingerprint of the certificate the other end presented on [socket]; null for plain sockets or no cert. */
    fun peerFingerprint(socket: Socket): String? {
        val ssl = socket as? SSLSocket ?: return null
        return runCatching { (ssl.session.peerCertificates.firstOrNull() as? X509Certificate)?.let { fingerprintOf(it) } }.getOrNull()
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
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager(identity())), arrayOf(trust), null) }
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
