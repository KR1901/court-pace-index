package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.model.CourtRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Court records live in a single JSON document in the app's private storage. No network,
 * no account: a court measurement is useful offline, on a court, with no signal.
 */
class CourtRepository(private val context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val _courts = MutableStateFlow<List<CourtRecord>>(emptyList())
    val courts: StateFlow<List<CourtRecord>> = _courts.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _courts.value = readFromDisk().sortedByDescending { it.createdAt }
        }
    }

    suspend fun upsert(record: CourtRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _courts.value.filterNot { it.id == record.id } + record
            persist(next)
            _courts.value = next.sortedByDescending { it.createdAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _courts.value.filterNot { it.id == id }
            persist(next)
            _courts.value = next.sortedByDescending { it.createdAt }
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(_courts.value)
    }

    /** Returns how many records were added or replaced. Throws on malformed input. */
    suspend fun importJson(payload: String): Int = withContext(Dispatchers.IO) {
        val incoming = json.decodeFromString<List<CourtRecord>>(payload)
        mutex.withLock {
            val merged = (_courts.value + incoming).associateBy { it.id }.values.toList()
            persist(merged)
            _courts.value = merged.sortedByDescending { it.createdAt }
        }
        incoming.size
    }

    private fun persist(records: List<CourtRecord>) {
        file.writeText(json.encodeToString(records.sortedByDescending { it.createdAt }))
    }

    private fun readFromDisk(): List<CourtRecord> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<CourtRecord>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private companion object {
        const val FILE_NAME = "courts.json"
    }
}
