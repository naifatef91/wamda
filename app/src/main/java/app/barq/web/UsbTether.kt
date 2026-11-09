package app.barq.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Phone to computer over a USB-C cable with nothing installed on the computer: when the user turns on
 * USB tethering the phone exposes a network interface (rndis0, usb0 or ncm0) and the computer gets an
 * address on it from its own built-in driver (RNDIS, or NCM on Windows 11 24H2 where RNDIS is gone).
 * The same PC-mode page then works at the phone's address on that link.
 *
 * This object works out where the user is on the way there, so the screen can say what to do next and
 * show the link and its QR code the moment the interface appears.
 */
object UsbTether {
    /** Interface name prefixes of USB tethering. PC mode answers on these (and the Wi-Fi Direct group) only. */
    val PREFIXES = listOf("rndis", "usb", "ncm")

    enum class Stage {
        /** No USB data connection seen. */
        NO_CABLE,
        /** Cable connected to a computer, tethering not on yet. */
        CABLE_ONLY,
        /** The tethering function is active but the phone has no address on it yet. */
        STARTING,
        /** Tethering is up and has an address: show the link. */
        READY,
    }

    data class Status(
        val stage: Stage = Stage.NO_CABLE,
        val ip: String? = null,
        val iface: String? = null,
        /** The cable is in file-transfer (MTP/PTP) mode, which is what makes the tethering switch selectable on many phones. */
        val fileTransfer: Boolean = false,
    ) {
        val url: String? get() = ip?.let { "http://$it:$PORT" }
        /** "RNDIS", "NCM" or "USB": the driver family the computer will use. */
        val driver: String? get() = iface?.let { driverOf(it) }
    }

    private const val PORT = 8080

    class Candidate(val name: String, val ip: String)

    fun isTetherInterface(name: String): Boolean = PREFIXES.any { name.startsWith(it) }

    fun driverOf(iface: String): String = when {
        iface.startsWith("rndis") -> "RNDIS"
        iface.startsWith("ncm") -> "NCM"
        else -> "USB"
    }

    /** When several tethering interfaces are up, prefer RNDIS, then NCM, then the generic one. */
    fun pick(candidates: List<Candidate>): Candidate? =
        candidates.filter { isTetherInterface(it.name) }.minByOrNull { c -> PREFERENCE.indexOfFirst { c.name.startsWith(it) } }

    private val PREFERENCE = listOf("rndis", "ncm", "usb")

    fun stageOf(ip: String?, tetherFunction: Boolean, cableConnected: Boolean): Stage = when {
        ip != null -> Stage.READY
        tetherFunction -> Stage.STARTING
        cableConnected -> Stage.CABLE_ONLY
        else -> Stage.NO_CABLE
    }

    // ---- Android glue ----

    private fun candidates(): List<Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && isTetherInterface(it.name) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { Candidate(nif.name, it.hostAddress ?: "") } }
            .filter { it.ip.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** Reads the sticky USB state broadcast and the network interfaces; cheap enough to call every couple of seconds. */
    fun status(context: Context): Status {
        val found = pick(candidates())
        val usb: Intent? = runCatching {
            // ACTION_USB_STATE is a protected sticky broadcast: registering with a null receiver just returns the last one.
            context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        val connected = usb?.getBooleanExtra("connected", false) ?: false
        val tetherFunction = usb?.let { it.getBooleanExtra("rndis", false) || it.getBooleanExtra("ncm", false) } ?: false
        val files = usb?.let { it.getBooleanExtra("mtp", false) || it.getBooleanExtra("ptp", false) } ?: false
        return Status(stageOf(found?.ip, tetherFunction, connected), found?.ip, found?.name, files)
    }

    /** Opens the system tethering page (names differ between makers), falling back to wireless settings. */
    fun openTetherSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}
