package app.barq.web

import android.content.ContextWrapper
import app.barq.data.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The real PC-mode HTTP server on loopback, attacked the way a web page on another origin would:
 * DNS rebinding (Host is a name, not an IP) and CSRF (cross-site upload without the custom header or from
 * another origin). Also which network interfaces the server answers on.
 */
class PcModeSecurityTest {
    private val port: Int = ServerSocket(0).use { it.localPort }
    private var server: HttpServer? = null
    private val uploaded = ArrayList<String>()

    @Before fun start() {
        // The rejections tested here happen before the server touches storage or assets, so a bare context is enough.
        val s = HttpServer(
            context = object : ContextWrapper(null) {},
            filesProvider = { emptyList<PickedFile>() },
            onUploaded = { n, _ -> uploaded += n },
        )
        s.start(port)
        server = s
    }
    @After fun stop() { server?.stop() }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun raw(request: String, bodyBytes: ByteArray = ByteArray(0)): Reply {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            sock.soTimeout = 10_000
            sock.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); write(bodyBytes); flush() }
            val all = sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val head = all.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Reply(status, headers, all.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun get(path: String, host: String?, extra: String = ""): Reply =
        raw("GET $path HTTP/1.1\r\n" + (if (host != null) "Host: $host\r\n" else "") + extra + "Connection: close\r\n\r\n")

    private val boundary = "----wamdaTestBoundary"
    private val multipart = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--$boundary--\r\n").toByteArray()

    private fun post(host: String?, headers: String): Reply = raw(
        "POST /upload HTTP/1.1\r\n" + (if (host != null) "Host: $host\r\n" else "") + headers +
            "Content-Length: ${multipart.size}\r\nConnection: close\r\n\r\n", multipart,
    )

    private val ctype = "Content-Type: multipart/form-data; boundary=$boundary\r\n"

    // ---- DNS rebinding: Host must be an IP literal ----

    @Test fun aRequestWhoseHostIsADnsNameIsRefusedOnEveryRoute() {
        for (host in listOf("evil.example", "evil.example:8080", "localhost", "localhost:8080", "127.0.0.1.evil.example", "192.168.42.129.nip.io", "1.2.3.4@evil.example", "wamda.local")) {
            for (path in listOf("/api/status", "/api/files", "/d/1", "/index.html", "/pc/app.js")) {
                val r = get(path, host)
                assertEquals("Host: $host on $path must be refused", 400, r.status)
                assertEquals("Bad host", r.body)
            }
        }
    }

    @Test fun aRequestWithNoHostHeaderIsRefused() {
        assertEquals(400, get("/api/status", host = null).status)
    }

    @Test fun ipLiteralHostsAreServed() {
        for (host in listOf("127.0.0.1:$port", "192.168.42.129:8080", "192.168.49.1", "[fe80::1]:8080", "[::1]")) {
            val r = get("/api/status", host)
            assertEquals("Host: $host should be accepted", 200, r.status)
            assertTrue(r.body.contains("\"phone\""))
        }
    }

    @Test fun hostParsingHandlesPortsBracketsAndGarbage() {
        assertEquals("192.168.1.2", HttpServer.hostOf("192.168.1.2:8080"))
        assertEquals("[fe80::1]", HttpServer.hostOf("[fe80::1]:8080"))
        assertNull(HttpServer.hostOf(null)); assertNull(HttpServer.hostOf("  "))
        assertEquals("evil.example", HttpServer.hostOf("evil.example:80"))
    }

    // ---- CSRF: the upload endpoint ----

    @Test fun uploadWithoutTheCustomHeaderIsRefusedEvenWithAValidHost() {
        // What a cross-site <form method=POST enctype=multipart/form-data> sends: no custom header possible.
        val r = post("127.0.0.1:$port", ctype)
        assertEquals(400, r.status)
        assertEquals("Missing upload header", r.body)
        assertTrue(uploaded.isEmpty())
    }

    @Test fun theCustomHeaderMustHaveTheExpectedValue() {
        for (v in listOf("0", "true", "", "yes")) {
            assertEquals("X-Wamda-Upload: '$v'", 400, post("127.0.0.1:$port", ctype + "X-Wamda-Upload: $v\r\n").status)
        }
    }

    @Test fun uploadFromAnotherOriginIsRefusedEvenWithTheHeader() {
        for (origin in listOf("http://evil.example", "https://evil.example:8080", "http://192.168.42.130:8080", "http://127.0.0.2:$port")) {
            val r = post("127.0.0.1:$port", ctype + "X-Wamda-Upload: 1\r\nOrigin: $origin\r\n")
            assertEquals("Origin $origin", 400, r.status)
            assertEquals("Cross-origin upload refused", r.body)
        }
        assertTrue(uploaded.isEmpty())
    }

    @Test fun uploadFromTheSameOriginPassesTheGate() {
        // The page itself: same host, custom header. It passes the CSRF checks (this bare test context has no
        // MediaStore, so what follows fails with a server error, which is exactly what shows the gate was passed).
        val r = post("127.0.0.1:$port", ctype + "X-Wamda-Upload: 1\r\nOrigin: http://127.0.0.1:$port\r\n")
        assertNotEquals("same-origin upload must get past the CSRF gate", 400, r.status)
        assertNotEquals(403, r.status)
    }

    @Test fun uploadWithTheHeaderButNotMultipartIsRefused() {
        val r = post("127.0.0.1:$port", "Content-Type: text/plain\r\nX-Wamda-Upload: 1\r\n")
        assertEquals(400, r.status)
        assertEquals("Expected multipart/form-data", r.body)
    }

    @Test fun uploadToADnsNameHostIsRefusedBeforeTheHeaderIsEvenLookedAt() {
        val r = post("evil.example", ctype + "X-Wamda-Upload: 1\r\n")
        assertEquals(400, r.status); assertEquals("Bad host", r.body)
    }

    @Test fun preflightGrantsNoCrossOriginAccess() {
        val r = raw("OPTIONS /upload HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: http://evil.example\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: x-wamda-upload\r\nConnection: close\r\n\r\n")
        assertEquals(204, r.status)
        assertFalse("no CORS grant may be given to another origin", r.headers.keys.any { it.startsWith("access-control-allow") })
    }

    @Test fun responsesCarryTheAntiFramingAndNoSniffHeaders() {
        val r = get("/api/status", "127.0.0.1:$port")
        assertEquals("DENY", r.headers["x-frame-options"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals("no-referrer", r.headers["referrer-policy"])
        assertTrue(r.headers["content-security-policy"].orEmpty().contains("frame-ancestors 'none'"))
    }

    // ---- which interfaces the server answers on ----

    @Test fun theServerAnswersOnlyOnTheWifiDirectGroupAndUsbTethering() {
        for (n in listOf("p2p-wlan0-0", "p2p0", "rndis0", "usb0", "ncm0")) assertTrue("$n must be served", HttpServer.interfaceAllowed(n))
        for (n in listOf("wlan0", "wlan1", "eth0", "rmnet_data0", "ap0", "swlan0", "tun0", "lo", "dummy0")) assertFalse("$n must NOT be served", HttpServer.interfaceAllowed(n))
    }

    @Test fun aLoopbackConnectionStillWorksSoTheAboveTestsAreMeaningful() {
        assertEquals(200, get("/api/status", "127.0.0.1:$port").status)
    }
}
