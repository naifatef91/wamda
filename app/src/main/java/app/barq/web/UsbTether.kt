package app.barq.web

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Phone ↔ computer over a USB-C cable without software on the computer: when
 * the user turns on USB tethering, the phone exposes a network interface
 * (rndis0 / usb0 / ncm0) and the computer gets an address on it through the
 * OS's built-in driver. The PC-mode HTTP server already binds every
 * interface, so the same page works at the phone's address on that link.
 */
object UsbTether {
    private val NAMES = listOf("rndis", "usb", "ncm")

    /** The phone's IPv4 address on the USB tethering interface, or null when tethering is off. */
    fun phoneIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { nif -> nif.isUp && NAMES.any { nif.name.startsWith(it) } }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
