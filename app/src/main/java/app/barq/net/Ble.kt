package app.barq.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.barq.engine.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Nearby discovery over Bluetooth LE (spec FR-01), in two steps so nothing sensitive is ever broadcast.
 *
 * 1. The receiver advertises only the Wamda service UUID, its display name and one capability byte.
 * 2. When the sender's user taps a device, the sender connects over GATT and writes a request (its
 *    name, its certificate fingerprint and a one-time key). The receiver's user sees "X wants to
 *    connect" (or it is approved automatically for a trusted device). Only after approval does the
 *    reply characteristic hold the network details, sealed for that sender alone, and advertising stops.
 */
object BleIds {
    val SERVICE: UUID = UUID.fromString("5b0b4a1e-7c6d-4d3a-9d2e-2a1f0c4b8e61")
    /** Written by the requester. */
    val REQUEST: UUID = UUID.fromString("5b0b4a1e-7c6d-4d3a-9d2e-2a1f0c4b8e63")
    /** Read by the requester: pending, declined, timed out, or approved + sealed payload. */
    val REPLY: UUID = UUID.fromString("5b0b4a1e-7c6d-4d3a-9d2e-2a1f0c4b8e64")
    /** 16-bit UUID used only as the key of the name service-data field (keeps 26 bytes for the name). */
    val NAME_KEY: ParcelUuid = ParcelUuid.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    /** One capability byte, see [PairingWire.capabilities]. */
    val CAPS_KEY: ParcelUuid = ParcelUuid.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    const val MAX_NAME_BYTES = 26

    /** How long the sender waits for the receiver's user, and the receiver for the user's answer. */
    const val APPROVAL_TIMEOUT_MS = 30_000L
}

fun Context.bluetoothAdapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

@SuppressLint("MissingPermission")
class BleAdvertiser(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var callback: AdvertiseCallback? = null
    private var broker: ConnectBroker? = null
    private var scope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Long writes arrive in pieces; keyed by device address. */
    private val partial = HashMap<String, ByteArrayOutputStream>()

    /**
     * Returns false when this phone cannot advertise (no BLE, Bluetooth off); the QR path still works.
     * [qr] is kept private and leaves this class only sealed inside an approved reply; the advertisement
     * is built from the display name and capability flags alone.
     */
    fun start(qr: QrPayload, decide: suspend (name: String, fp: String) -> Decision): Boolean {
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val b = ConnectBroker(s, qr, decide, onApproved = { stopAdvertising(); closeServerLater() }).also { broker = it }

        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(BleIds.REQUEST, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(BleIds.REPLY, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // The requester left before an answer: stop asking the user about it.
                    synchronized(partial) { partial.remove(device.address) }
                    handler.postDelayed({ if (!isConnected(device)) b.forget(device.address) }, 1500)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
            ) {
                if (characteristic.uuid != BleIds.REQUEST || value == null) {
                    reply(device, requestId, BluetoothGatt.GATT_FAILURE, 0, responseNeeded); return
                }
                if (preparedWrite) {
                    synchronized(partial) {
                        val buf = partial.getOrPut(device.address) { ByteArrayOutputStream() }
                        if (offset != buf.size() || buf.size() + value.size > 512) { partial.remove(device.address); reply(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, responseNeeded); return }
                        buf.write(value)
                    }
                    reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseNeeded, value)
                } else {
                    val ok = b.onRequest(device.address, value)
                    reply(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, responseNeeded)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                val bytes = synchronized(partial) { partial.remove(device.address)?.toByteArray() }
                val ok = execute && bytes != null && b.onRequest(device.address, bytes)
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != BleIds.REPLY) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null); return
                }
                val full = b.reply(device.address)
                val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        })?.also { it.addService(service) } ?: return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Everything in the advertisement: service id, one capability byte, display name. No network
        // name, password, token or fingerprint ever goes into AdvertiseData.
        val main = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleIds.SERVICE))
            .addServiceData(BleIds.CAPS_KEY, byteArrayOf(PairingWire.capabilities(ready = true, encrypted = qr.enc)))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val response = AdvertiseData.Builder()
            .addServiceData(BleIds.NAME_KEY, truncateUtf8(qr.name, BleIds.MAX_NAME_BYTES))
            .build()
        val cb = object : AdvertiseCallback() {}
        callback = cb
        advertiser.startAdvertising(settings, main, response, cb)
        return true
    }

    private fun reply(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, needed: Boolean, value: ByteArray? = null) {
        if (needed) server?.sendResponse(device, requestId, status, offset, value)
    }

    private fun isConnected(device: BluetoothDevice): Boolean =
        manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).any { it.address == device.address }

    /** Approval done: nobody else needs to discover us through this advertisement. */
    fun stopAdvertising() {
        callback?.let { runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        callback = null
    }

    /** The approved requester still has to read its reply, so the GATT server outlives the advertisement briefly. */
    private fun closeServerLater() {
        handler.postDelayed({ stop() }, 20_000)
    }

    fun stop() {
        stopAdvertising()
        broker?.clear(); broker = null
        scope?.cancel(); scope = null
        runCatching { server?.close() }
        server = null
        synchronized(partial) { partial.clear() }
    }

    private fun truncateUtf8(s: String, max: Int): ByteArray {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > max && out.isNotEmpty()) out = out.dropLast(1)
        return out.toByteArray(Charsets.UTF_8)
    }
}

data class NearbyDevice(val address: String, val name: String, val rssi: Int, val lastSeen: Long, val caps: Int = 0)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter = context.bluetoothAdapter()
    private val found = LinkedHashMap<String, NearbyDevice>()
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()
    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    val available: Boolean get() = adapter?.isEnabled == true && adapter.bluetoothLeScanner != null

    fun start(): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        }
        callback = cb
        scanner.startScan(listOf(filter), settings, cb)
        return true
    }

    /** Drops devices not seen for 12 s; call from a ticker. */
    @Synchronized
    fun prune() {
        val cutoff = System.currentTimeMillis() - 12_000
        if (found.values.removeAll { it.lastSeen < cutoff }) publish()
    }

    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
    }

    @Synchronized
    private fun handle(r: ScanResult) {
        val nameBytes = r.scanRecord?.getServiceData(BleIds.NAME_KEY)
        val name = nameBytes?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
            ?: found[r.device.address]?.name ?: "Wamda"
        val caps = r.scanRecord?.getServiceData(BleIds.CAPS_KEY)?.firstOrNull()?.toInt() ?: 0
        found[r.device.address] = NearbyDevice(r.device.address, name, r.rssi, System.currentTimeMillis(), caps)
        publish()
    }

    private fun publish() {
        _devices.value = found.values.sortedByDescending { it.rssi }
    }

    /**
     * Asks the device at [address] to connect and returns the pairing payload once its user approves.
     * Fails with [PairingDeclined], [PairingTimeout] or an [IOException] (connection lost, not a Wamda device).
     * [onWaiting] fires when the request reached the other phone and we are waiting for its user.
     */
    suspend fun fetchPayload(address: String, myName: String, myFingerprint: String, onWaiting: () -> Unit = {}): QrPayload {
        val device = adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
        val eph = PairingCrypto.newEphemeral()
        val request = PairingWire.encodeRequest(myName, myFingerprint, eph)
        return try {
            withTimeout(BleIds.APPROVAL_TIMEOUT_MS + 15_000) { exchange(device, request, eph, myFingerprint, onWaiting) }
        } catch (e: TimeoutCancellationException) {
            throw PairingTimeout()
        }
    }

    private suspend fun exchange(
        device: BluetoothDevice, request: ByteArray, eph: PairingCrypto.Ephemeral, fp: String, onWaiting: () -> Unit,
    ): QrPayload = suspendCancellableCoroutine { cont ->
        var gatt: BluetoothGatt? = null
        var done = false
        val deadline = System.currentTimeMillis() + BleIds.APPROVAL_TIMEOUT_MS + 5_000

        fun finish(result: Result<QrPayload>) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            runCatching { gatt?.disconnect(); gatt?.close() }
            if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
        fun readReply(g: BluetoothGatt) {
            val ch = g.getService(BleIds.SERVICE)?.getCharacteristic(BleIds.REPLY)
            if (ch == null) finish(Result.failure(IOException("not a Wamda device"))) else g.readCharacteristic(ch)
        }
        fun onReply(g: BluetoothGatt, bytes: ByteArray) {
            when (val r = PairingWire.openReply(bytes, eph, fp)) {
                PairingWire.Reply.Pending ->
                    if (System.currentTimeMillis() > deadline) finish(Result.failure(PairingTimeout()))
                    else handler.postDelayed({ if (!done) readReply(g) }, 700)
                PairingWire.Reply.Declined -> finish(Result.failure(PairingDeclined()))
                PairingWire.Reply.Timeout -> finish(Result.failure(PairingTimeout()))
                PairingWire.Reply.Bad -> finish(Result.failure(IOException("bad pairing reply")))
                is PairingWire.Reply.Approved -> {
                    val qr = PairingWire.qrOf(r.plaintext)
                    finish(if (qr != null) Result.success(qr) else Result.failure(IOException("bad payload")))
                }
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(512)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(Result.failure(IOException("Bluetooth connection lost (status $status)")))
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(BleIds.SERVICE)?.getCharacteristic(BleIds.REQUEST)
                if (ch == null) { finish(Result.failure(IOException("not a Wamda device"))); return }
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run { ch.value = request; g.writeCharacteristic(ch) }
                }
                if (!started) finish(Result.failure(IOException("could not send the request")))
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { finish(Result.failure(IOException("request refused (status $status)"))); return }
                handler.post { onWaiting() }
                readReply(g)
            }
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) {
                    if (status == BluetoothGatt.GATT_SUCCESS) onReply(g, ch.value ?: ByteArray(0)) else finish(Result.failure(IOException("read failed $status")))
                }
            }
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onReply(g, value) else finish(Result.failure(IOException("read failed $status")))
            }
        }
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        cont.invokeOnCancellation { done = true; handler.removeCallbacksAndMessages(null); runCatching { gatt?.disconnect(); gatt?.close() } }
    }
}
