package app.barq.web

import android.content.Context
import android.util.Log
import app.barq.data.PickedFile
import app.barq.data.Settings
import app.barq.net.WifiDirectHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** PC mode state exposed to the UI. */
sealed class PcState {
    data object Idle : PcState()
    data object Starting : PcState()
    data class Ready(
        val ssid: String,
        val pass: String,
        val url: String,
        val band: String,
        val stats: HttpServer.Stats,
        val sharedCount: Int,
        val uploadedCount: Int,
        val uploadedNames: List<String> = emptyList(),
        /** Where the USB-cable route to a computer stands, with its link once tethering is on. */
        val usb: UsbTether.Status = UsbTether.Status(),
    ) : PcState()
    data class Failed(val message: String) : PcState()
}

/**
 * Orchestrates PC mode: brings up a Wi-Fi Direct group with this phone as owner, then serves
 * the browser UI over HTTP on that link. Independent from the phone-to-phone TransferController.
 */
object PcSession {
    private const val TAG = "WamdaPc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow<PcState>(PcState.Idle)
    val state: StateFlow<PcState> get() = _state

    private val _shared = MutableStateFlow<List<PickedFile>>(emptyList())
    /** Files currently offered to the computer. */
    val shared: StateFlow<List<PickedFile>> get() = _shared

    /** Names the computer uploaded during this session (newest last). */
    private val _uploaded = MutableStateFlow<List<String>>(emptyList())
    val uploaded: StateFlow<List<String>> get() = _uploaded

    private var host: WifiDirectHost? = null
    private var server: HttpServer? = null
    private var statsJob: Job? = null
    private var group: WifiDirectHost.GroupInfo? = null
    @Volatile private var usbStatus: UsbTether.Status = UsbTether.Status()

    /** Starts the group and the server. No-op when already starting or ready. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                if (_state.value is PcState.Starting || _state.value is PcState.Ready) return@withLock
                _state.value = PcState.Starting
                _uploaded.value = emptyList()
                try {
                    val prefs = Settings.get(app).state.value
                    val h = WifiDirectHost(app)
                    host = h
                    // 2.4GHz by default: every laptop can see it; 5GHz is opt-in from the screen.
                    val g = h.start(
                        band = if (prefs.pc5GHz) WifiDirectHost.Band.PREFER_5GHZ else WifiDirectHost.Band.GHZ2,
                        suffix = prefs.pcSuffix, passphrase = prefs.pcPassword,
                    )
                    group = g
                    val s = HttpServer(
                        context = app,
                        filesProvider = { _shared.value },
                        onUploaded = { name, _ -> _uploaded.update { it + name } },
                        phoneName = { Settings.get(app).deviceName },
                        uploadedNames = { _uploaded.value },
                    )
                    s.start()
                    server = s
                    publish()
                    statsJob = scope.launch {
                        // Re-publish Ready whenever stats, shared list or uploads change.
                        // Stats change on every 64 KiB served; publishing each one made the whole screen recompose
                        // hundreds of times a second during a transfer. Coalesce to four updates a second.
                        launch { s.stats.collect { publish(); kotlinx.coroutines.delay(250) } }
                        launch { _shared.collect { publish() } }
                        launch { _uploaded.collect { publish() } }
                        // Poll the USB tethering interface; it appears when the user toggles tethering on.
                        launch {
                            while (true) {
                                val st = UsbTether.status(app)
                                if (st != usbStatus) { usbStatus = st; publish() }
                                kotlinx.coroutines.delay(2_000)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PC mode failed to start", e)
                    tearDown()
                    _state.value = PcState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Replaces the set of files offered for download. Safe to call at any time. */
    fun setShared(files: List<PickedFile>) {
        _shared.value = files
    }

    /** Applies new network settings: tears down and brings the group back up. */
    fun restart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                tearDown()
                _state.value = PcState.Idle
            }
            start(app)
        }
    }

    /** Stops the server and removes the Wi-Fi Direct group. */
    fun stop() {
        scope.launch {
            lock.withLock {
                tearDown()
                _state.value = PcState.Idle
            }
        }
    }

    private fun publish() {
        val g = group ?: return
        val s = server ?: return
        _state.value = PcState.Ready(
            ssid = g.ssid,
            pass = g.passphrase,
            url = "http://${g.ownerIp}:${HttpServer.PORT}",
            band = g.band,
            stats = s.stats.value,
            sharedCount = _shared.value.size,
            uploadedCount = _uploaded.value.size,
            uploadedNames = _uploaded.value,
            usb = usbStatus,
        )
    }

    private suspend fun tearDown() {
        statsJob?.cancel(); statsJob = null
        runCatching { server?.stop() }; server = null
        runCatching { host?.stop() }; host = null
        group = null
        usbStatus = UsbTether.Status()
    }
}
