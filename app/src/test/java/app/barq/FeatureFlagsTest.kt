package app.barq

import app.barq.data.BarqSettings
import app.barq.data.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {
    private fun settings(experimental: Boolean) = BarqSettings(
        deviceName = "t", autoAcceptTrusted = false, skipExisting = true, prefer5GHz = true, trustedDevices = emptyList(),
        theme = ThemeChoice.SYSTEM, pcSuffix = "AB", pcPassword = "12345678", pc5GHz = false, streams = 6, diskWriters = 4,
        experimental = experimental, benchmarkPlain = false,
    )

    @Test fun unlockedPhoneShowsBothExperimentalFeatures() {
        assertTrue(FeatureFlags.broadcast(settings(true))); assertTrue(FeatureFlags.phoneCable(settings(true)))
    }

    @Test fun lockedPhoneFollowsTheBuildDefault() {
        // With the default build (-PshipExperimental not set) both stay hidden; a build that ships them shows them to everyone.
        assertEquals(BuildConfig.EXPERIMENTAL_DEFAULT, FeatureFlags.broadcast(settings(false)))
        assertEquals(BuildConfig.EXPERIMENTAL_DEFAULT, FeatureFlags.phoneCable(settings(false)))
    }

    @Test fun theBuildSwitchOverridesThePerPhoneOneInBothDirections() {
        if (FeatureFlags.shippedToEveryone) assertTrue(FeatureFlags.broadcast(settings(false)))
        else assertFalse(FeatureFlags.broadcast(settings(false)))
    }

    @Test fun sevenTapsUnlockAndTheCountdownStartsAfterTheThird() {
        var taps = 0
        val seen = ArrayList<Pair<Boolean, Int>>()
        repeat(FeatureFlags.UNLOCK_TAPS - 1) {
            val r = FeatureFlags.registerTap(taps); taps = r.taps
            assertFalse("must not unlock before the ${FeatureFlags.UNLOCK_TAPS}th tap", r.unlocked)
            if (r.showCountdown) seen += true to r.left
        }
        assertEquals(listOf(true to 4, true to 3, true to 2, true to 1), seen) // taps 3, 4, 5 and 6
        val last = FeatureFlags.registerTap(taps)
        assertTrue(last.unlocked); assertEquals(0, last.taps)
        assertEquals(7, FeatureFlags.UNLOCK_TAPS)
    }

    @Test fun noCountdownBeforeTheThirdTap() {
        assertFalse(FeatureFlags.registerTap(0).showCountdown); assertFalse(FeatureFlags.registerTap(1).showCountdown)
        assertTrue(FeatureFlags.registerTap(2).showCountdown)
    }
}
