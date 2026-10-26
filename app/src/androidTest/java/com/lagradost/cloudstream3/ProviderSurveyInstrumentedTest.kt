package com.lagradost.cloudstream3

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.aggregation.DirectProviderGateway
import com.lagradost.cloudstream4.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.aggregation.SearchMediaMetadata
import com.lagradost.cloudstream4.aggregation.StreamInspector
import com.lagradost.cloudstream4.domain.media.EpisodeNumbering
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Measures installed extensions on a reference corpus (assets/provider-survey-corpus.json):
 * availability, identity, link count, reachability, verified quality, language and real stream
 * duration versus the TMDB runtime. Opt-in: `-e plugins Movix,Frembed,...` and the class filter.
 * Results: files/provider-survey/<run>.json (no payloads or URLs, only hosts).
 */
@RunWith(AndroidJUnit4::class)
class ProviderSurveyInstrumentedTest {
    private data class Work(val kind: String, val tmdb: Long, val imdb: String?, val title: String, val original: String,
        val year: Int, val runtime: Int?)

    private fun corpus(): List<Work> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("provider-survey-corpus.json").bufferedReader().readText()
        val array = JSONArray(text)
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            Work(it.getString("kind"), it.getLong("tmdb"), it.optString("imdb").takeIf(String::isNotBlank),
                it.getString("title"), it.getString("original"), it.getInt("year"),
                if (it.isNull("runtime")) null else it.getInt("runtime"))
        }
    }

    @Test fun survey() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val wanted = args.getString("plugins")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@runBlocking
        val only = args.getString("titles")?.toIntOrNull()
        val run = args.getString("run") ?: System.currentTimeMillis().toString()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = PluginManager.getPluginsOnline()
        // The catalogue normally refuses extensions outside its reviewed list: lift that for the survey only.
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val recommendedOnly = prefs.getBoolean(com.lagradost.cloudstream3.ui.catalog.CatalogPreferences.RECOMMENDED_ONLY, true)
        prefs.edit().putBoolean(com.lagradost.cloudstream3.ui.catalog.CatalogPreferences.RECOMMENDED_ONLY, false).commit()
        try { surveyLoaded(context, wanted, only, run, installed) } finally {
            prefs.edit().putBoolean(com.lagradost.cloudstream3.ui.catalog.CatalogPreferences.RECOMMENDED_ONLY, recommendedOnly).commit()
        }
    }

    private suspend fun surveyLoaded(context: Context, wanted: List<String>, only: Int?, run: String,
        installed: Array<com.lagradost.cloudstream3.plugins.PluginData>) {
        for (name in wanted) {
            // The same internal name can come from several repositories (two Frembed builds).
            val copies = installed.filter { it.internalName.equals(name, true) }.distinctBy { it.filePath }
            if (copies.isEmpty()) Log.w(TAG, "Not installed: $name")
            copies.forEach { data ->
                runCatching { PluginManager.loadPlugin(context, File(data.filePath), data) }
                    .onFailure { Log.e(TAG, "Load failed $name", it) }
            }
        }
        val apis = APIHolder.apis.withLock { APIHolder.apis.toList() }
            .filter { api -> wanted.any { api.sourcePlugin?.contains(it, true) == true } }
        Log.i(TAG, "Surveying ${apis.map { it.name }}")
        val works = corpus().let { if (only != null) it.take(only) else it }
        val out = File(context.filesDir, "provider-survey").apply { mkdirs() }.resolve("$run.json")
        val results = JSONArray()
        val global = Semaphore(InstrumentationRegistry.getArguments().getString("concurrency")?.toIntOrNull() ?: 16)
        coroutineScope {
            apis.map { api ->
                async {
                    // One provider at a time per site: sequential over the corpus.
                    for (work in works) {
                        val row = global.withPermit { survey(api, work) }
                        synchronized(results) {
                            results.put(row)
                            out.writeText(JSONObject().put("run", run).put("providers", JSONArray(apis.map { it.name }))
                                .put("results", results).toString())
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "Survey done: ${results.length()} rows in $out")
    }

    private suspend fun survey(api: MainAPI, work: Work): JSONObject {
        val row = JSONObject().put("provider", api.name).put("lang", api.lang).put("class", api.javaClass.name)
            .put("tmdb", work.tmdb).put("title", work.title).put("kind", work.kind).put("year", work.year)
        val started = System.currentTimeMillis()
        val key = ProviderKey(api.name, api.mainUrl)
        val ids = setOfNotNull(
            ExternalMediaId(if (work.kind == "movie") ExternalIdNamespace.TmdbMovie else ExternalIdNamespace.TmdbTv, work.tmdb.toString()),
            work.imdb?.let { ExternalMediaId(ExternalIdNamespace.Imdb, it) })
        val titles = setOf(work.title, work.original).map(MediaAggregator::matchKey).toSet()
        val family = if (work.kind == "movie") "movie" else "series"
        try {
            // 1. Search: localized title, then original title.
            var match: SearchResponse? = null
            var how = "none"
            var searched = 0
            for (query in listOf(work.title, work.original).distinct()) {
                val page = withTimeoutOrNull(25_000) { DirectProviderGateway.search(api, query, 1) } ?: continue
                searched += page.items.size
                val candidates = page.items.map { it to SearchMediaMapper.candidate(key, it, api.javaClass.name) }
                val byId = candidates.firstOrNull { (_, c) -> c.externalIds.any { it in ids } }
                val byTitle = candidates.filter { (_, c) ->
                    MediaAggregator.titles(c).any { it in titles } &&
                        (MediaAggregator.family(c.kind) == null || MediaAggregator.family(c.kind) == family) &&
                        (c.year == null || abs(c.year!! - work.year) <= 1) && c.externalIds.none { id ->
                            ids.any { it.namespace == id.namespace && it != id } }
                }.sortedBy { (_, c) -> c.year?.let { abs(it - work.year) } ?: 5 }
                val chosen = byId ?: byTitle.firstOrNull()
                if (chosen != null) { match = chosen.first; how = if (byId != null) "id" else "title"; break }
            }
            row.put("searchResults", searched).put("found", match != null).put("matchedBy", how)
                .put("searchMs", System.currentTimeMillis() - started)
            match ?: return row
            // 2. Detail page and identity.
            val loadStarted = System.currentTimeMillis()
            val response = withTimeoutOrNull(30_000) { DirectProviderGateway.load(api, match.url) }
            row.put("loadMs", System.currentTimeMillis() - loadStarted).put("loaded", response != null)
            response ?: return row
            val meta = SearchMediaMetadata.of(response)
            val conflict = meta.ids.any { id -> ids.any { it.namespace == id.namespace && it != id } }
            row.put("detailTitle", response.name).put("detailYear", response.year)
                .put("detailIdMatch", meta.ids.any { it in ids }).put("detailIdConflict", conflict)
                .put("detailDuration", response.duration)
            // 3. The movie, or S01E01 (episode 1 for flat anime lists).
            val (data, track) = when (response) {
                is MovieLoadResponse -> response.dataUrl to "None"
                is TvSeriesLoadResponse -> firstEpisode(response.episodes)?.data to "None"
                is AnimeLoadResponse -> response.episodes.entries.firstNotNullOfOrNull { (dub, list) ->
                    firstEpisode(list)?.let { it.data to dub.name } } ?: (null to "None")
                else -> null to "None"
            }
            row.put("track", track)
            if (data.isNullOrBlank()) return row.put("episodeFound", false)
            row.put("episodeFound", true)
            // 4. Links.
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            var firstLinkMs: Long? = null
            val linksStarted = System.currentTimeMillis()
            val ok = withTimeoutOrNull(45_000) {
                DirectProviderGateway.loadLinks(api, data, false, { synchronized(subtitles) { subtitles += it } }) { link ->
                    synchronized(links) {
                        if (firstLinkMs == null) firstLinkMs = System.currentTimeMillis() - linksStarted
                        links += link
                    }
                }
            }
            val all = synchronized(links) { links.toList() }
            row.put("linksMs", System.currentTimeMillis() - linksStarted).put("linksTimedOut", ok == null)
                .put("firstLinkMs", firstLinkMs).put("links", all.size)
                .put("subtitles", JSONArray(synchronized(subtitles) { subtitles.map { it.lang } }.distinct()))
            // 5. Inspect up to 8 links, one per host first.
            val binding = EpisodeSourceBinding(api.name, api.mainUrl, match.url, data, track, api.lang)
            val sample = all.distinctBy { runCatching { java.net.URI(it.url).host }.getOrNull() to it.quality }
                .sortedByDescending { PlaybackSources.quality(it) }.take(6)
            val inspections = coroutineScope { sample.map { link -> async { link to StreamInspector.inspect(link, 7_000) } }.awaitAll() }
            val inspected = JSONArray()
            for ((link, inspection) in inspections) {
                val expected = work.runtime?.let { it * 60.0 }
                inspected.put(JSONObject()
                    .put("host", runCatching { java.net.URI(link.url).host }.getOrNull())
                    .put("source", link.source).put("name", link.name).put("type", link.type.name)
                    .put("declaredQuality", link.quality)
                    .put("version", PlaybackSources.version(link.name, binding).name)
                    .put("reachable", inspection.reachable).put("http", inspection.httpCode)
                    .put("height", inspection.height).put("audio", JSONArray(inspection.audioLanguages.toList()))
                    .put("subs", JSONArray(inspection.subtitleLanguages.toList()))
                    .put("durationSec", inspection.durationSeconds)
                    .put("durationRatio", if (expected != null && inspection.durationSeconds != null) inspection.durationSeconds!! / expected else null)
                    .put("sizeMB", inspection.sizeBytes?.let { it / 1_000_000 })
                    .put("error", inspection.error))
            }
            row.put("inspected", inspected)
        } catch (t: Throwable) {
            row.put("error", "${t.javaClass.simpleName}: ${t.message?.take(120)}")
        }
        return row.put("totalMs", System.currentTimeMillis() - started)
    }

    private fun firstEpisode(episodes: List<Episode>): Episode? =
        episodes.firstOrNull { (it.season ?: 1) == 1 && it.episode == 1 }
            ?: episodes.firstOrNull { EpisodeNumbering.fromLabel(it.name)?.let { n -> (n.season ?: 1) == 1 && n.episode == 1 } == true }
            ?: episodes.minByOrNull { (it.season ?: 1) * 10_000 + (it.episode ?: 9_999) }

    companion object { private const val TAG = "ProviderSurvey" }
}
