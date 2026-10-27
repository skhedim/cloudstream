package com.lagradost.cloudstream3.ui.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream4.aggregation.ExtensionRole
import com.lagradost.cloudstream4.aggregation.ReviewedExtension
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import java.io.File

/** A small reviewed default, rather than trusting a package's self-declared Movie tag. */
object CatalogCuration {
    val videoTypes = com.lagradost.cloudstream3.ui.home.aggregation.HomeFeedPolicy.videoTypes
    private fun key(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private val excludedOrigins = listOf("rvrbeast76allforurepo", "errorcode26ayucloudstreamgames", "flummoxgamerflummoxrepo")
    private val utilities = setOf("megaprovider", "m3uplayer", "m3uplaylistplayerprovider", "jellyfin", "stremioaddon", "stremiox",
        "simpmusic", "smarttube", "skillshareprovider", "goldenaudiobook", "goldenaudiobooks", "librivoxaudiobook", "comix")
    fun blocked(name: String, origin: String, version: Int = Int.MAX_VALUE): Boolean =
        excludedOrigins.any { it in key(origin) } ||
            (name.equals("FStream", true) && version in 1..16)
    fun excludedFromCatalogue(name: String, origin: String, version: Int): Boolean =
        blocked(name, origin, version) || key(name) in utilities
    fun blocked(data: PluginData) = blocked(data.internalName, data.url.orEmpty() + data.filePath, data.version)
    fun blockedClass(className: String) = className.startsWith("com.allforu.", true)
    /** Reviewed extensions (shared with the desktop app): see [ReviewedExtensions] and docs/catalog-setup.md. */
    fun recommended(name: String, origin: String): Boolean = ReviewedExtensions.find(name, origin) != null
    /** The reviewed origin matching [origin]: two reviewed packages may share a name (both Frembeds). */
    fun reviewedOrigin(name: String, origin: String): String? = ReviewedExtensions.find(name, origin)?.origin
    fun reviewed(plugin: PluginWrapper): ReviewedExtension? =
        ReviewedExtensions.find(plugin.plugin.internalName, plugin.repositoryData.url + plugin.plugin.url)
    /** The reviewed extension a loaded provider comes from. */
    fun reviewed(api: MainAPI): ReviewedExtension? = api.sourcePlugin?.let { path ->
        ReviewedExtensions.find(File(path).nameWithoutExtension.substringBeforeLast('.'), path)
    }
    /** Original-version and anime extensions, whatever their language: searched and played, never on the home. */
    fun complement(role: ExtensionRole?): Boolean = role != null && role != ExtensionRole.Catalogue
    fun complement(api: MainAPI): Boolean = complement(reviewed(api)?.role)
    /** Extensions registering several providers: only the reviewed ones are used. */
    fun usesApi(api: MainAPI): Boolean = reviewed(api)?.let { ReviewedExtensions.usesApi(it, api.name) } ?: true
    fun reviewedOrigin(plugin: PluginWrapper): String? = reviewedOrigin(plugin.plugin.internalName,
        plugin.repositoryData.url + plugin.plugin.url)
    fun recommended(plugin: PluginWrapper) = recommended(plugin.plugin.internalName,
        plugin.repositoryData.url + plugin.plugin.url)
    fun recommendedPath(path: String): Boolean {
        val name = File(path).nameWithoutExtension.substringBeforeLast('.')
        return recommended(name, path)
    }
    fun allowedProvider(api: MainAPI, recommendedOnly: Boolean): Boolean {
        val path = api.sourcePlugin ?: return true // Core/in-process providers, including test fixtures.
        return !blocked(File(path).nameWithoutExtension.substringBeforeLast('.'), path) &&
            (!recommendedOnly || recommendedPath(path))
    }
}
