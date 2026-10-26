package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.domain.media.ProviderKey

/**
 * A loaded provider as the aggregation engine sees it. The extension contract stays [MainAPI];
 * how it was distributed (repository, plugin file, built-in) is not the engine's concern.
 */
data class RegisteredProvider(val id: String, val key: ProviderKey, val api: MainAPI) {
    val language: String get() = api.lang
}

object ProviderIds {
    /**
     * Stable across domain changes: plugin file, implementation class and display name.
     * `mainUrl` is mutable (user overrides, mirrors) and display names are not unique.
     */
    fun of(api: MainAPI): String {
        val plugin = api.sourcePlugin?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "builtin"
        return "$plugin|${api.javaClass.name}|${api.name}"
    }
}

interface SourceRegistry {
    fun providers(): List<RegisteredProvider>

    /**
     * Saved bindings resolve by stable ID, then by the exact name/domain pair, then by a unique
     * name. An ambiguous name never routes an opaque payload to an arbitrary extension.
     */
    fun resolve(key: ProviderKey?, id: String? = null): RegisteredProvider? {
        val all = providers()
        id?.let { wanted -> all.firstOrNull { it.id == wanted }?.let { return it } }
        key ?: return null
        all.firstOrNull { it.key == key }?.let { return it }
        return all.filter { it.key.name == key.name }.singleOrNull()
    }
}

/** Providers registered through [com.lagradost.cloudstream3.plugins.BasePlugin.registerMainAPI]. */
object ApiHolderSourceRegistry : SourceRegistry {
    override fun providers(): List<RegisteredProvider> = APIHolder.apis.withLock {
        APIHolder.apis.map { RegisteredProvider(ProviderIds.of(it), ProviderKey(it.name, it.mainUrl), it) }
    }
}
