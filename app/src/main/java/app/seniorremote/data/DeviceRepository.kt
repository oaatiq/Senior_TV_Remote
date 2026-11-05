package app.seniorremote.data

import android.content.Context
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** JSON form of the library, including upgrade from the earlier single-TV format. */
internal object TvLibraryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(library: TvLibrary): String = json.encodeToString(TvLibrary.serializer(), library)

    fun decode(text: String): TvLibrary? =
        runCatching { json.decodeFromString(TvLibrary.serializer(), text) }.getOrNull()

    /** Reads a single saved TV written by version 1, which had no id; its credential ref is unique. */
    fun decodeLegacy(text: String): TvLibrary? = runCatching {
        val old = json.decodeFromString(SavedTv.serializer(), text)
        val tv = old.copy(id = old.id.ifEmpty { old.credentialRef })
        TvLibrary(listOf(tv), tv.id)
    }.getOrNull()
}

/**
 * Persists the saved TVs (identity and configuration, no secrets) in private app storage.
 * Loaded synchronously at construction so the remote can render immediately.
 */
class PrefsDeviceRepository(context: Context) : DeviceRepository {
    private val prefs = context.getSharedPreferences("saved_tv", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _library = MutableStateFlow(load())
    override val library: StateFlow<TvLibrary> = _library.asStateFlow()

    private fun load(): TvLibrary {
        prefs.getString(KEY, null)?.let(TvLibraryCodec::decode)?.let { return it }
        val migrated = prefs.getString(LEGACY_KEY, null)?.let(TvLibraryCodec::decodeLegacy) ?: return TvLibrary()
        prefs.edit().putString(KEY, TvLibraryCodec.encode(migrated)).remove(LEGACY_KEY).apply()
        return migrated
    }

    override suspend fun edit(transform: (TvLibrary) -> TvLibrary) = mutex.withLock {
        val next = transform(_library.value)
        withContext(Dispatchers.IO) { prefs.edit().putString(KEY, TvLibraryCodec.encode(next)).commit() }
        _library.value = next
    }

    private companion object {
        const val KEY = "library"
        const val LEGACY_KEY = "tv"
    }
}

class InMemoryDeviceRepository(initial: TvLibrary = TvLibrary()) : DeviceRepository {
    private val _library = MutableStateFlow(initial)
    override val library: StateFlow<TvLibrary> = _library.asStateFlow()

    override suspend fun edit(transform: (TvLibrary) -> TvLibrary) {
        synchronized(_library) { _library.value = transform(_library.value) }
    }
}
