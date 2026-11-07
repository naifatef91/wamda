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
import app.barq.engine.QrPayload
import app.barq.engine.wireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.Inet6Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wi-Fi Aware (NAN) transport (FR-14). Opportunistic: used only when both phones
 * support it and the radio allows it next to the Wi-Fi Direct group; otherwise
 * nothing changes. The receiver publishes a "wamda" service whose payload carries
 * what the QR code carries (name, token, TLS fingerprint); the sender subscribes,
 * the user taps a peer, and both sides bring up an Aware data path. The sender
 * then dials the receiver's existing TLS server over that link, so the frame
 * protocol, resume and dedupe are untouched. Internet stays connected meanwhile.
 */
object Aware {
    const val SERVICE = "wamda"
    private const val JOIN = "join"

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            (context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager)?.isAvailable == true

    /** What the publisher advertises; must stay under ~255 bytes. */
    @Serializable
    data class Advert(val n: String, val t: String, val fp: String, val p: Int, val e: Boolean = true) {
        fun bytes() = wireJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)
        companion object {
            fun parse(b: ByteArray?): Advert? = b?.let { runCatching { wireJson.decodeFromString(serializer(), String(it, Charsets.UTF_8)) }.getOrNull() }
            fun of(qr: QrPayload) = Advert(qr.name.take(40), qr.token, qr.fp, qr.port, qr.enc)
        }
    }

    /** Both sides derive the data-path PSK from the pairing secret; TLS pinning is the real guard. */
    fun psk(token: String, fp: String) = "wamda-$token-" + fp.take(16)

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

/** Receiver side: publish, and open a data path to every sender that asks. */
@SuppressLint("MissingPermission")
class AwareHost(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private var advert: Aware.Advert? = null

    /** Returns false when Aware cannot run right now (unsupported, off, or blocked by the P2P group). */
    suspend fun start(qr: QrPayload): Boolean {
        if (!Aware.supported(context)) return false
        val s = runCatching { Aware.attach(context) }.getOrNull() ?: return false
        session = s
        val adv = Aware.Advert.of(qr).also { advert = it }
        val config = PublishConfig.Builder().setServiceName(Aware.SERVICE).setServiceSpecificInfo(adv.bytes()).build()
        s.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(p: PublishDiscoverySession) { publish = p }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val p = publish ?: return
                // A sender asked to join: accept the data path as responder on our server port.
                val spec = WifiAwareNetworkSpecifier.Builder(p, peerHandle)
                    .setPskPassphrase(Aware.psk(adv.t, adv.fp))
                    .setPort(adv.p)
                    .build()
                val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
                val cb = object : ConnectivityManager.NetworkCallback() {}
                synchronized(callbacks) { callbacks += cb }
                runCatching { cm.requestNetwork(req, cb) }
            }
        }, Aware.handlerFor())
        return true
    }

    fun stop() {
        synchronized(callbacks) { callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }; callbacks.clear() }
        runCatching { publish?.close() }; publish = null
        runCatching { session?.close() }; session = null
    }
}

class AwarePeer(val id: Int, val name: String, val advert: Aware.Advert, internal val handle: PeerHandle, val lastSeen: Long)

/** Sender side: subscribe, list publishers, and bring up a data path to the one the user taps. */
@SuppressLint("MissingPermission")
class AwareScanner(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var session: WifiAwareSession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
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
                    val existing = peers.values.firstOrNull { it.handle == peerHandle || (it.advert.t == adv.t && it.advert.fp == adv.fp) }
                    val id = existing?.id ?: nextId++
                    peers[id] = AwarePeer(id, adv.n, adv, peerHandle, System.currentTimeMillis())
                    _devices.value = peers.values.toList()
                }
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

    /** Asks the publisher to open a data path, then returns the network and the receiver's address on it. */
    suspend fun connect(peer: AwarePeer, timeoutMs: Long = 30_000): Pair<Network, Inet6Address> = withTimeout(timeoutMs) {
        val sub = subscribe ?: throw IOException("Aware not subscribed")
        sub.sendMessage(peer.handle, peer.id, "join".toByteArray())
        suspendCancellableCoroutine { cont ->
            val spec = WifiAwareNetworkSpecifier.Builder(sub, peer.handle)
                .setPskPassphrase(Aware.psk(peer.advert.t, peer.advert.fp))
                .build()
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val addr = info.peerIpv6Addr ?: return
                    if (cont.isActive) cont.resume(network to addr)
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
