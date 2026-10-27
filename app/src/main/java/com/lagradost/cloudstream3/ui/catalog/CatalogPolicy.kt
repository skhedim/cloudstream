package com.lagradost.cloudstream3.ui.catalog

import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.PluginWrapper

/** Selection is deliberately independent of the extension API and of audio track preferences. */
data class CatalogSelection(val languages: Set<String>, val types: Set<TvType>, val recommendedOnly: Boolean = true) {
    fun acceptsLanguage(language: String?) = AllLanguagesName in languages ||
        language?.lowercase()?.substringBefore('-') in languages.map { it.lowercase().substringBefore('-') }
    fun accepts(plugin: PluginWrapper): Boolean {
        val p = plugin.plugin
        // Beta (3) is accepted only for reviewed packages, which the provider survey measured working.
        val status = p.status in 1..2 || (p.status == 3 && CatalogCuration.recommended(plugin))
        if (!status || p.apiVersion != 1 || CatalogCuration.excludedFromCatalogue(p.internalName,
                plugin.repositoryData.url + p.url, p.version)) return false
        // This published version links against a removed core auth class and cannot load.
        if (p.internalName.equals("FStream", true) && p.version <= 16) return false
        val media = p.tvTypes.orEmpty().mapNotNull { name -> TvType.entries.find { it.name.equals(name, true) } }
        if (recommendedOnly && !CatalogCuration.recommended(plugin)) return false
        // Original-version and anime complements serve a French-speaking viewer whatever their language.
        return (acceptsLanguage(p.language) || CatalogCuration.complement(CatalogCuration.reviewed(plugin)?.role)) &&
            media.any { it in types && it in CatalogCuration.videoTypes }
    }

    fun normalProvider(api: MainAPI): Boolean = CatalogCuration.allowedProvider(api, recommendedOnly) &&
        (acceptsLanguage(api.lang) || CatalogCuration.complement(api)) && CatalogCuration.usesApi(api) &&
        api.supportedTypes.any { it in types && it in CatalogCuration.videoTypes }
    /** Home rows come from the catalogues only; complements are searched and played. */
    fun homeProvider(api: MainAPI): Boolean = normalProvider(api) && !CatalogCuration.complement(api)
    fun normalItem(item: SearchResponse): Boolean = item.type == null ||
        (item.type in types && item.type in CatalogCuration.videoTypes)

    fun plan(plugins: List<PluginWrapper>, installedUrls: Set<String> = emptySet()): List<PluginWrapper> =
        // Mirrors of a package are installed once; reviewed packages that share a name are distinct.
        plugins.filter { accepts(it) }.groupBy { it.plugin.internalName.lowercase() + "|" + CatalogCuration.reviewedOrigin(it).orEmpty() }.values.map { copies ->
            copies.sortedWith(compareByDescending<PluginWrapper> { it.plugin.url in installedUrls }
                .thenBy { it.plugin.status }.thenByDescending { it.plugin.version }).first()
        }.distinctBy { it.plugin.url }.sortedBy { it.plugin.name.lowercase() }
}

object CatalogIndex {
    const val MEGA_REPOSITORY = "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json"
    // The same public index used by MegaProvider; resolve it before applying language filters.
    const val INDEX = "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
    fun parse(text: String): List<String> = mapper.readTree(text).also { require(it.isArray) }
        .mapNotNull { node -> if (node.isTextual) node.asText() else node.get("url")?.asText() }
        .filter { it.startsWith("https://") }.distinct()
}
