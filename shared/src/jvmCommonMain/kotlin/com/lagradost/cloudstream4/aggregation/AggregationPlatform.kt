package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.mapper
import java.util.concurrent.ConcurrentHashMap

/** Small persistent key/value storage. Values are serialized by the platform (Jackson on Android). */
interface KeyValueStore {
    fun <T : Any> get(key: String, type: Class<T>): T?
    fun set(key: String, value: Any)
}

inline fun <reified T : Any> KeyValueStore.get(key: String): T? = get(key, T::class.java)

/** Round-trips values through JSON, like the persistent stores, so tests see the same behaviour. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun <T : Any> get(key: String, type: Class<T>): T? =
        values[key]?.let { runCatching { mapper.readValue(it, type) }.getOrNull() }
    override fun set(key: String, value: Any) { values[key] = mapper.writeValueAsString(value) }
}

/**
 * What a platform supplies to the shared aggregation engine. Android installs its stores and
 * extension wrappers at startup; the desktop app installs its own. Defaults are usable in tests.
 */
object AggregationPlatform {
    @Volatile var store: KeyValueStore = InMemoryKeyValueStore()
    @Volatile var registry: SourceRegistry = ApiHolderSourceRegistry
    @Volatile var gateway: ProviderGateway = DirectProviderGateway
    /** Decodes small poster thumbnails; null disables visual artwork corroboration. */
    @Volatile var artworkSampler: ArtworkSampler? = null
    /** Language tag used for metadata lookups, e.g. `fr-FR`. */
    @Volatile var metadataLanguage: () -> String = { java.util.Locale.getDefault().toLanguageTag() }
    @Volatile var debug: Boolean = false
}
