package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.lagradost.cloudstream4.aggregation.CanonicalSourceLoader
import com.lagradost.cloudstream4.aggregation.SourceVersion
import com.lagradost.cloudstream3.ui.player.aggregation.CanonicalSourceDiagnostics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    var lastCachedTimestamp: Long = unixTime,
    /** If it has fully loaded */
    var saturated: Boolean,
)

class RepoLinkGenerator(
    episodes: List<ResultEpisode>,
    val page: LoadResponse? = null,
) : VideoGenerator<ResultEpisode>(episodes) {
    companion object {
        const val TAG = "RepoLink"
        val cache: HashMap<Pair<String, Int>, Cache> =
            hashMapOf()
    }

    /** Version chosen in the player; canonical acquisition waits for it when it exists. */
    var canonicalPreference: SourceVersion = SourceVersion.Auto

    /** Called with the episode offset when enough canonical sources are known to start playback. */
    var onCanonicalReady: ((Int) -> Unit)? = null

    /**
     * Catalogue runtime for stream checks: TMDB's (movie or this episode), else the movie runtime
     * the detail page shows. Unknown for episodes without a TMDB show ID.
     */
    private suspend fun expectedRuntime(episode: ResultEpisode): Pair<Double?, Boolean> {
        val response = page
        val tmdb = response?.let { runCatching { with(LoadResponse) { it.getTMDbId() } }.getOrNull() }
        if (response is com.lagradost.cloudstream3.MovieLoadResponse || episode.season == null) {
            val minutes = tmdb?.let { com.lagradost.cloudstream4.aggregation.RuntimeCatalog.movieMinutes(it) }
                ?: response?.duration?.takeIf { it in 20..400 }
            return minutes?.let { it * 60.0 } to false
        }
        val season = episode.season ?: return null to true
        val minutes = tmdb?.let {
            com.lagradost.cloudstream4.aggregation.RuntimeCatalog.episodeMinutes(it, season, episode.episode)
        }
        return minutes?.let { it * 60.0 } to true
    }

    override val hasCache = true
    override val canSkipLoading = true
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    // this is a simple array that is used to instantly load links if they are already loaded
    //var linkCache = Array<Set<ExtractorLink>>(size = episodes.size, init = { setOf() })
    //var subsCache = Array<Set<SubtitleData>>(size = episodes.size, init = { setOf() })

    @Throws
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val current = videos.getOrNull(offset) ?: return false
        if (current.sourceBindings.isNotEmpty()) {
            // Sources are published as they arrive; the player starts on onCanonicalReady and may
            // upgrade once acquisition has finished.
            val subtitleNames = ConcurrentHashMap<String, AtomicInteger>()
            val (expectedSeconds, isEpisode) = expectedRuntime(current)
            val sources = CanonicalSourceLoader().load(current.sourceBindings, clearCache, isCasting,
                preference = canonicalPreference, expectedSeconds = expectedSeconds, episode = isEpisode,
                onSource = { source -> if (source.link.type in sourceTypes) callback(source.link to null) },
                onSubtitle = { file ->
                    val data = PlayerSubtitleHelper.getSubtitleData(file)
                    val count = subtitleNames.getOrPut(data.originalName) { AtomicInteger(0) }.incrementAndGet()
                    subtitleCallback(data.copy(nameSuffix = if (count == 1) "" else count.toString()))
                },
                onReady = { onCanonicalReady?.invoke(offset) })
            currentCoroutineContext().ensureActive()
            if (com.lagradost.cloudstream3.BuildConfig.DEBUG) CanonicalSourceDiagnostics.write(current, sources)
            return sources.links.any { it.type in sourceTypes }
        }
        val binding = current.sourceBindings.firstOrNull()
        val cacheKey = (if (binding == null) current.apiName else
            "${binding.apiName}\n${binding.mainUrl}") to current.id

        val currentCache = synchronized(cache) {
            cache[cacheKey] ?: Cache(
                mutableSetOf(),
                mutableSetOf(),
                unixTime,
                false
            ).also {
                cache[cacheKey] = it
            }
        }

        // These act as a general filter to prevent duplication of links or names
        // Avoid any possible ConcurrentModificationException
        val currentLinksUrls = ConcurrentHashMap.newKeySet<String>()
        val currentSubsUrls = ConcurrentHashMap.newKeySet<String>()
        // Use atomics as otherwise we get race conditions when incrementing, while rare it did actually happen!
        val lastCountedSuffix = ConcurrentHashMap<String, AtomicInteger>()

        synchronized(currentCache) {
            val outdatedCache =
                unixTime - currentCache.lastCachedTimestamp > 60 * 20 // 20 minutes

            if (outdatedCache || clearCache) {
                currentCache.linkCache.clear()
                currentCache.subtitleCache.clear()
                currentCache.saturated = false
            } else if (currentCache.linkCache.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Resumed previous loading from ${unixTime - currentCache.lastCachedTimestamp}s ago"
                )
            }

            // call all callbacks
            currentCache.linkCache.forEach { link ->
                currentLinksUrls.add(link.url)
                if (sourceTypes.contains(link.type)) {
                    callback(link to null)
                }
            }

            currentCache.subtitleCache.forEach { sub ->
                currentSubsUrls.add(sub.url)
                lastCountedSuffix.getOrPut(sub.originalName) { AtomicInteger(0) }.incrementAndGet()
                subtitleCallback(sub)
            }

            // this stops all execution if links are cached
            // no extra get requests
            if (currentCache.saturated) {
                return true
            }
        }

        val api = if (binding == null) getApiFromNameNull(current.apiName) else {
            val named = apis.filter { it.name == binding.apiName }
            named.firstOrNull { it.mainUrl == binding.mainUrl } ?: named.singleOrNull()
        } ?: throw Exception("This provider does not exist")
        val result = APIRepository(api).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = { file ->
                Log.d(TAG, "Loaded SubtitleFile: $file")
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || !currentSubsUrls.add(correctFile.url)) {
                    return@loadLinks
                }

                // this part makes sure that all names are unique for UX
                val nameDecoded = correctFile.originalName.html().toString()
                    .trim() // `%3Ch1%3Esub%20name…` → `<h1>sub name…` → `sub name…`
                val suffixCount =
                    lastCountedSuffix.getOrPut(nameDecoded) { AtomicInteger(0) }.incrementAndGet()

                val updatedFile =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                synchronized(currentCache) {
                    if (currentCache.subtitleCache.add(updatedFile)) {
                        subtitleCallback(updatedFile)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            },
            callback = { link ->
                Log.d(TAG, "Loaded ExtractorLink: $link")
                if (link.url.isBlank() || !currentLinksUrls.add(link.url)) {
                    return@loadLinks
                }

                synchronized(currentCache) {
                    if (currentCache.linkCache.add(link)) {
                        if (sourceTypes.contains(link.type)) {
                            callback(Pair(link, null))
                        }

                        currentCache.linkCache.add(link)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            }
        )

        synchronized(currentCache) {
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result
    }
}
