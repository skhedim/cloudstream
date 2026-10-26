package com.lagradost.cloudstream3.ui.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginWrapper
import java.io.File

/** A small reviewed default, rather than trusting a package's self-declared Movie tag. */
object CatalogCuration {
    val videoTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA,
        TvType.Cartoon, TvType.Documentary, TvType.AsianDrama, TvType.Video)
    private fun key(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private val excludedOrigins = listOf("rvrbeast76allforurepo", "errorcode26ayucloudstreamgames", "flummoxgamerflummoxrepo")
    private val utilities = setOf("megaprovider", "m3uplayer", "m3uplaylistplayerprovider", "jellyfin", "stremioaddon", "stremiox",
        "simpmusic", "smarttube", "skillshareprovider", "goldenaudiobook", "goldenaudiobooks", "librivoxaudiobook", "comix")
    /**
     * Recommended extensions, from the provider survey (ProviderSurveyInstrumentedTest, 47 TMDB
     * titles, 2026-10-26): every playable link checked for reachability and real duration.
     * - Movix, Frembed (yorik100): ~100 % of titles playable, mostly 720p+; Xalaflix ~72 %;
     *   FsMirrorLol ~64 % (mostly 360p, explicit VF/VOST).
     * - AfterDark, Frembed FR (mouradchaouche): no playable links, but they find every title with
     *   TMDB IDs: kept for matching and the home catalogue; the engine never waits on their links.
     * - CineStream: the original-version fallback.
     * Coflix, Flemmix and FrenchAnime found nothing in the survey and are no longer recommended.
     */
    private val reviewed = mapOf(
        "cinestream" to listOf("saurabhkaperwancsx"),
        "movix" to listOf("kraptor123cskarma"),
        "frembed" to listOf("yorik100cloudstream", "mouradchaouchecloudstreamfrenchrepo"),
        "xalaflix" to listOf("yorik100cloudstream"),
        "afterdark" to listOf("yorik100cloudstream"),
        "fsmirrorlol" to listOf("mouradchaouchecloudstreamfrenchrepo"),
    )
    fun blocked(name: String, origin: String, version: Int = Int.MAX_VALUE): Boolean =
        excludedOrigins.any { it in key(origin) } ||
            (name.equals("FStream", true) && version in 1..16)
    fun excludedFromCatalogue(name: String, origin: String, version: Int): Boolean =
        blocked(name, origin, version) || key(name) in utilities
    fun blocked(data: PluginData) = blocked(data.internalName, data.url.orEmpty() + data.filePath, data.version)
    fun blockedClass(className: String) = className.startsWith("com.allforu.", true)
    fun recommended(name: String, origin: String): Boolean = reviewed[key(name)]?.any { it in key(origin) } == true
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
