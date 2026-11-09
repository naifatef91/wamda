package app.barq

import app.barq.data.BarqSettings

/**
 * Which features are visible. Two features are not proven on real devices yet (the phone-to-phone USB cable
 * and broadcast to several receivers), so the first public release hides them:
 *
 * - Release switch: Gradle property `shipExperimental`. Build with `-PshipExperimental=true` (or put it in
 *   gradle.properties) to ship them to everyone; the default is false. It is one line to flip before building the AAB.
 * - Testing switch: tapping the version number 7 times in Settings turns on "Experimental features" on
 *   that phone, so they can be tried on real devices without rebuilding. It can be turned off again there.
 */
object FeatureFlags {
    /** The build-time default, from the Gradle property. */
    val shippedToEveryone: Boolean get() = BuildConfig.EXPERIMENTAL_DEFAULT

    fun experimental(settings: BarqSettings): Boolean = shippedToEveryone || settings.experimental

    /** "Broadcast to several devices" on the discovery screen, and joining one by scanning its code. */
    fun broadcast(settings: BarqSettings): Boolean = experimental(settings)

    /** The phone-to-phone USB cable (home button, auto-opening on a cable, multipath over the cable). */
    fun phoneCable(settings: BarqSettings): Boolean = experimental(settings)

    /** Taps on the version number needed to unlock the experimental switch. */
    const val UNLOCK_TAPS = 7

    /** [left] is how many taps remain; Android's own developer options start counting down after the third. */
    class TapResult(val taps: Int, val unlocked: Boolean, val left: Int) {
        val showCountdown: Boolean get() = !unlocked && taps >= 3
    }

    fun registerTap(current: Int): TapResult {
        val n = current + 1
        return if (n >= UNLOCK_TAPS) TapResult(0, true, 0) else TapResult(n, false, UNLOCK_TAPS - n)
    }
}
