package app.barq.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Receiver side: creates a Wi-Fi Direct group with this phone as group owner,
 * forcing the 5GHz band when the hardware allows it (spec section 06).
 */
class WifiDirectHost(context: Context) {
    data class GroupInfo(val ssid: String, val passphrase: String, val ownerIp: String, val frequencyMhz: Int) {
        val band: String get() = when {
            frequencyMhz >= 5925 -> "6GHz"
            frequencyMhz >= 4900 -> "5GHz"
            frequencyMhz > 0 -> "2.4GHz"
            else -> "?"
        }
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, Looper.getMainLooper(), null)

    @SuppressLint("MissingPermission")
    enum class Band { PREFER_5GHZ, AUTO, GHZ2 }

    /**
     * [band]: PREFER_5GHZ tries 5GHz then falls back; GHZ2 forces 2.4GHz (every laptop can see it).
     * [suffix] (2 alphanumerics) and [passphrase] (8–63 ASCII chars) make the network stable
     * across sessions so a computer can remember it; null = random each time.
     */
    suspend fun start(band: Band = Band.PREFER_5GHZ, suffix: String? = null, passphrase: String? = null): GroupInfo {
        removeGroup()
        delay(300)
        val sfx = suffix?.takeIf { it.length == 2 && it.all(Char::isLetterOrDigit) } ?: randomAlnum(2)
        val pass = passphrase?.takeIf { it.length in 8..63 && it.all { ch -> ch.code in 32..126 } } ?: randomAlnum(10)
        val ok = when (band) {
            Band.GHZ2 -> createGroup(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, sfx, pass) || run { delay(500); createGroup(WifiP2pConfig.GROUP_OWNER_BAND_AUTO, sfx, pass) }
            Band.AUTO -> createGroup(WifiP2pConfig.GROUP_OWNER_BAND_AUTO, sfx, pass)
            Band.PREFER_5GHZ -> createGroup(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, sfx, pass) || run { delay(500); createGroup(WifiP2pConfig.GROUP_OWNER_BAND_AUTO, sfx, pass) }
        }
        if (!ok) throw IOException("Could not create Wi-Fi Direct group")

        repeat(40) {
            val g = requestGroup()
            if (g != null && g.isGroupOwner && !g.passphrase.isNullOrEmpty()) {
                return GroupInfo(g.networkName, g.passphrase, ownerIp(g), g.frequency)
            }
            delay(250)
        }
        throw IOException("Wi-Fi Direct group did not come up")
    }

    suspend fun stop() {
        removeGroup()
    }

    @SuppressLint("MissingPermission")
    private suspend fun createGroup(band: Int, suffix: String, passphrase: String): Boolean = suspendCancellableCoroutine { cont ->
        val config = WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-$suffix-Wamda")
            .setPassphrase(passphrase)
            .setGroupOperatingBand(band)
            .build()
        manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
        })
    }

    private suspend fun removeGroup(): Boolean = suspendCancellableCoroutine { cont ->
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroup(): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        manager.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
    }

    private fun ownerIp(group: WifiP2pGroup): String {
        val iface = group.`interface`
        if (!iface.isNullOrEmpty()) {
            runCatching {
                NetworkInterface.getByName(iface)?.inetAddresses?.toList()
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
            }.getOrNull()?.let { return it }
        }
        return "192.168.49.1"
    }

    private fun randomAlnum(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..n).map { chars.random() }.joinToString("")
    }
}
