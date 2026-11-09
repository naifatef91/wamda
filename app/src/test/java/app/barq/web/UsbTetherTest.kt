package app.barq.web

import app.barq.web.UsbTether.Candidate
import app.barq.web.UsbTether.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTetherTest {
    @Test fun tetheringInterfacesAreRecognisedByPrefix() {
        for (n in listOf("rndis0", "usb0", "ncm0", "rndis1")) assertTrue("$n should count as USB tethering", UsbTether.isTetherInterface(n))
        for (n in listOf("wlan0", "p2p-wlan0-0", "eth0", "lo", "rmnet_data0", "ap0")) assertFalse("$n must not count", UsbTether.isTetherInterface(n))
    }

    @Test fun driverFamilyIsNamedForTheUser() {
        assertEquals("RNDIS", UsbTether.driverOf("rndis0")); assertEquals("NCM", UsbTether.driverOf("ncm0")); assertEquals("USB", UsbTether.driverOf("usb0"))
    }

    @Test fun rndisIsPreferredThenNcmThenGeneric() {
        val all = listOf(Candidate("usb0", "10.0.0.1"), Candidate("ncm0", "10.0.1.1"), Candidate("rndis0", "192.168.42.129"))
        assertEquals("rndis0", UsbTether.pick(all)!!.name)
        assertEquals("ncm0", UsbTether.pick(all.filter { it.name != "rndis0" })!!.name)
        assertEquals("usb0", UsbTether.pick(all.filter { it.name == "usb0" })!!.name)
    }

    @Test fun nonTetheringInterfacesAreNeverPicked() {
        assertNull(UsbTether.pick(listOf(Candidate("wlan0", "192.168.1.5"), Candidate("p2p-wlan0-0", "192.168.49.1"))))
        assertNull(UsbTether.pick(emptyList()))
    }

    @Test fun stageFollowsWhatThePhoneCanSee() {
        assertEquals(Stage.NO_CABLE, UsbTether.stageOf(ip = null, tetherFunction = false, cableConnected = false))
        assertEquals(Stage.CABLE_ONLY, UsbTether.stageOf(ip = null, tetherFunction = false, cableConnected = true))
        assertEquals(Stage.STARTING, UsbTether.stageOf(ip = null, tetherFunction = true, cableConnected = true))
        assertEquals(Stage.READY, UsbTether.stageOf(ip = "192.168.42.129", tetherFunction = true, cableConnected = true))
    }

    @Test fun anAddressOnTheInterfaceWinsEvenIfTheUsbStateBroadcastIsMissing() {
        // Some phones never deliver the sticky USB_STATE broadcast; the interface itself is the truth.
        assertEquals(Stage.READY, UsbTether.stageOf(ip = "192.168.42.129", tetherFunction = false, cableConnected = false))
    }

    @Test fun readyStatusCarriesTheLinkTheScreenShowsAndEncodesAsQr() {
        val s = UsbTether.Status(Stage.READY, ip = "192.168.42.129", iface = "ncm0")
        assertEquals("http://192.168.42.129:8080", s.url)
        assertEquals("NCM", s.driver)
        assertNull(UsbTether.Status().url); assertNull(UsbTether.Status().driver)
    }

    @Test fun theHttpServerAnswersOnExactlyTheInterfacesDetectionLooksAt() {
        // PC mode refuses every other interface, so a detected link must be one it will actually serve.
        assertEquals(listOf("rndis", "usb", "ncm"), UsbTether.PREFIXES)
    }
}
