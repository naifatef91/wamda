package app.barq.data

import android.content.Context
import android.os.Build
import android.provider.Settings as SysSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

data class BarqSettings(
    val deviceName: String,
    val autoAcceptTrusted: Boolean,
    val skipExisting: Boolean,
    val prefer5GHz: Boolean,
    val trustedDevices: List<String>,
    val theme: ThemeChoice,
    /** PC mode network: stable 2-char SSID suffix, simple password (8–63 chars), band. */
    val pcSuffix: String,
    val pcPassword: String,
    val pc5GHz: Boolean,
    /** Parallel data streams the receiver asks for (4, 6 or 8). */
    val streams: Int,
    /** Chunks the receiver writes to flash at the same moment (1 to 8). Lower is gentler on slow eMMC, higher suits fast UFS. */
    val diskWriters: Int,
    /** Developer switch for features not yet proven on real devices (USB cable between phones, broadcast). Unlocked by tapping the version 7 times. */
    val experimental: Boolean,
    /** Benchmark only: receive without TLS so the cost of encryption can be measured. */
    val benchmarkPlain: Boolean,
)

/** User preferences, SharedPreferences-backed, exposed as one StateFlow. */
class Settings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("barq", Context.MODE_PRIVATE)
    private val systemName: String =
        SysSettings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val _state = MutableStateFlow(read())
    val state: StateFlow<BarqSettings> = _state.asStateFlow()

    val deviceName: String get() = _state.value.deviceName

    fun update(transform: (BarqSettings) -> BarqSettings) {
        val next = transform(_state.value)
        prefs.edit()
            .putString(K_NAME, next.deviceName)
            .putBoolean(K_AUTO, next.autoAcceptTrusted)
            .putBoolean(K_SKIP, next.skipExisting)
            .putBoolean(K_5G, next.prefer5GHz)
            .putStringSet(K_TRUST, next.trustedDevices.toSet())
            .putString(K_THEME, next.theme.name)
            .putString(K_PC_SUFFIX, next.pcSuffix)
            .putString(K_PC_PASS, next.pcPassword)
            .putBoolean(K_PC_5G, next.pc5GHz)
            .putInt(K_STREAMS, next.streams)
            .putInt(K_DISK_WRITERS, next.diskWriters)
            .putBoolean(K_EXPERIMENTAL, next.experimental)
            .putBoolean(K_PLAIN, next.benchmarkPlain)
            .apply()
        _state.value = next
    }

    /**
     * Trust is bound to the peer's certificate fingerprint, not its display name: a device
     * trusted without a fingerprint (plain or cable link) is listed but never auto-accepted.
     */
    fun trust(name: String, fingerprint: String?) {
        if (fingerprint != null) prefs.edit().putString(K_TRUST_FP + name, fingerprint).apply()
        update { it.copy(trustedDevices = (it.trustedDevices + name).distinct()) }
    }
    fun untrust(name: String) {
        prefs.edit().remove(K_TRUST_FP + name).apply()
        update { it.copy(trustedDevices = it.trustedDevices - name) }
    }
    fun isTrusted(name: String) = name in _state.value.trustedDevices

    /** True only when [name] is trusted and [fingerprint] is the one recorded when the user trusted it. */
    fun isTrusted(name: String, fingerprint: String?): Boolean {
        if (fingerprint == null || name !in _state.value.trustedDevices) return false
        val saved = prefs.getString(K_TRUST_FP + name, null) ?: return false
        return TrustPolicy.sameFingerprint(saved, fingerprint)
    }

    private fun read() = BarqSettings(
        deviceName = prefs.getString(K_NAME, null)?.takeIf { it.isNotBlank() } ?: systemName,
        autoAcceptTrusted = prefs.getBoolean(K_AUTO, false),
        skipExisting = prefs.getBoolean(K_SKIP, true),
        prefer5GHz = prefs.getBoolean(K_5G, true),
        trustedDevices = prefs.getStringSet(K_TRUST, emptySet())!!.sorted(),
        theme = runCatching { ThemeChoice.valueOf(prefs.getString(K_THEME, null) ?: "") }.getOrDefault(ThemeChoice.SYSTEM),
        pcSuffix = prefs.getString(K_PC_SUFFIX, null)?.takeIf { it.length == 2 } ?: randomSuffix().also { prefs.edit().putString(K_PC_SUFFIX, it).apply() },
        pcPassword = prefs.getString(K_PC_PASS, null)?.takeIf { it.length in 8..63 } ?: randomDigits().also { prefs.edit().putString(K_PC_PASS, it).apply() },
        pc5GHz = prefs.getBoolean(K_PC_5G, false),
        streams = prefs.getInt(K_STREAMS, 6).coerceIn(1, 8),
        diskWriters = prefs.getInt(K_DISK_WRITERS, 4).coerceIn(1, 8),
        experimental = prefs.getBoolean(K_EXPERIMENTAL, false),
        benchmarkPlain = prefs.getBoolean(K_PLAIN, false),
    )

    /** Ten digits from a CSPRNG: still easy to read off the screen and type on a laptop. */
    private fun randomDigits(): String { val r = java.security.SecureRandom(); return (1..10).map { ('0'..'9').elementAt(r.nextInt(10)) }.joinToString("") }
    private fun randomSuffix(): String { val r = java.security.SecureRandom(); val a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; return (1..2).map { a[r.nextInt(a.length)] }.joinToString("") }

    companion object {
        private const val K_NAME = "device_name"
        private const val K_AUTO = "auto_accept_trusted"
        private const val K_SKIP = "skip_existing"
        private const val K_5G = "prefer_5ghz"
        private const val K_TRUST = "trusted_devices"
        private const val K_TRUST_FP = "trusted_fp:"
        private const val K_THEME = "theme"
        private const val K_PC_SUFFIX = "pc_suffix"
        private const val K_PC_PASS = "pc_password"
        private const val K_PC_5G = "pc_5ghz"
        private const val K_STREAMS = "streams"
        private const val K_DISK_WRITERS = "disk_writers"
        private const val K_EXPERIMENTAL = "experimental"
        private const val K_PLAIN = "benchmark_plain"

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context.applicationContext).also { instance = it } }
    }
}
