package app.barq

import android.content.Context
import app.barq.crypto.Tls
import app.barq.data.HistoryStore
import app.barq.data.PickedFile
import app.barq.data.Settings
import app.barq.data.TransferRecord
import app.barq.engine.BroadcastHost
import app.barq.engine.DialTransport
import app.barq.engine.FileEntry
import app.barq.engine.Link
import app.barq.engine.SenderTransport
import app.barq.net.usb.UsbSession
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
import app.barq.net.AwareHost
import app.barq.net.AwareScanner
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
    /** Broadcast mode: this phone hosts the network and serves every receiver that joins. */
    data class Broadcasting(val qr: QrPayload, val band: String, val peers: List<PeerProgress>) : TransferState

    enum class Step { HOTSPOT, WIFI, HANDSHAKE, RECONNECT, PREPARE }
}

enum class PeerStatus { JOINED, RUNNING, DONE, FAILED }

data class PeerProgress(val session: String, val name: String, val snapshot: Snapshot?, val status: PeerStatus, val message: String = "", val incomplete: Int = 0)

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
    private var awareHost: AwareHost? = null
    private var client: WifiClient? = null

    // ---------- Wi-Fi Aware discovery (sender side) ----------

    private var aware: AwareScanner? = null
    private var lastAwarePeer: Int? = null
    private var lastAwareFiles: List<PickedFile>? = null

    /** The subscriber lives as long as the radar screen is open. */
    fun awareScanner(context: Context): AwareScanner =
        aware ?: AwareScanner(context.applicationContext).also { aware = it }

    fun stopAwareScan() {
        aware?.close(); aware = null
    }

    /** Sends over a Wi-Fi Aware data path to a peer found by [awareScanner]; the QR/Wi-Fi Direct path is unchanged. */
    fun startSendingAware(context: Context, peerId: Int, files: List<PickedFile>) {
        val app = context.applicationContext
        val scanner = aware ?: return
        val peer = scanner.peer(peerId) ?: return
        cancelJobOnly()
        lastSend = null
        lastAwarePeer = peerId
        lastAwareFiles = files
        lastDirection = Direction.SEND
        job = scope.launch {
            var peerName = peer.name
            var startedAt = 0L
            var lastStats: TransferStats? = null
            try {
                _state.value = TransferState.Preparing(TransferState.Step.PREPARE)
                val items = files.mapIndexed { i, f -> toSendItem(app, f, i + 1) }
                _state.value = TransferState.Preparing(TransferState.Step.WIFI)
                val (network, addr) = scanner.connect(peer)
                android.util.Log.i(TAG, "aware data path up: ${addr.hostAddress}:${peer.advert.p}")
                _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                var stats: TransferStats? = null
                val transport = DialTransport(network, addr.hostAddress ?: "", peer.advert.p, peer.advert.fp, peer.advert.e, address = addr)
                val sender = Sender(transport, peer.advert.t, deviceName(app), items, onPlanned = { s, p ->
                    stats = s; lastStats = s
                    if (startedAt == 0L) startedAt = System.currentTimeMillis()
                    if (p.isNotBlank()) peerName = p
                })
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        stats?.let { _state.value = TransferState.Running(Direction.SEND, peerName, "Aware", it.snapshot()) }
                    }
                }
                val result = try { sender.run() } finally { ticker.cancel() }
                record(app, Direction.SEND, peerName, "Aware", stats, startedAt, result.incompleteFiles.size)
                _state.value = TransferState.Finished(Direction.SEND, peerName, stats?.snapshot() ?: emptySnapshot(), result.incompleteFiles.size, sessionOpen = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "aware send failed", e)
                lastStats?.let { st -> record(app, Direction.SEND, peerName, "Aware", st, startedAt, st.snapshot().files.count { !it.skipped && it.done < it.size }) }
                _state.value = TransferState.Failed(describe(e), retryable = false)
            }
        }
    }
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
        lastReceiveFrom = null
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
                // Opportunistic Wi-Fi Aware publish; silently absent when the hardware or the P2P group forbids it.
                awareHost = AwareHost(app).also { ah ->
                    val ok = runCatching { ah.start(qr) }.getOrDefault(false)
                    android.util.Log.i(TAG, "wifi aware publish: ${if (ok) "on" else "unavailable"}")
                }

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
                activeReceiver = receiver
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
                    if (activeReceiver === receiver) activeReceiver = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "receive failed", e)
                _state.value = TransferState.Failed(describe(e), retryable = true)
            } finally {
                runCatching { awareHost?.stop() }
                awareHost = null
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
                        // Multipath: if a cable to the receiver is up, two USB streams join the Wi-Fi ones.
                        val extras: List<suspend () -> Link> =
                            if (UsbSession.isConnected) List(2) { { UsbSession.openStream() } } else emptyList()
                        if (extras.isNotEmpty()) android.util.Log.i(TAG, "multipath: wifi + ${extras.size} usb streams")
                        val sender = Sender(
                            DialTransport(network, qr.ip, qr.port, qr.fp, qr.enc), qr.token, deviceName(app), items,
                            onPlanned = { s, p ->
                                stats = s; lastStats = s
                                if (startedAt == 0L) startedAt = System.currentTimeMillis()
                                if (p.isNotBlank()) peer = p
                            },
                            extraLinks = extras,
                        )
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

    // ---------- Broadcast (one sender, many receivers) ----------

    private class PeerEntry(val name: String, val order: Int) {
        @Volatile var stats: TransferStats? = null
        @Volatile var status = PeerStatus.JOINED
        @Volatile var message = ""
        @Volatile var incomplete = 0
        @Volatile var startedAt = 0L
    }

    fun startBroadcast(context: Context, files: List<PickedFile>) {
        val app = context.applicationContext
        cancel()
        lastDirection = Direction.SEND
        lastSend = null
        job = scope.launch {
            try {
                _state.value = TransferState.Preparing(TransferState.Step.HOTSPOT)
                val settings = Settings.get(app)
                val h = WifiDirectHost(app).also { host = it }
                val group = h.start(if (settings.state.value.prefer5GHz) WifiDirectHost.Band.PREFER_5GHZ else WifiDirectHost.Band.AUTO)
                val token = (100000 + (Math.random() * 900000).toInt()).toString()
                val qr = QrPayload(
                    ssid = group.ssid, pass = group.passphrase, ip = group.ownerIp, port = Protocol.PORT, token = token,
                    name = deviceName(app), freq = group.frequencyMhz, fp = Tls.identity().fingerprint, host = true,
                )
                _state.value = TransferState.Preparing(TransferState.Step.PREPARE)
                val items = files.mapIndexed { i, f -> toSendItem(app, f, i + 1) }

                val peers = java.util.concurrent.ConcurrentHashMap<String, PeerEntry>()
                var order = 0
                fun publish() {
                    _state.value = TransferState.Broadcasting(
                        qr, group.band,
                        peers.values.sortedBy { it.order }.map { p ->
                            PeerProgress(p.name, p.name, p.stats?.snapshot(), p.status, p.message, p.incomplete)
                        },
                    )
                }
                publish()
                advertiser = BleAdvertiser(app).also { runCatching { it.start(qr) } }

                val server = BroadcastHost(
                    port = Protocol.PORT, token = token, deviceName = deviceName(app), items = items,
                    onJoined = { s, n -> peers[s] = PeerEntry(n, order++); publish() },
                    onPlanned = { s, _, st -> peers[s]?.let { it.stats = st; it.status = PeerStatus.RUNNING; it.startedAt = System.currentTimeMillis() }; publish() },
                    onDone = { s, r ->
                        peers[s]?.let { p ->
                            p.status = PeerStatus.DONE; p.incomplete = r.incompleteFiles.size
                            record(app, Direction.SEND, p.name, group.band, p.stats, p.startedAt, r.incompleteFiles.size)
                        }
                        publish()
                    },
                    onFailed = { s, e -> peers[s]?.let { it.status = PeerStatus.FAILED; it.message = describe(e) }; publish() },
                )
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        if (peers.values.any { it.status == PeerStatus.RUNNING }) publish()
                    }
                }
                try { server.run() } finally { ticker.cancel() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "broadcast failed", e)
                _state.value = TransferState.Failed(describe(e), retryable = false)
            } finally {
                runCatching { advertiser?.stop() }
                advertiser = null
                runCatching { host?.stop() }
                host = null
            }
        }
    }

    private var lastReceiveFrom: QrPayload? = null

    /** Broadcast mode, receiver side: join the sender's network and dial it. */
    fun startReceivingFrom(context: Context, qr: QrPayload) {
        val app = context.applicationContext
        // Our own Wi-Fi Direct group (from the Receive screen) must be fully gone before we can
        // join someone else's network: cancel() stops it asynchronously, so take it over here.
        val oldHost = host
        host = null
        cancel()
        lastDirection = Direction.RECEIVE
        lastReceiveFrom = qr
        job = scope.launch {
            val band = bandOf(qr.freq)
            var stats: TransferStats? = null
            var peer = qr.name
            var startedAt = 0L
            try {
                _state.value = TransferState.Preparing(TransferState.Step.WIFI)
                if (oldHost != null) {
                    runCatching { oldHost.stop() }
                    delay(1_500)
                    android.util.Log.i(TAG, "own group removed; joining ${qr.ssid}")
                }
                val settings = Settings.get(app)
                val c = WifiClient(app).also { client = it }
                val network = c.connect(qr.ssid, qr.pass)
                android.util.Log.i(TAG, "joined ${qr.ssid}; dialing ${qr.ip}:${qr.port}")
                _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                val receiver = Receiver(
                    port = Protocol.PORT, token = qr.token, deviceName = deviceName(app),
                    store = ReceiveStore(app, resume = settings.state.value.skipExisting),
                    onIncoming = { manifest ->
                        val s = settings.state.value
                        if (s.autoAcceptTrusted && manifest.senderName in s.trustedDevices) return@Receiver true
                        pendingSender = manifest.senderName
                        val gate = CompletableDeferred<Boolean>().also { acceptGate = it }
                        _state.value = TransferState.AwaitingAccept(manifest)
                        gate.await()
                    },
                    onPlanned = { s, p -> stats = s; peer = p; startedAt = System.currentTimeMillis() },
                    onSessionDone = { r ->
                        record(app, Direction.RECEIVE, peer, band, stats, startedAt, r.incompleteFiles.size)
                        _state.value = TransferState.Finished(Direction.RECEIVE, peer, stats?.snapshot() ?: emptySnapshot(), r.incompleteFiles.size, sessionOpen = false)
                    },
                    streams = settings.state.value.streams,
                    encrypted = qr.enc,
                )
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        stats?.let { _state.value = TransferState.Running(Direction.RECEIVE, peer, band, it.snapshot()) }
                    }
                }
                try { receiver.runClient(network, qr.ip, qr.port, qr.fp) } finally { ticker.cancel() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "receive-from failed", e)
                stats?.let { st -> record(app, Direction.RECEIVE, peer, band, st, startedAt, st.snapshot().files.count { !it.skipped && it.done < it.size }) }
                _state.value = TransferState.Failed(describe(e), retryable = true)
            } finally {
                client?.close(); client = null
            }
        }
    }

    // ---------- USB-C cable (2.0) ----------

    /** The receiver currently serving a session, so USB streams can join it (multipath). */
    @Volatile private var activeReceiver: Receiver? = null
    private var usbFeeder: Job? = null

    /**
     * Start listening for cable events: streams the remote opens go to the active
     * receiver; a "send" announcement starts a cable-only receive.
     */
    fun attachUsb(context: Context) {
        val app = context.applicationContext
        UsbSession.start(app)
        if (usbFeeder?.isActive == true) return
        usbFeeder = scope.launch {
            launch {
                UsbSession.incoming.collect { link ->
                    val r = activeReceiver
                    if (r != null) launch { r.admit(link) } else { android.util.Log.w(TAG, "usb stream with no receiver; closing"); runCatching { link.close() } }
                }
            }
            launch {
                UsbSession.remoteSend.collect { msg -> startUsbReceive(app, msg.token, msg.name) }
            }
        }
    }

    /** Sender side over the cable only: streams are mux channels; no TLS (physical link). */
    fun startUsbSend(context: Context, files: List<PickedFile>) {
        val app = context.applicationContext
        if (!UsbSession.isConnected) return
        cancelJobOnly()
        lastDirection = Direction.SEND
        lastSend = null
        job = scope.launch {
            var peer = UsbSession.peerName
            var startedAt = 0L
            var lastStats: TransferStats? = null
            try {
                _state.value = TransferState.Preparing(TransferState.Step.PREPARE)
                val items = files.mapIndexed { i, f -> toSendItem(app, f, i + 1) }
                val token = (100000 + (Math.random() * 900000).toInt()).toString()
                UsbSession.announceSend(token, deviceName(app))
                delay(300) // let the remote install its receiver before the first stream opens
                _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                val transport = object : SenderTransport {
                    override val writesHello = true
                    override suspend fun control() = UsbSession.openStream()
                    override suspend fun data() = UsbSession.openStream()
                }
                var stats: TransferStats? = null
                val sender = Sender(transport, token, deviceName(app), items, onPlanned = { s, p ->
                    stats = s; lastStats = s
                    if (startedAt == 0L) startedAt = System.currentTimeMillis()
                    if (p.isNotBlank()) peer = p
                })
                val ticker = launch {
                    while (isActive) { delay(500); stats?.let { _state.value = TransferState.Running(Direction.SEND, peer, "USB", it.snapshot()) } }
                }
                val result = try { sender.run() } finally { ticker.cancel() }
                record(app, Direction.SEND, peer, "USB", stats, startedAt, result.incompleteFiles.size)
                _state.value = TransferState.Finished(Direction.SEND, peer, stats?.snapshot() ?: emptySnapshot(), result.incompleteFiles.size, sessionOpen = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "usb send failed", e)
                lastStats?.let { st -> record(app, Direction.SEND, peer, "USB", st, startedAt, st.snapshot().files.count { !it.skipped && it.done < it.size }) }
                _state.value = TransferState.Failed(describe(e), retryable = false)
            }
        }
    }

    /** Receiver side over the cable only: the remote announced [token]; serve the streams it opens. */
    private fun startUsbReceive(app: Context, token: String, from: String) {
        cancelJobOnly()
        lastDirection = Direction.RECEIVE
        job = scope.launch {
            val settings = Settings.get(app)
            var stats: TransferStats? = null
            var peer = from
            var startedAt = 0L
            val done = CompletableDeferred<Unit>()
            try {
                _state.value = TransferState.Preparing(TransferState.Step.HANDSHAKE)
                val receiver = Receiver(
                    port = Protocol.PORT, token = token, deviceName = deviceName(app),
                    store = ReceiveStore(app, resume = settings.state.value.skipExisting),
                    onIncoming = { manifest ->
                        val s = settings.state.value
                        if (s.autoAcceptTrusted && manifest.senderName in s.trustedDevices) return@Receiver true
                        pendingSender = manifest.senderName
                        val gate = CompletableDeferred<Boolean>().also { acceptGate = it }
                        _state.value = TransferState.AwaitingAccept(manifest)
                        gate.await()
                    },
                    onPlanned = { s, p -> stats = s; peer = p; startedAt = System.currentTimeMillis() },
                    onSessionDone = { r ->
                        record(app, Direction.RECEIVE, peer, "USB", stats, startedAt, r.incompleteFiles.size)
                        _state.value = TransferState.Finished(Direction.RECEIVE, peer, stats?.snapshot() ?: emptySnapshot(), r.incompleteFiles.size, sessionOpen = false)
                        done.complete(Unit)
                    },
                    onDropped = { e -> _state.value = TransferState.Failed(describe(e), retryable = false); done.complete(Unit) },
                    streams = settings.state.value.streams,
                )
                activeReceiver = receiver
                val ticker = launch {
                    while (isActive) { delay(500); stats?.let { _state.value = TransferState.Running(Direction.RECEIVE, peer, "USB", it.snapshot()) } }
                }
                try { done.await() } finally { ticker.cancel() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "usb receive failed", e)
                _state.value = TransferState.Failed(describe(e), retryable = false)
            } finally {
                activeReceiver = null
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
                ?: lastAwarePeer?.let { id -> lastAwareFiles?.let { startSendingAware(context, id, it) } }
            Direction.RECEIVE -> lastReceiveFrom?.let { startReceivingFrom(context, it) } ?: startReceiving(context)
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
        runCatching { awareHost?.stop() }
        awareHost = null
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
