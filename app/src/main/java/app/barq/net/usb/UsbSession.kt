package app.barq.net.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import app.barq.data.Settings
import app.barq.engine.Link
import app.barq.engine.wireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger

sealed interface UsbState {
    data object Idle : UsbState
    data class Searching(val hint: String) : UsbState
    data class Connected(val peerName: String, val role: String) : UsbState
    data class Failed(val message: String) : UsbState
}

/** Messages on mux stream 0. */
@Serializable
data class UsbMsg(val type: String, val name: String = "", val token: String = "")

/**
 * Owns the cable link for the whole process: finds the other phone over USB
 * (as host or as accessory), brings up the [Mux], exchanges names on stream 0,
 * and hands protocol streams to whoever asked for them.
 *
 * - "send" messages announce that the remote will start a transfer with [token];
 *   the controller then runs a Receiver and feeds it [incoming] links.
 * - [openStream] gives the local Sender fresh streams (cable-only or multipath).
 */
object UsbSession {
    private const val ACTION_PERMISSION = "app.barq.USB_PERMISSION"
    private const val TAG = "Wamda"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UsbState>(UsbState.Idle)
    val state: StateFlow<UsbState> = _state.asStateFlow()

    /** Remote asked to send to us: token for the incoming session. */
    val remoteSend = MutableSharedFlow<UsbMsg>(extraBufferCapacity = 4)
    /** Protocol streams the remote opened (control + data of its transfer, or multipath data). */
    val incoming = MutableSharedFlow<Link>(extraBufferCapacity = 64)

    private var mux: Mux? = null
    private var control: Link? = null
    private var controlJob: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var pendingHostDevice: UsbDevice? = null
    private val nextStream = AtomicInteger(1)
    @Volatile var peerName: String = ""
        private set

    val isConnected: Boolean get() = _state.value is UsbState.Connected && mux != null

    /** Opens a fresh protocol stream for a local Sender. Ids 1..255, odd for host, even for accessory, so both sides can open. */
    fun openStream(): Link {
        val m = mux ?: throw IllegalStateException("USB not connected")
        var id = nextStream.getAndAdd(2)
        if (id > 254) { nextStream.set(if (id % 2 == 0) 2 else 1); id = nextStream.getAndAdd(2) }
        return m.open(id)
    }

    /** Tells the remote we are about to send with [token]. */
    fun announceSend(token: String, name: String) {
        val c = control ?: return
        runCatching { writeMsg(c, UsbMsg("send", name, token)) }
    }

    /** Starts looking for a phone on the cable; safe to call repeatedly. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (isConnected) return
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        registerReceiver(app)
        _state.value = UsbState.Searching("")

        // 1) Are we the accessory? (the other phone, as host, already switched us)
        usb.accessoryList?.firstOrNull { it.manufacturer == Aoa.MANUFACTURER }?.let { acc ->
            if (usb.hasPermission(acc)) openAccessory(app, acc) else usb.requestPermission(acc, permissionIntent(app))
            return
        }
        // 2) Are we the host? Any attached device: already in accessory mode, or switchable.
        val device = usb.deviceList.values.firstOrNull { Aoa.isAccessory(it) } ?: usb.deviceList.values.firstOrNull()
        if (device != null) {
            if (usb.hasPermission(device)) openDevice(app, device) else usb.requestPermission(device, permissionIntent(app))
            return
        }
        _state.value = UsbState.Searching("none")
    }

    fun stop() {
        controlJob?.cancel(); controlJob = null
        runCatching { control?.close() }; control = null
        runCatching { mux?.close() }; mux = null
        _state.value = UsbState.Idle
        peerName = ""
    }

    // ---------- accessory side ----------

    private fun openAccessory(app: Context, acc: UsbAccessory) {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val pfd = usb.openAccessory(acc) ?: run { _state.value = UsbState.Failed("openAccessory returned null"); return }
        nextStream.set(2) // accessory opens even ids
        bringUp(app, Mux(AccessoryPipe(pfd, acc.model ?: "accessory")), role = "accessory")
    }

    // ---------- host side ----------

    private fun openDevice(app: Context, device: UsbDevice) {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = usb.openDevice(device) ?: run { _state.value = UsbState.Failed("openDevice returned null"); return }
        if (!Aoa.isAccessory(device)) {
            // Ask it to re-enumerate as an accessory; the attach broadcast brings us back here.
            pendingHostDevice = device
            val ok = Aoa.switchToAccessory(conn)
            conn.close()
            _state.value = if (ok) UsbState.Searching("switching") else UsbState.Failed("الجهاز الآخر لا يدعم وضع الملحق (AOA)")
            if (ok) scope.launch { delay(6_000); if (_state.value is UsbState.Searching) start(app) }
            return
        }
        val (epIn, epOut) = Aoa.endpoints(device) ?: run { conn.close(); _state.value = UsbState.Failed("no bulk endpoints"); return }
        val itf = (0 until device.interfaceCount).map { device.getInterface(it) }.first { i -> (0 until i.endpointCount).any { i.getEndpoint(it) == epIn } }
        if (!conn.claimInterface(itf, true)) { conn.close(); _state.value = UsbState.Failed("claimInterface failed"); return }
        nextStream.set(1) // host opens odd ids
        bringUp(app, Mux(HostPipe(conn, epIn, epOut, device.productName ?: "device")), role = "host")
    }

    // ---------- common ----------

    private fun bringUp(app: Context, m: Mux, role: String) {
        runCatching { mux?.close() }
        mux = m
        m.start()
        val c = m.open(0).also { control = it }
        val myName = Settings.get(app).deviceName
        runCatching { writeMsg(c, UsbMsg("hello", myName)) }
        controlJob = scope.launch {
            // Stream 0 carries session messages; every other incoming stream is protocol traffic.
            launch {
                try {
                    val inp = DataInputStream(c.input)
                    while (true) {
                        val msg = readMsg(inp)
                        when (msg.type) {
                            "hello" -> { peerName = msg.name; _state.value = UsbState.Connected(msg.name, role) }
                            "send" -> remoteSend.emit(msg)
                            "bye" -> break
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "usb control ended: ${e.message}")
                }
                stop()
            }
            launch {
                for (link in m.incoming) {
                    if (link === c) continue
                    incoming.emit(link)
                }
            }
        }
        if (peerName.isEmpty()) _state.value = UsbState.Connected("…", role)
    }

    private fun writeMsg(link: Link, msg: UsbMsg) {
        val bytes = wireJson.encodeToString(UsbMsg.serializer(), msg).toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(link.output)
        synchronized(link) { out.writeInt(bytes.size); out.write(bytes); out.flush() }
    }

    private fun readMsg(inp: DataInputStream): UsbMsg {
        val len = inp.readInt()
        if (len !in 1..65536) throw java.io.IOException("bad usb message")
        val b = ByteArray(len); inp.readFully(b)
        return wireJson.decodeFromString(UsbMsg.serializer(), String(b, Charsets.UTF_8))
    }

    private fun permissionIntent(app: Context): PendingIntent {
        val intent = Intent(ACTION_PERMISSION).setPackage(app.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, 0, intent, flags)
    }

    private fun registerReceiver(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (!granted) { _state.value = UsbState.Failed("رُفض إذن USB"); return }
                        scope.launch { start(app) }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> scope.launch { delay(500); start(app) }
                    UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED -> { stop(); _state.value = UsbState.Searching("none") }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(app, r, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }
}
