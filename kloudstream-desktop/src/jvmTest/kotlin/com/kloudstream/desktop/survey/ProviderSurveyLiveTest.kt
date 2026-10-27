package com.kloudstream.desktop.survey

import android.content.Context
import com.fasterxml.jackson.module.kotlin.readValue
import com.kloudstream.desktop.extensions.DesktopContext
import com.kloudstream.desktop.extensions.DexTranslator
import com.kloudstream.desktop.extensions.ExtensionPrompts
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import kotlin.math.abs

/**
 * The provider survey on the desktop runtime (same measures as Android's
 * ProviderSurveyInstrumentedTest, without tying up a TV): for each candidate extension and work of
 * a small corpus, search, identity, the film or first episode, links, and up to four streams
 * inspected (reachability, resolution, duration against TMDB, audio and subtitle languages). It
 * also counts the dialogs and toasts each extension tries to show.
 *
 * Opt-in: `./gradlew :kloudstream-desktop:jvmTest --tests '*ProviderSurveyLiveTest*' -Pkloudstream.survey=spec.json`
 * where spec.json has `out`, `cache`, `candidates` ({repo, name, group}) and `corpus` (works with a
 * `group`: candidates of group `vo` or `anime` get the works of that group, `all` gets every work).
 */
class ProviderSurveyLiveTest {
    data class Candidate(val repo: String, val name: String, val group: String, val apis: List<String>? = null)
    data class Work(val group: String, val kind: String, val tmdb: Long, val imdb: String?, val title: String,
        val original: String, val english: String? = null, val year: Int, val runtime: Int?, val queries: List<String> = emptyList())
    data class Spec(val out: String, val cache: String, val candidates: List<Candidate>, val corpus: List<Work>,
        val concurrency: Int = 8, val linkTimeoutMs: Long = 45_000, val titlesPerGroup: Int? = null)

    private data class SitePlugin(val internalName: String = "", val name: String? = null, val url: String = "", val version: Int = 0)
    private data class Repository(val name: String? = null, val pluginLists: List<String> = emptyList())

    private val repositories = java.util.concurrent.ConcurrentHashMap<String, List<SitePlugin>>()

    @Test fun survey() = runBlocking(Dispatchers.IO) {
        val specPath = System.getProperty("kloudstream.survey").orEmpty()
        assumeTrue("survey not requested", specPath.isNotBlank())
        val spec = mapper.readValue<Spec>(File(specPath))
        val cache = File(spec.cache).apply { mkdirs() }
        System.setProperty("kloudstream.home", File(cache, "home").path)
        CloudStreamApp.context = DesktopContext
        val out = File(spec.out)
        val rows = mutableListOf<Map<String, Any?>>()
        val plugins = mutableListOf<Map<String, Any?>>()
        fun save() = synchronized(rows) {
            out.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("plugins" to plugins, "results" to rows)))
        }

        // 1. Install and load every candidate (one class loader each, as in the app).
        val loaded = spec.candidates.map { candidate -> async { candidate to load(candidate, cache) } }.awaitAll()
        loaded.forEach { (candidate, result) -> plugins += result.first.toMutableMap().apply { put("group", candidate.group) } }
        save()

        // 2. Survey each provider over its group's works, sequentially per provider.
        val gate = Semaphore(spec.concurrency)
        coroutineScope {
            loaded.flatMap { (candidate, result) ->
                val apis = result.second
                    .filter { api -> candidate.apis == null || candidate.apis.any { it.equals(api.name, true) } }
                apis.map { api ->
                    async {
                        val works = spec.corpus.filter { candidate.group == "all" || it.group == candidate.group }
                            .filter { work -> api.supportedTypes.isEmpty() || supports(api, work) }
                            .let { list -> spec.titlesPerGroup?.let { list.take(it) } ?: list }
                        for (work in works) {
                            val row = gate.withPermit { survey(api, work, candidate, spec.linkTimeoutMs) }
                            synchronized(rows) { rows += row }
                            save()
                        }
                    }
                }
            }.awaitAll()
        }
        // 3. What each extension tried to show, by translated class directory.
        val prompts = ExtensionPrompts.drain().groupBy { it.origin }
        synchronized(rows) {
            plugins.replaceAll { plugin ->
                val dir = plugin["classDir"] as? String
                plugin.toMutableMap().apply {
                    put("prompts", prompts[dir].orEmpty().map { "${it.kind}: ${it.text}" }.distinct())
                }
            }
        }
        save()
        println("Survey done: ${rows.size} rows in $out")
    }

    private fun supports(api: MainAPI, work: Work): Boolean {
        val types = api.supportedTypes
        return if (work.group == "anime") types.any { it == TvType.Anime || it == TvType.AnimeMovie || it == TvType.OVA }
            else if (work.kind == "movie") TvType.Movie in types else TvType.TvSeries in types
    }

    private suspend fun load(candidate: Candidate, cache: File): Pair<Map<String, Any?>, List<MainAPI>> {
        val info = linkedMapOf<String, Any?>("name" to candidate.name, "repo" to candidate.repo)
        var apis = emptyList<MainAPI>()
        val started = System.currentTimeMillis()
        try {
            val list = repositories.getOrPut(candidate.repo) {
                val repository = mapper.readValue<Repository>(app.get(candidate.repo, cacheTime = 0).text)
                repository.pluginLists.flatMap { mapper.readValue<List<SitePlugin>>(app.get(it, cacheTime = 0).text) }
            }
            val site = list.firstOrNull { it.internalName.equals(candidate.name, true) } ?: error("not in repository")
            info["version"] = site.version
            val key = (candidate.name + "-" + candidate.repo.hashCode().toUInt().toString(16)).lowercase()
            val file = File(cache, "$key-v${site.version}.cs3")
            if (!file.exists()) file.writeBytes(app.get(site.url, cacheTime = 0).body.bytes())
            info["sizeKB"] = file.length() / 1024
            val dir = DexTranslator.translate(file, File(cache, "classes"))
            info["classDir"] = dir.name
            val manifest = mapper.readValue<BasePlugin.Manifest>(File(dir, "manifest.json"))
            val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), ProviderSurveyLiveTest::class.java.classLoader)
            val plugin = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
            plugin.filename = file.absolutePath
            val loadStarted = System.currentTimeMillis()
            withTimeoutOrNull(60_000) {
                if (plugin is Plugin) plugin.load(DesktopContext as Context) else plugin.load()
            } ?: error("load timed out")
            info["loadMs"] = System.currentTimeMillis() - loadStarted
            apis = synchronized(APIHolder.allProviders) { APIHolder.allProviders.filter { it.sourcePlugin == file.absolutePath } }
            info["apis"] = apis.map { mapOf("name" to it.name, "lang" to it.lang, "types" to it.supportedTypes.map(TvType::name), "mainUrl" to it.mainUrl) }
            info["loaded"] = true
        } catch (t: Throwable) {
            info["loaded"] = false
            info["error"] = t.toString().take(300)
        }
        info["totalLoadMs"] = System.currentTimeMillis() - started
        println("Loaded ${candidate.name}: ${info["loaded"]} ${info["error"] ?: ""} ${apis.size} APIs")
        return info to apis
    }

    private suspend fun survey(api: MainAPI, work: Work, candidate: Candidate, linkTimeoutMs: Long): Map<String, Any?> {
        val row = linkedMapOf<String, Any?>("plugin" to candidate.name, "group" to candidate.group, "provider" to api.name,
            "lang" to api.lang, "tmdb" to work.tmdb, "title" to work.title, "kind" to work.kind, "workGroup" to work.group)
        val started = System.currentTimeMillis()
        val key = ProviderKey(api.name, api.mainUrl)
        val ids = setOfNotNull(
            ExternalMediaId(if (work.kind == "movie") ExternalIdNamespace.TmdbMovie else ExternalIdNamespace.TmdbTv, work.tmdb.toString()),
            work.imdb?.let { ExternalMediaId(ExternalIdNamespace.Imdb, it) })
        val queries = (work.queries + listOfNotNull(work.title, work.english, work.original)).distinct().filter { it.isNotBlank() }
        val titles = queries.map(MediaAggregator::matchKey).toSet()
        val family = if (work.kind == "movie") "movie" else "series"
        try {
            // 1. Search with each known title until one matches by id, or by title and year.
            var match: SearchResponse? = null
            var how = "none"
            var searched = 0
            for (query in queries.filter { it.all { c -> c.code < 0x3000 } }.take(4)) {
                val page = withTimeoutOrNull(20_000) { DirectProviderGateway.search(api, query, 1) } ?: continue
                searched += page.items.size
                val candidates = page.items.map { it to SearchMediaMapper.candidate(key, it, api.javaClass.name) }
                val byId = candidates.firstOrNull { (_, c) -> c.externalIds.any { it in ids } }
                val byTitle = candidates.filter { (_, c) ->
                    MediaAggregator.titles(c).any { it in titles } &&
                        (work.group == "anime" || MediaAggregator.family(c.kind) == null || MediaAggregator.family(c.kind) == family) &&
                        (c.year == null || abs(c.year!! - work.year) <= 1) &&
                        c.externalIds.none { id -> ids.any { it.namespace == id.namespace && it != id } }
                }.sortedBy { (_, c) -> c.year?.let { abs(it - work.year) } ?: 5 }
                val chosen = byId ?: byTitle.firstOrNull()
                if (chosen != null) { match = chosen.first; how = if (byId != null) "id" else "title:$query"; break }
            }
            row["searchResults"] = searched
            row["found"] = match != null
            row["matchedBy"] = how
            row["searchMs"] = System.currentTimeMillis() - started
            match ?: return row

            // 2. Detail page and identity.
            val loadStarted = System.currentTimeMillis()
            val response = withTimeoutOrNull(25_000) { DirectProviderGateway.load(api, match.url) }
            row["loadMs"] = System.currentTimeMillis() - loadStarted
            row["loaded"] = response != null
            response ?: return row
            val meta = SearchMediaMetadata.of(response)
            row["detailTitle"] = response.name
            row["detailYear"] = response.year
            row["detailIdMatch"] = meta.ids.any { it in ids }
            row["detailIdConflict"] = meta.ids.any { id -> ids.any { it.namespace == id.namespace && it != id } }

            // 3. The film, or the first episode (subbed track first for anime).
            val tracks = (response as? AnimeLoadResponse)?.episodes?.keys?.map { it.name }
            row["tracks"] = tracks
            val (data, track) = when (response) {
                is MovieLoadResponse -> response.dataUrl to "None"
                is TvSeriesLoadResponse -> firstEpisode(response.episodes)?.data to "None"
                is AnimeLoadResponse -> response.episodes.entries.sortedBy { if (it.key.name == "Subbed") 0 else 1 }
                    .firstNotNullOfOrNull { (dub, list) -> firstEpisode(list)?.let { it.data to dub.name } } ?: (null to "None")
                else -> null to "None"
            }
            row["track"] = track
            row["episodeFound"] = !data.isNullOrBlank()
            if (data.isNullOrBlank()) return row

            // 4. Links and subtitles.
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            var firstLinkMs: Long? = null
            val linksStarted = System.currentTimeMillis()
            val ok = withTimeoutOrNull(linkTimeoutMs) {
                DirectProviderGateway.loadLinks(api, data, false, { synchronized(subtitles) { subtitles += it } }) { link ->
                    synchronized(links) {
                        if (firstLinkMs == null) firstLinkMs = System.currentTimeMillis() - linksStarted
                        if (links.size < 80) links += link
                    }
                }
            }
            val all = synchronized(links) { links.toList() }
            val subs = synchronized(subtitles) { subtitles.map { it.lang } }.distinct()
            val binding = EpisodeSourceBinding(api.name, api.mainUrl, match.url, data, track, api.lang)
            row["linksMs"] = System.currentTimeMillis() - linksStarted
            row["linksTimedOut"] = ok == null
            row["firstLinkMs"] = firstLinkMs
            row["links"] = all.size
            row["versions"] = all.groupingBy { PlaybackSources.version(it.name, binding).name }.eachCount()
            row["subtitles"] = subs.take(40)
            row["frenchSubtitles"] = subs.any(::french)

            // 5. Up to four streams, one per host, best declared quality first.
            val sample = all.distinctBy { runCatching { URI(it.url).host }.getOrNull() }
                .sortedByDescending { PlaybackSources.quality(it) }.take(4)
            val inspections = coroutineScope { sample.map { link -> async { link to StreamInspector.inspect(link, 8_000) } }.awaitAll() }
            val expected = work.runtime?.let { it * 60.0 }
            row["inspected"] = inspections.map { (link, inspection) ->
                mapOf("host" to runCatching { URI(link.url).host }.getOrNull(), "name" to link.name.take(60), "type" to link.type.name,
                    "declaredQuality" to link.quality, "version" to PlaybackSources.version(link.name, binding).name,
                    "reachable" to inspection.reachable, "http" to inspection.httpCode, "height" to inspection.height,
                    "audio" to inspection.audioLanguages.toList(), "subs" to inspection.subtitleLanguages.toList(),
                    "durationSec" to inspection.durationSeconds?.toInt(),
                    "durationRatio" to if (expected != null && inspection.durationSeconds != null) inspection.durationSeconds!! / expected else null,
                    "sizeMB" to inspection.sizeBytes?.let { it / 1_000_000 }, "error" to inspection.error?.take(100))
            }
        } catch (t: Throwable) {
            row["error"] = "${t.javaClass.simpleName}: ${t.message?.take(160)}"
        }
        row["totalMs"] = System.currentTimeMillis() - started
        println("${api.name} | ${work.title}: found=${row["found"]} links=${row["links"]} ${row["error"] ?: ""}")
        return row
    }

    private fun french(lang: String): Boolean {
        val l = lang.lowercase()
        return l == "fr" || l.startsWith("fr-") || l.startsWith("fre") || l.startsWith("fra") || "french" in l || "fran" in l
    }

    private fun firstEpisode(episodes: List<Episode>): Episode? =
        episodes.firstOrNull { (it.season ?: 1) == 1 && it.episode == 1 }
            ?: episodes.firstOrNull { EpisodeNumbering.fromLabel(it.name)?.let { n -> (n.season ?: 1) == 1 && n.episode == 1 } == true }
            ?: episodes.minByOrNull { (it.season ?: 1) * 10_000 + (it.episode ?: 9_999) }
}
