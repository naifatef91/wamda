package app.barq

import android.content.Context
import app.barq.crypto.Tls
import app.barq.data.HistoryStore
import app.barq.data.PickedFile
import app.barq.data.Settings
import app.barq.data.TransferRecord
import app.barq.engine.FileEntry
import app.barq.engine.Protocol
import app.barq.engine.QrPayload
import app.barq.engine.ReadableFile
import app.barq.engine.ReceiveStore
import app.barq.engine.Receiver
import app.barq.engine.SendItem
import app.barq.engine.Sender
import app.barq.engine.Snapshot
import app.barq.engine.TransferManifest
import app.barq.engine.TransferStats
import app.barq.net.BleAdvertiser
import app.barq.net.WifiClient
import app.barq.net.WifiDirectHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

enum class Direction { SEND, RECEIVE }

sealed interface TransferState {
    data object Idle : TransferState
    data class Preparing(val step: Step, val attempt: Int = 0, val maxAttempts: Int = 0) : TransferState
    /** [dropped] = a sender was mid-transfer and vanished; same QR stays valid while we wait. */
    data class Listening(val qr: QrPayload, val band: String, val dropped: Boolean = false) : TransferState
    data class AwaitingAccept(val manifest: TransferManifest) : TransferState
    data class Running(val direction: Direction, val peer: String, val band: String, val snapshot: Snapshot) : TransferState
    /** [sessionOpen] = the link is still up, so more files can go without pairing again. */
    data class Finished(val direction: Direction, val peer: String, val snapshot: Snapshot, val incomplete: Int, val sessionOpen: Boolean = true) : TransferState
    data class Failed(val message: String, val retryable: Boolean = false) : TransferState

    enum class Step { HOTSPOT, WIFI, HANDSHAKE, RECONNECT, PREPARE }
}

/**
 * Single owner of the transfer engine for the whole process. The UI observes
 * [state]; the foreground service keeps the process alive while not Idle.
 *
 * Sessions: the receiver keeps its network, QR code and BLE beacon alive after
 * a transfer completes, and the sender keeps its Wi-Fi link, so either side can
 * continue with more files until the user leaves the screen. The sender retries
 * [SEND_ATTEMPTS] times by itself; the receiver waits [RECONNECT_WINDOW_MS] after a drop.
 */
object TransferController {
    private const val SEND_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 4_000L
    private const val RECONNECT_WINDOW_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /** Files handed to the app by the system share sheet, consumed by the send screen. */
    val shareIntake = MutableStateFlow<List<PickedFile>>(emptyList())

    private var job: Job? = null
    private var host: WifiDirectHost? = null
    private var advertiser: BleAdvertiser? = null
    private var client: WifiClient? = null
    private var acceptGate: CompletableDeferred<Boolean>? = null
    private var pendingSender: String? = null

    private var receiveQr: QrPayload? = null
    private var receiveBand: String = ""
    private var lastSend: Pair<QrPayload, List<PickedFile>>? = null
    private var lastDirection: Direction? = null

    /** The receiver the sender is currently paired with, while its link is up. */
    val openSendSession: QrPayload? get() = lastSend?.first?.takeIf { client?.isUsable == true }

    fun deviceName(context: Context): String = Settings.get(context).deviceName

    // ---------- Receive ----------

    fun startReceiving(context: Context) {
        val app = context.applicationContext
        cancel()
        lastDirection = Direction.RECEIVE
        job = scope.launch {
            try {
                _state.value = TransferState.Preparing(TransferState.Step.HOTSPOT)
                val settings = Settings.get(app)
                val h = WifiDirectHost(app).also { host = it }
                val group = h.start(if (settings.state.value.prefer5GHz) WifiDirectHost.Band.PREFER_5GHZ else WifiDirectHost.Band.AUTO)
                val token = (100000 + (Math.random() * 900000).toInt()).toString()
                val identity = Tls.identity()
                val qr = QrPayload(
                    ssid = group.ssid, pass = group.passphrase, ip = group.ownerIp,
                    port = Protocol.PORT, token = token, name = deviceName(app), freq = group.frequencyMhz,
                    fp = identity.fingerprint, enc = !settings.state.value.benchmarkPlain,
                )
                receiveQr = qr; receiveBand = group.band
                _state.value = TransferState.Listening(qr, group.band)
                advertiser = BleAdvertiser(app).also { runCatching { it.start(qr) } }

                var stats: TransferStats? = null
                var peer = ""
                var acceptedPeer: String? = null
                var startedAt = 0L
                var watchdog: Job? = null

                val receiver = Receiver(
                    port = Protocol.PORT, token = token, deviceName = deviceName(app),
                    store = ReceiveStore(app, resume = settings.state.value.skipExisting),
                    onIncoming = { manifest ->
                        val s = settings.state.value
                        if (s.autoAcceptTrusted && manifest.senderName in s.trustedDevices) return@Receiver true
                        // Same sender, same session (reconnect or "send more"): no second dialog.
                        if (manifest.senderName == acceptedPeer) return@Receiver true
                        pendingSender = manifest.senderName
                        val gate = CompletableDeferred<Boolean>().also { acceptGate = it }
                        _state.value = TransferState.AwaitingAccept(manifest)
                        val ok = gate.await()
                        if (ok) acceptedPeer = manifest.senderName
                        ok
                    },
                    onPlanned = { s, p ->
                        watchdog?.cancel(); watchdog = null
                        stats = s; peer = p
                        if (startedAt == 0L) startedAt = System.currentTimeMillis()
                    },
                    onSessionDone = { result ->
                        val snap = stats?.snapshot() ?: emptySnapshot()
                        record(app, Direction.RECEIVE, peer, group.band, stats, startedAt, result.incompleteFiles.size)
                        stats = null; startedAt = 0L
                        _state.value = TransferState.Finished(Direction.RECEIVE, peer, snap, result.incompleteFiles.size)
                    },
                    onDropped = { cause ->
                        stats = null
                        _state.value = TransferState.Listening(qr, group.band, dropped = true)
                        watchdog = scope.launch {
                            delay(RECONNECT_WINDOW_MS)
                            job?.cancel(ReconnectTimeout(cause))
                        }
                    },
                    streams = settings.state.value.streams,
                    encrypted = qr.enc,
                )
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        stats?.let { _state.value = TransferState.Running(Direction.RECEIVE, peer, group.band, it.snapshot()) }
                    }
                }
                try {
                    receiver.run()
                } catch (e: CancellationException) {
                    val timeout = e as? ReconnectTimeout ?: (e.cause as? ReconnectTimeout)
                    if (timeout != null) {
                        _state.value = TransferState.Failed(timeout.cause?.message ?: "sender did not come back", retryable = true)
                    }
                    throw e
                } catch (e: Exception) {
                    stats?.let { st -> record(app, Direction.RECEIVE, peer, group.band, st, startedAt, st.snapshot().files.count { !it.skipped && it.done < it.size }) }
                    throw e
                } finally {
                    ticker.cancel(); watchdog?.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "receive failed", e)
                _state.value = TransferState.Failed(describe(e), retryable = true)
            } finally {
                runCatching { advertiser?.stop() }
                advertiser = null
                runCatching { host?.stop() }
                host = null
            }
        }
    }

    /** After a completed receive: back to the QR screen, same network, same code. */
    fun continueReceiving() {
        val qr = receiveQr ?: return
        if (job?.isActive == true) _state.value = TransferState.Listening(qr, receiveBand)
    }

    private class ReconnectTimeout(cause: Throwable) : CancellationException("reconnect window elapsed") {
        init { initCause(cause) }
    }

    fun accept(context: Context, yes: Boolean, trust: Boolean = false) {
        if (yes && trust) pendingSender?.let { Settings.get(context).trust(it) }
        pendingSender = null
        acceptGate?.complete(yes)
    }

    // ---------- Send ----------

    fun startSending(context: Context, qr: QrPayload, files: List<PickedFile>) {
        val app = context.applicationContext
        val reuse = client?.takeIf { it.isUsable && it.ssid == qr.ssid }
        cancelJobOnly()
        lastSend = qr to files
        lastDirection = Direction.SEND
        job = scope.launch {
            val band = bandOf(qr.freq)
            var peer = qr.name
            var startedAt = 0L
            var lastStats: TransferStats? = null
            var ok = false
            try {
                _state.value = TransferState.Preparing(TransferState.Step.PREPARE)
                val items = files.mapIndexed { i, f -> toSendItem(app, f, i + 1) }
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        val network = if (attempt == 1 && reuse != null) {
                            android.util.Log.i(TAG, "reusing link to ${qr.ssid}")
                            _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                            reuse.network!!
                        } else {
                            _state.value = if (attempt == 1) TransferState.Preparing(TransferState.Step.WIFI)
                            else TransferState.Preparing(TransferState.Step.RECONNECT, attempt, SEND_ATTEMPTS)
                            client?.close()
                            val c = WifiClient(app).also { client = it }
                            android.util.Log.i(TAG, "joining ${qr.ssid} (attempt $attempt)")
                            val n = c.connect(qr.ssid, qr.pass)
                            android.util.Log.i(TAG, "joined ${qr.ssid}; connecting to ${qr.ip}:${qr.port}")
                            if (attempt == 1) _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                            n
                        }

                        var stats: TransferStats? = null
                        val sender = Sender(network, qr.ip, qr.port, qr.token, qr.fp, deviceName(app), items, onPlanned = { s, p ->
                            stats = s; lastStats = s
                            if (startedAt == 0L) startedAt = System.currentTimeMillis()
                            if (p.isNotBlank()) peer = p
                        }, encrypted = qr.enc)
                        val ticker = launch {
                            while (isActive) {
                                delay(500)
                                stats?.let { _state.value = TransferState.Running(Direction.SEND, peer, band, it.snapshot()) }
                            }
                        }
                        val result = try { sender.run() } finally { ticker.cancel() }
                        record(app, Direction.SEND, peer, band, stats, startedAt, result.incompleteFiles.size)
                        ok = true
                        _state.value = TransferState.Finished(Direction.SEND, peer, stats?.snapshot() ?: emptySnapshot(), result.incompleteFiles.size)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "send attempt $attempt failed", e)
                        // Declined or wrong code: retrying would not help.
                        val fatal = e.message?.let { it.contains("declined") || it.contains("bad token") || it.contains("certificate") } == true
                        if (fatal || attempt >= SEND_ATTEMPTS) throw e
                        delay(RETRY_DELAY_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "send failed", e)
                lastStats?.let { st -> record(app, Direction.SEND, peer, band, st, startedAt, st.snapshot().files.count { !it.skipped && it.done < it.size }) }
                _state.value = TransferState.Failed(describe(e), retryable = true)
            } finally {
                // Keep the Wi-Fi link after success so the user can send more; drop it on failure.
                if (!ok) { client?.close(); client = null }
            }
        }
    }

    /** After a completed send: back to Idle for the UI, link kept so the next send skips pairing. */
    fun prepareNextSend() {
        cancelJobOnly()
        _state.value = TransferState.Idle
    }

    /** Repeats the last send or reopens the receiver after a failure. */
    fun retry(context: Context) {
        when (lastDirection) {
            Direction.SEND -> lastSend?.let { (qr, files) -> startSending(context, qr, files) }
            Direction.RECEIVE -> startReceiving(context)
            null -> {}
        }
    }

    // ---------- Lifecycle ----------

    private fun cancelJobOnly() {
        job?.cancel()
        job = null
        acceptGate?.complete(false)
        acceptGate = null
        pendingSender = null
    }

    fun cancel() {
        cancelJobOnly()
        client?.close()
        client = null
        receiveQr = null
        runCatching { advertiser?.stop() }
        advertiser = null
        host?.let { h -> scope.launch { runCatching { h.stop() } } }
        host = null
    }

    fun reset() {
        cancel()
        _state.value = TransferState.Idle
    }

    // ---------- Helpers ----------

    private fun record(context: Context, direction: Direction, peer: String, band: String, stats: TransferStats?, startedAt: Long, incomplete: Int) {
        val snap = stats?.snapshot() ?: return
        android.util.Log.i(
            TAG, "profile $direction peer=$peer band=$band streams=${snap.streams} bytes=${snap.bytesDone} " +
                "elapsedMs=${snap.elapsedMs} avgMBps=${"%.1f".format(snap.avgBps / 1048576)} stagesMs=${snap.profileMs}",
        )
        HistoryStore.get(context).add(
            TransferRecord(
                id = java.util.UUID.randomUUID().toString(),
                direction = direction, peer = peer, fileCount = snap.files.size, bytes = snap.bytesDone,
                avgBps = snap.avgBps, band = band, startedAt = startedAt, durationMs = snap.elapsedMs, incomplete = incomplete,
            ),
        )
    }

    private fun toSendItem(context: Context, f: PickedFile, id: Int): SendItem {
        val resolver = context.contentResolver
        val open: () -> ReadableFile = {
            if (f.uri.scheme == "file") {
                val stream = FileInputStream(File(f.uri.path!!))
                ReadableFile(stream.channel) { stream.close() }
            } else {
                val pfd = resolver.openFileDescriptor(f.uri, "r") ?: throw java.io.IOException("cannot open ${f.name}")
                ReadableFile(FileInputStream(pfd.fileDescriptor).channel) { pfd.close() }
            }
        }
        // Quick fingerprint (≤ 512 KiB read) so the receiver can skip files it already has.
        val fp = runCatching { open().use { app.barq.engine.QuickHash.of(it.channel, f.size) } }.getOrNull()
        val entry = FileEntry(id, f.name, f.size, f.mime, f.modifiedSec * 1000, path = f.relPath, fp = fp)
        return SendItem(entry, open)
    }

    private fun bandOf(freq: Int) = when {
        freq >= 5925 -> "6GHz"
        freq >= 4900 -> "5GHz"
        freq > 0 -> "2.4GHz"
        else -> "?"
    }

    private fun emptySnapshot() = Snapshot(0, 0, 0.0, 0.0, 0, 0, 0, emptyList())

    private const val TAG = "Wamda"

    /** Human-readable failure: exception type plus message, plus the root cause when it differs. */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val head = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim().trimEnd(':')
        return if (root !== e && root.message != null) "$head ← ${root.javaClass.simpleName}: ${root.message}" else head
    }
}
