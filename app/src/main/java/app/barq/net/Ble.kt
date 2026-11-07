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
import android.os.ParcelUuid
import app.barq.engine.QrPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Nearby discovery over Bluetooth LE (spec FR-01).
 *
 * The receiver advertises the Wamda service UUID with its display name as service
 * data. The sender scans for that UUID, and when the user taps a device it reads
 * the full connection payload (the same JSON as the QR code) from one GATT
 * characteristic, then joins the Wi-Fi Direct group exactly as after a QR scan.
 */
object BleIds {
    val SERVICE: UUID = UUID.fromString("5b0b4a1e-7c6d-4d3a-9d2e-2a1f0c4b8e61")
    val PAYLOAD: UUID = UUID.fromString("5b0b4a1e-7c6d-4d3a-9d2e-2a1f0c4b8e62")
    /** 16-bit UUID used only as the key of the name service-data field (keeps 26 bytes for the name). */
    val NAME_KEY: ParcelUuid = ParcelUuid.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    const val MAX_NAME_BYTES = 26
}

fun Context.bluetoothAdapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

@SuppressLint("MissingPermission")
class BleAdvertiser(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var callback: AdvertiseCallback? = null
    private var payload: ByteArray = ByteArray(0)

    /** Returns false when this phone cannot advertise (no BLE, Bluetooth off); the QR path still works. */
    fun start(qr: QrPayload): Boolean {
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false
        payload = qr.encode().toByteArray(Charsets.UTF_8)

        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(BleIds.PAYLOAD, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != BleIds.PAYLOAD) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null); return
                }
                val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        })?.also { it.addService(service) } ?: return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val main = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleIds.SERVICE))
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

    fun stop() {
        callback?.let { runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        callback = null
        runCatching { server?.close() }
        server = null
    }

    private fun truncateUtf8(s: String, max: Int): ByteArray {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > max && out.isNotEmpty()) out = out.dropLast(1)
        return out.toByteArray(Charsets.UTF_8)
    }
}

data class NearbyDevice(val address: String, val name: String, val rssi: Int, val lastSeen: Long)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter = context.bluetoothAdapter()
    private val found = LinkedHashMap<String, NearbyDevice>()
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()
    private var callback: ScanCallback? = null

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
        found[r.device.address] = NearbyDevice(r.device.address, name, r.rssi, System.currentTimeMillis())
        publish()
    }

    private fun publish() {
        _devices.value = found.values.sortedByDescending { it.rssi }
    }

    /** Connects to the device, reads the connection payload, disconnects. */
    suspend fun fetchPayload(address: String): QrPayload = withTimeout(20_000) {
        val device = adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
        suspendCancellableCoroutine { cont ->
            var gatt: BluetoothGatt? = null
            fun finish(result: Result<QrPayload>) {
                runCatching { gatt?.disconnect(); gatt?.close() }
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            fun deliver(bytes: ByteArray) {
                val qr = QrPayload.decode(String(bytes, Charsets.UTF_8))
                finish(if (qr != null) Result.success(qr) else Result.failure(IOException("bad payload")))
            }
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!g.requestMtu(512)) g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(Result.failure(IOException("disconnected (status $status)")))
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val ch = g.getService(BleIds.SERVICE)?.getCharacteristic(BleIds.PAYLOAD)
                    if (ch == null) finish(Result.failure(IOException("not a Wamda device"))) else g.readCharacteristic(ch)
                }
                @Deprecated("pre-33 callback")
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    if (Build.VERSION.SDK_INT < 33) {
                        if (status == BluetoothGatt.GATT_SUCCESS) deliver(ch.value ?: ByteArray(0)) else finish(Result.failure(IOException("read failed $status")))
                    }
                }
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) deliver(value) else finish(Result.failure(IOException("read failed $status")))
                }
            }
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(context, false, cb)
            cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
        }
    }
}
