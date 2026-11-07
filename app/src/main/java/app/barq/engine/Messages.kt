package app.barq.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Hello(
    val magic: Int = Protocol.MAGIC,
    val version: Int = Protocol.VERSION,
    val token: String,
    val role: Int,
    val deviceName: String,
)

@Serializable
data class FileEntry(
    val id: Int,
    val name: String,
    val size: Long,
    val mime: String? = null,
    val modified: Long = 0L,
)

@Serializable
data class TransferManifest(
    val sessionId: String,
    val senderName: String,
    val files: List<FileEntry>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/**
 * Receiver's answer to a manifest. [missing] maps fileId to the chunk indexes
 * still needed. A file absent from the map is already complete on the receiver
 * and is skipped entirely.
 */
@Serializable
data class NeedResponse(
    val accepted: Boolean,
    val streams: Int = Protocol.DEFAULT_STREAMS,
    val receiverName: String = "",
    val missing: Map<Int, List<Int>> = emptyMap(),
    val reason: String? = null,
)

@Serializable
data class DoneMessage(val bytesSent: Long)

@Serializable
data class AckMessage(val ok: Boolean, val incomplete: List<Int> = emptyList())

/** Content of the QR code shown by the receiver. */
@Serializable
data class QrPayload(
    val v: Int = 1,
    val ssid: String,
    val pass: String,
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val freq: Int = 0,
    /** Base64url SHA-256 of the receiver's TLS certificate; the sender pins it. */
    val fp: String = "",
) {
    fun encode(): String = "barq:" + wireJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): QrPayload? {
            if (!text.startsWith("barq:")) return null
            return runCatching { wireJson.decodeFromString(serializer(), text.removePrefix("barq:")) }.getOrNull()
        }
    }
}
