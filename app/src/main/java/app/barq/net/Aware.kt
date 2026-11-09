package app.barq.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import app.barq.engine.NameSanitizer
import app.barq.engine.QrPayload
import app.barq.engine.wireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.Inet6Address
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wi-Fi Aware (NAN) transport (FR-14). Opportunistic: used only when both phones
 * support it and the radio allows it next to the Wi-Fi Direct group; otherwise
 * nothing changes. The receiver publishes a "wamda" service whose payload carries only
 * public facts (display name, TLS fingerprint, port). The sender subscribes and the user
 * taps a peer; the sender then sends a request (its name, its certificate fingerprint and a
 * one-time key). The receiver's user approves (or a trusted device is approved automatically)
 * and only then gets the pairing token plus a fresh data-path key, sealed for that sender
 * alone. Both sides bring up an Aware data path with that key, and the sender dials the
 * receiver's existing TLS server over it, so the frame protocol, resume and dedupe are
 * untouched. Internet stays connected meanwhile.
 */
object Aware {
    const val SERVICE = "wamda"

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            (context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager)?.isAvailable == true

    /** What the publisher advertises: public facts only (no token, no key); must stay under ~255 bytes. */
    @Serializable
    data class Advert(val n: String, val fp: String, val p: Int, val e: Boolean = true) {
        fun bytes() = wireJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)
        companion object {
            fun parse(b: ByteArray?): Advert? = b?.let { runCatching { wireJson.decodeFromString(serializer(), String(it, Charsets.UTF_8)) }.getOrNull() }
            fun of(qr: QrPayload) = Advert(NameSanitizer.displayLabel(qr.name, 40), qr.fp, qr.port, qr.enc)
        }
    }

    /** What an approved sender receives, sealed: the pairing token and a data-path secret unique to this approval. */
    class Grant(val token: String, val secret: String) {
        fun bytes() = "$token:$secret".toByteArray(Charsets.US_ASCII)

        companion object {
            fun parse(b: ByteArray): Grant? =
                String(b, Charsets.US_ASCII).split(':').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].length >= 16 }?.let { Grant(it[0], it[1]) }

            fun fresh(token: String, random: SecureRandom = SecureRandom()) =
                Grant(token, ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) })
        }
    }

    /** Data-path passphrase (Aware needs 8..63 characters): per approval, never broadcast. */
    fun psk(secret: String) = "wamda-$secret"

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("MissingPermission")
    suspend fun attach(context: Context): WifiAwareSession = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (manager == null || !manager.isAvailable) { cont.resumeWithException(IOException("Wi-Fi Aware unavailable")); return@suspendCancellableCoroutine }
        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) { if (cont.isActive) cont.resume(session) }
            override fun onAttachFailed() { if (cont.isActive) cont.resumeWithException(IOException("Wi-Fi Aware attach failed")) }
        }, handler)
    }
}

/** Receiver side: publish, and open a data path only for senders whose request was approved. */
@SuppressLint("MissingPermission")
class AwareHost(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private var advert: Aware.Advert? = null
    private var scope: CoroutineScope? = null
    private val pending = AtomicInteger(0)
    private var nextMessageId = 1

    /**
     * Returns false when Aware cannot run right now (unsupported, off, or blocked by the P2P group).
     * [decide] asks the user (or the trusted-device list) about one sender; nothing secret is sent before it says approved.
     */
    suspend fun start(qr: QrPayload, decide: suspend (name: String, fp: String) -> Decision): Boolean {
        if (!Aware.supported(context)) return false
        val s = runCatching { Aware.attach(context) }.getOrNull() ?: return false
        session = s
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val adv = Aware.Advert.of(qr).also { advert = it }
        val config = PublishConfig.Builder().setServiceName(Aware.SERVICE).setServiceSpecificInfo(adv.bytes()).build()
        s.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(p: PublishDiscoverySession) { publish = p }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val request = PairingWire.decodeRequest(message) ?: return
                if (pending.incrementAndGet() > 4) { pending.decrementAndGet(); return }
                sc.launch {
                    try {
                        val answer = withTimeoutOrNull(BleIds.APPROVAL_TIMEOUT_MS) { decide(request.name, request.fp) } ?: Decision.TIMEOUT
                        val p = publish ?: return@launch
                        val id = synchronized(this@AwareHost) { nextMessageId++ }
                        if (answer != Decision.APPROVED) {
                            val code = if (answer == Decision.DECLINED) PairingWire.ST_DECLINED else PairingWire.ST_TIMEOUT
                            p.sendMessage(peerHandle, id, PairingWire.status(code))
                            return@launch
                        }
                        // Approved: a fresh secret for this sender only, delivered sealed, then open the data path with it.
                        val grant = Aware.Grant.fresh(qr.token)
                        p.sendMessage(peerHandle, id, PairingWire.approved(PairingCrypto.newEphemeral(), request, grant.bytes()))
                        val spec = WifiAwareNetworkSpecifier.Builder(p, peerHandle)
                            .setPskPassphrase(Aware.psk(grant.secret))
                            .setPort(adv.p)
                            .build()
                        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
                        val cb = object : ConnectivityManager.NetworkCallback() {}
                        synchronized(callbacks) { callbacks += cb }
                        runCatching { cm.requestNetwork(req, cb) }
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            }
        }, Aware.handlerFor())
        return true
    }

    fun stop() {
        scope?.cancel(); scope = null
        synchronized(callbacks) { callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }; callbacks.clear() }
        runCatching { publish?.close() }; publish = null
        runCatching { session?.close() }; session = null
    }
}

class AwarePeer(val id: Int, val name: String, val advert: Aware.Advert, internal val handle: PeerHandle, val lastSeen: Long)

/** Sender side: subscribe, list publishers, and ask the one the user taps to let us in. */
@SuppressLint("MissingPermission")
class AwareScanner(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var session: WifiAwareSession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var pendingReply: CompletableDeferred<ByteArray>? = null
    private val peers = LinkedHashMap<Int, AwarePeer>()
    private var nextId = 1
    private val _devices = MutableStateFlow<List<AwarePeer>>(emptyList())
    val devices: StateFlow<List<AwarePeer>> = _devices.asStateFlow()

    suspend fun start(): Boolean {
        if (!Aware.supported(context)) return false
        val s = runCatching { Aware.attach(context) }.getOrNull() ?: return false
        session = s
        s.subscribe(SubscribeConfig.Builder().setServiceName(Aware.SERVICE).build(), object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(sub: SubscribeDiscoverySession) { subscribe = sub }
            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                val adv = Aware.Advert.parse(serviceSpecificInfo) ?: return
                synchronized(peers) {
                    val existing = peers.values.firstOrNull { it.handle == peerHandle || it.advert.fp == adv.fp }
                    val id = existing?.id ?: nextId++
                    peers[id] = AwarePeer(id, adv.n, adv, peerHandle, System.currentTimeMillis())
                    _devices.value = peers.values.toList()
                }
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                pendingReply?.complete(message)
            }
            override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                synchronized(peers) {
                    peers.values.firstOrNull { it.handle == peerHandle }?.let { peers.remove(it.id) }
                    _devices.value = peers.values.toList()
                }
            }
        }, Aware.handlerFor())
        return true
    }

    fun peer(id: Int): AwarePeer? = synchronized(peers) { peers[id] }

    /** What a successful [connect] hands back: the data-path network, the receiver's address on it and the pairing token. */
    class Connection(val network: Network, val address: Inet6Address, val token: String)

    /**
     * Asks the publisher to let us in. Its user approves or declines; only an approved, sealed reply
     * yields the token and the data-path secret. Throws [PairingDeclined] or [PairingTimeout] otherwise.
     * [onWaiting] fires once the request has been sent.
     */
    suspend fun connect(peer: AwarePeer, myName: String, myFingerprint: String, onWaiting: () -> Unit = {}, timeoutMs: Long = 50_000): Connection =
        try {
            withTimeout(timeoutMs) { negotiate(peer, myName, myFingerprint, onWaiting) }
        } catch (e: TimeoutCancellationException) {
            throw PairingTimeout()
        }

    private suspend fun negotiate(peer: AwarePeer, myName: String, myFingerprint: String, onWaiting: () -> Unit): Connection {
        val sub = subscribe ?: throw IOException("Aware not subscribed")
        val eph = PairingCrypto.newEphemeral()
        val reply = CompletableDeferred<ByteArray>().also { pendingReply = it }
        sub.sendMessage(peer.handle, peer.id, PairingWire.encodeRequest(myName, myFingerprint, eph, maxNameBytes = 24))
        onWaiting()
        val grant = try {
            val answer = withTimeout(BleIds.APPROVAL_TIMEOUT_MS + 5_000) { reply.await() }
            when (val r = PairingWire.openReply(answer, eph, myFingerprint)) {
                is PairingWire.Reply.Approved -> Aware.Grant.parse(r.plaintext) ?: throw IOException("bad pairing reply")
                PairingWire.Reply.Declined -> throw PairingDeclined()
                PairingWire.Reply.Timeout, PairingWire.Reply.Pending -> throw PairingTimeout()
                PairingWire.Reply.Bad -> throw IOException("bad pairing reply")
            }
        } finally {
            pendingReply = null
        }

        return suspendCancellableCoroutine { cont ->
            val spec = WifiAwareNetworkSpecifier.Builder(sub, peer.handle)
                .setPskPassphrase(Aware.psk(grant.secret))
                .build()
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val addr = info.peerIpv6Addr ?: return
                    if (cont.isActive) cont.resume(Connection(network, addr, grant.token))
                }
                override fun onUnavailable() { if (cont.isActive) cont.resumeWithException(IOException("Aware data path unavailable")) }
                override fun onLost(network: Network) { if (cont.isActive) cont.resumeWithException(IOException("Aware data path lost")) }
            }
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = cb
            cm.requestNetwork(req, cb)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    }

    fun close() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; callback = null
        runCatching { subscribe?.close() }; subscribe = null
        runCatching { session?.close() }; session = null
        synchronized(peers) { peers.clear(); _devices.value = emptyList() }
    }
}

private fun Aware.handlerFor(): Handler = Handler(Looper.getMainLooper())
