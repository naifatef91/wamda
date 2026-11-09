package app.barq.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class SelfSignedCertTest {
    @Test fun certificateIsSelfSignedEcP256WithTheExpectedFields() {
        val r = SelfSignedCert.generate()
        val cert = r.certificate
        cert.verify(cert.publicKey) // signature checks out against its own key
        assertEquals("CN=Wamda", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(3, cert.version)
        assertEquals("EC", cert.publicKey.algorithm)
        assertTrue(cert.serialNumber.signum() > 0)
        val days = TimeUnit.MILLISECONDS.toDays(cert.notAfter.time - cert.notBefore.time)
        assertTrue("validity was $days days", days in 365..367)
        assertTrue(cert.notBefore.time <= System.currentTimeMillis())
    }

    @Test fun everyIdentityIsDistinct() {
        val a = SelfSignedCert.generate().certificate.encoded
        val b = SelfSignedCert.generate().certificate.encoded
        assertTrue(!a.contentEquals(b))
        assertNotEquals(SelfSignedCert.generate().certificate.serialNumber, SelfSignedCert.generate().certificate.serialNumber)
    }

    /** The real proof: a TLS 1.3 handshake with the certificate pinned by SHA-256, exactly as the app does. */
    @Test fun tlsHandshakeSucceedsWithPinnedFingerprintAndFailsOnMismatch() {
        val id = SelfSignedCert.generate()
        val pin = sha256(id.certificate)

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("k", id.keyPair.private, charArrayOf(), arrayOf(id.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, charArrayOf()) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = serverCtx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.soTimeout = 5000
        val echoed = arrayOfNulls<Int>(1)
        val acceptor = thread {
            repeat(2) {
                runCatching {
                    (server.accept() as SSLSocket).use { s ->
                        s.soTimeout = 5000
                        s.startHandshake()
                        val b = s.inputStream.read()
                        echoed[0] = b
                        s.outputStream.write(b); s.outputStream.flush()
                    }
                }
            }
        }

        fun client(expected: ByteArray): SSLSocket {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (!MessageDigest.isEqual(sha256(chain.first()), expected)) throw CertificateException("pin mismatch")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return (ctx.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3"); soTimeout = 5000
            }
        }

        client(pin).use { c ->
            c.startHandshake()
            assertEquals("TLSv1.3", c.session.protocol)
            c.outputStream.write(42); c.outputStream.flush()
            assertEquals(42, c.inputStream.read())
        }

        val wrong = pin.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        var rejected = false
        try { client(wrong).use { it.startHandshake() } } catch (e: javax.net.ssl.SSLException) { rejected = true }
        assertTrue("a different fingerprint must be rejected", rejected)

        acceptor.join(8000)
        server.close()
    }

    private fun sha256(c: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(c.encoded)
}
