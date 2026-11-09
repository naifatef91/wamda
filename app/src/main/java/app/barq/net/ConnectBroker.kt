package app.barq.net

import app.barq.engine.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** The receiver's answer to "may this device connect?". */
enum class Decision { APPROVED, DECLINED, TIMEOUT }

/**
 * Receiver side of the two-step pairing for one transport (BLE today). A requester writes a
 * request, then reads the reply; the reply stays "pending" until the user decides, and only an
 * approved reply contains the network details, sealed for that requester alone.
 *
 * No Android types here, so every path (approve, decline, silence, cut connection, flooding)
 * runs in plain unit tests.
 */
class ConnectBroker(
    private val scope: CoroutineScope,
    private val qr: QrPayload,
    private val decide: suspend (name: String, fp: String) -> Decision,
    /** Called once per approval, before the sealed reply becomes readable (e.g. stop advertising). */
    private val onApproved: () -> Unit = {},
    private val maxPending: Int = 4,
    private val decisionTimeoutMs: Long = 30_000,
) {
    private class Entry(val request: PairingWire.Request, @Volatile var reply: ByteArray) { var job: Job? = null }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** A request written by [deviceKey]. False when it is malformed or the broker is full. */
    fun onRequest(deviceKey: String, bytes: ByteArray): Boolean {
        val request = PairingWire.decodeRequest(bytes) ?: return false
        if (entries.size >= maxPending && !entries.containsKey(deviceKey)) return false
        val entry = Entry(request, PairingWire.status(PairingWire.ST_PENDING))
        entries.put(deviceKey, entry)?.job?.cancel() // a repeated request replaces the earlier one
        entry.job = scope.launch {
            val answer = withTimeoutOrNull(decisionTimeoutMs) { decide(request.name, request.fp) } ?: Decision.TIMEOUT
            entry.reply = when (answer) {
                Decision.APPROVED -> {
                    onApproved()
                    PairingWire.approved(PairingCrypto.newEphemeral(), request, qr.encode().toByteArray(Charsets.UTF_8))
                }
                Decision.DECLINED -> PairingWire.status(PairingWire.ST_DECLINED)
                Decision.TIMEOUT -> PairingWire.status(PairingWire.ST_TIMEOUT)
            }
        }
        return true
    }

    /** What a read of the reply characteristic returns right now for [deviceKey]. */
    fun reply(deviceKey: String): ByteArray = entries[deviceKey]?.reply ?: PairingWire.status(PairingWire.ST_BAD)

    /** The requester went away: stop waiting for a decision that nobody needs any more. */
    fun forget(deviceKey: String) {
        entries.remove(deviceKey)?.job?.cancel()
    }

    fun clear() {
        entries.keys.toList().forEach { forget(it) }
    }
}
