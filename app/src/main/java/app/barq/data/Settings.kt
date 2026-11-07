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
            .apply()
        _state.value = next
    }

    fun trust(name: String) = update { it.copy(trustedDevices = (it.trustedDevices + name).distinct()) }
    fun untrust(name: String) = update { it.copy(trustedDevices = it.trustedDevices - name) }
    fun isTrusted(name: String) = name in _state.value.trustedDevices

    private fun read() = BarqSettings(
        deviceName = prefs.getString(K_NAME, null)?.takeIf { it.isNotBlank() } ?: systemName,
        autoAcceptTrusted = prefs.getBoolean(K_AUTO, true),
        skipExisting = prefs.getBoolean(K_SKIP, true),
        prefer5GHz = prefs.getBoolean(K_5G, true),
        trustedDevices = prefs.getStringSet(K_TRUST, emptySet())!!.sorted(),
        theme = runCatching { ThemeChoice.valueOf(prefs.getString(K_THEME, null) ?: "") }.getOrDefault(ThemeChoice.SYSTEM),
    )

    companion object {
        private const val K_NAME = "device_name"
        private const val K_AUTO = "auto_accept_trusted"
        private const val K_SKIP = "skip_existing"
        private const val K_5G = "prefer_5ghz"
        private const val K_TRUST = "trusted_devices"
        private const val K_THEME = "theme"

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context.applicationContext).also { instance = it } }
    }
}
