package app.barq.data

import android.content.Context
import app.barq.Direction
import app.barq.engine.wireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** A received .apk or .apks the user can install from the history or the finish screen. */
@Serializable
data class Installable(val name: String, val uri: String)

@Serializable
data class TransferRecord(
    val id: String,
    val direction: Direction,
    val peer: String,
    val fileCount: Int,
    val bytes: Long,
    val avgBps: Double,
    val band: String,
    val startedAt: Long,
    val durationMs: Long,
    val incomplete: Int = 0,
    val installables: List<Installable> = emptyList(),
) {
    val complete: Boolean get() = incomplete == 0
}

/** Past transfers, newest first, kept as one JSON file in app storage. */
class HistoryStore private constructor(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    @Synchronized
    fun add(record: TransferRecord) {
        val next = (listOf(record) + _records.value).take(MAX)
        _records.value = next
        runCatching { file.writeText(wireJson.encodeToString(ListSerializer(TransferRecord.serializer()), next)) }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        file.delete()
    }

    private fun load(): List<TransferRecord> = runCatching {
        if (!file.exists()) emptyList()
        else wireJson.decodeFromString(ListSerializer(TransferRecord.serializer()), file.readText())
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 200

        @Volatile private var instance: HistoryStore? = null
        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context.applicationContext).also { instance = it } }
    }
}
