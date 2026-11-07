package app.barq.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sender side: joins the receiver's Wi-Fi Direct group as a plain Wi-Fi client,
 * through a local-only network request. No P2P negotiation needed, and the
 * same path will work for iPhone later.
 *
 * The system shows a one-time "connect to device" sheet; the user taps Connect.
 */
class WifiClient(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile var network: Network? = null
        private set
    @Volatile var ssid: String? = null
        private set
    @Volatile var lost: Boolean = false
        private set

    /** True while the joined network is still up and can carry another session. */
    val isUsable: Boolean get() = network != null && !lost

    suspend fun connect(ssid: String, passphrase: String, timeoutMs: Long = 90_000): Network = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    network = n; lost = false; this@WifiClient.ssid = ssid
                    if (cont.isActive) cont.resume(n)
                }
                override fun onLost(n: Network) { lost = true }
                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(IOException("Could not join $ssid"))
                }
            }
            callback = cb
            lost = false
            cm.requestNetwork(request, cb)
            cont.invokeOnCancellation { close() }
        }
    }

    fun close() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        lost = true
    }
}
