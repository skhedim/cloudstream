package com.lagradost.cloudstream3.ui.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginWrapper
import java.io.File

/** A small reviewed default, rather than trusting a package's self-declared Movie tag. */
object CatalogCuration {
    val videoTypes = com.lagradost.cloudstream3.ui.home.aggregation.HomeFeedPolicy.videoTypes
    private fun key(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private val excludedOrigins = listOf("rvrbeast76allforurepo", "errorcode26ayucloudstreamgames", "flummoxgamerflummoxrepo")
    private val utilities = setOf("megaprovider", "m3uplayer", "m3uplaylistplayerprovider", "jellyfin", "stremioaddon", "stremiox",
        "simpmusic", "smarttube", "skillshareprovider", "goldenaudiobook", "goldenaudiobooks", "librivoxaudiobook", "comix")
    /**
     * Recommended extensions, from the provider survey (ProviderSurveyInstrumentedTest, 47 TMDB
     * titles, 2026-10-26): every playable link checked for reachability and real duration.
     * - Movix: every title playable in 720p+; Frembed (yorik100) 83 %, Xalaflix 72 % as backups.
     * - Frembed FR (mouradchaouche): no playable links, but it finds every title with TMDB IDs and
     *   feeds the home's popular rows; opening one of its cards finds the work at the providers
     *   above (completion).
     * French catalogues only for now; original-version and anime extensions are not reviewed yet.
     * Not recommended: AfterDark (no playable links, and its site shows a verification page
     * during playback, which breaks invisible providers), FsMirrorLol (no title the others lack,
     * mostly 360p, its movie pages now declare series and its series pages have no title), and
     * Coflix, Flemmix and FrenchAnime, which found nothing.
     */
    private val reviewed = mapOf(
        "movix" to listOf("kraptor123cskarma"),
        "frembed" to listOf("yorik100cloudstream", "mouradchaouchecloudstreamfrenchrepo"),
        "xalaflix" to listOf("yorik100cloudstream"),
    )
    fun blocked(name: String, origin: String, version: Int = Int.MAX_VALUE): Boolean =
        excludedOrigins.any { it in key(origin) } ||
            (name.equals("FStream", true) && version in 1..16)
    fun excludedFromCatalogue(name: String, origin: String, version: Int): Boolean =
        blocked(name, origin, version) || key(name) in utilities
    fun blocked(data: PluginData) = blocked(data.internalName, data.url.orEmpty() + data.filePath, data.version)
    fun blockedClass(className: String) = className.startsWith("com.allforu.", true)
    fun recommended(name: String, origin: String): Boolean = reviewedOrigin(name, origin) != null
    /** The reviewed origin matching [origin]: two reviewed packages may share a name (both Frembeds). */
    fun reviewedOrigin(name: String, origin: String): String? = reviewed[key(name)]?.firstOrNull { it in key(origin) }
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
