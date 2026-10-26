package com.lagradost.cloudstream3.ui.home.aggregation

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream4.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Local history snapshots. A page opened without playback is not a positive signal. */
data class RecommendationHistory(
    val candidates: List<MediaCandidate>, val sources: List<RecommendationSource>,
    val updatedAt: Long, val positionMs: Long = 0, val completed: Boolean = false,
    val dropped: Boolean = false,
    val fromContinueWatching: Boolean = true,
)
data class RecommendationSource(val binding: ProviderBinding, val implementation: String)
data class RecommendationSeed(val key: String, val sources: List<RecommendationSource>,
    val kind: MediaKind = MediaKind.Unknown, val title: String = "", val year: Int? = null,
    val externalIds: Set<ExternalMediaId> = emptySet(), val alternateTitles: Set<String> = emptySet()) {
    /** A redirect/error page can be valid provider data yet describe another work. */
    fun accepts(source: RecommendationSource, response: LoadResponse): Boolean {
        if (MediaAggregator.family(kind) != MediaAggregator.family(SearchMediaMapper.kind(response.type))) return false
        val actualIds=SearchMediaMetadataCache.get(source.binding)?.ids.orEmpty()
        if (externalIds.any { expected -> actualIds.any { it.namespace==expected.namespace && it!=expected } }) return false
        if (externalIds.intersect(actualIds).isNotEmpty()) return true
        if (year!=null && response.year!=null && year!=response.year) return false
        return MediaAggregator.matchKey(response.name) in (alternateTitles+title).map(MediaAggregator::matchKey)
    }
}
data class RecommendationBatch(val seed: String, val source: RecommendationSource, val items: List<SearchResponse>,
    val kind: MediaKind = MediaKind.Unknown, val title: String = "")
data class RecommendationPlan(val seeds: List<RecommendationSeed>, val excluded: List<MediaCandidate>) {
    companion object {
        const val MIN_PLAYBACK_MS = 30_000L
        const val SEEDS_PER_FAMILY = 3
        fun from(history: List<RecommendationHistory>): RecommendationPlan {
            val seen = history.filter { it.completed || it.dropped || it.positionMs > 0 }
                .sortedWith(compareByDescending<RecommendationHistory> { it.fromContinueWatching }
                    .thenByDescending { it.updatedAt }.thenBy { it.candidates.firstOrNull()?.binding?.url })
            val candidates = seen.flatMap { it.candidates }.distinctBy { it.binding }
            val groups = MediaAggregator().aggregate(candidates)
            val seeds = groups.mapNotNull { media ->
                val entries = seen.filter { entry -> entry.candidates.any { it.binding in media.bindings } }
                // Prefer the visible continue-watching history; short accidental launches
                // remain excluded from suggestions without becoming preference signals.
                if (entries.any { it.dropped } || entries.none { it.completed || it.positionMs >= MIN_PLAYBACK_MS } ||
                    MediaAggregator.family(media.kind) == null) return@mapNotNull null
                val sources = entries.flatMap { it.sources }.distinctBy { it.binding.provider }.take(8)
                if (sources.isEmpty()) return@mapNotNull null
                val key = media.bindings.map { "${it.provider.name}\n${it.provider.mainUrl}\n${it.url}" }.sorted().first()
                RecommendationSeed(key, sources, media.kind, media.title, media.year, media.externalIds,
                    entries.flatMap { it.candidates }.flatMap { MediaAggregator.titles(it) }.toSet())
            }
            val movies = seeds.filter { MediaAggregator.family(it.kind) == "movie" }.take(SEEDS_PER_FAMILY)
            val series = seeds.filter { MediaAggregator.family(it.kind) == "series" }.take(SEEDS_PER_FAMILY)
            // Reserve acquisition time for both kinds, including when many recent films exist.
            val balanced = (0 until SEEDS_PER_FAMILY).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) }
            return RecommendationPlan(balanced, candidates)
        }
    }
}

/** Reuse existing detail recommendations: no history upload, new service, or extension API.
 * Cache is scoped by local account, source implementation and seed; failures are not cached. */
class PersonalizedRecommendationsLoader(
    private val concurrency: Int = 2, private val requestTimeoutMs: Long = 5_000,
    private val totalTimeoutMs: Long = 20_000,
    private val related: suspend (RecommendationSeed) -> List<MediaReference> = { emptyList() },
) {
    private data class Key(val account: String, val seed: RecommendationSeed)
    private data class Cached(val batch: RecommendationBatch, val at: Long)
    private val cache = linkedMapOf<Key, Cached>()
    suspend fun load(account: String, seeds: List<RecommendationSeed>, refresh: Boolean,
        fetch: suspend (RecommendationSource) -> List<SearchResponse>?,
    ): List<RecommendationBatch> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val metadata = Semaphore(2)
        val selected = seeds.take(RecommendationPlan.SEEDS_PER_FAMILY * 2)
        val results = arrayOfNulls<RecommendationBatch>(selected.size)
        withTimeoutOrNull(totalTimeoutMs) {
            selected.mapIndexed { index, seed -> async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                val key = Key(account, seed)
                val saved = synchronized(cache) {
                    if (refresh) cache.remove(key)
                    cache[key]?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000 }
                }
                if (saved != null) { results[index] = saved.batch; return@async }
                val references = metadata.withPermit { withTimeoutOrNull(2_500) { related(seed) }.orEmpty() }
                for (source in seed.sources.take(8)) {
                    val items = semaphore.withPermit {
                        yield()
                        try { withTimeoutOrNull(requestTimeoutMs) { fetch(source) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }.orEmpty().filter { response ->
                        response.apiName == source.binding.provider.name &&
                            (seed.kind == MediaKind.Unknown || MediaAggregator.family(
                                SearchMediaMapper.candidate(source.binding.provider, response, source.implementation).kind
                            ) == MediaAggregator.family(seed.kind)) &&
                            (references.isEmpty() || corroborated(SearchMediaMapper.candidate(
                                source.binding.provider,response,source.implementation), references))
                    }.take(24)
                    ensureActive()
                    if (items.isNotEmpty()) {
                        val batch = RecommendationBatch(seed.key, source, items.toList(), seed.kind, seed.title)
                        results[index] = batch
                        synchronized(cache) {
                            cache[key] = Cached(batch, System.currentTimeMillis())
                            while (cache.size > 32) cache.remove(cache.keys.first())
                        }
                        break
                    }
                }
            } }.awaitAll()
        }
        ensureActive()
        results.filterNotNull()
    }

    private fun corroborated(candidate: MediaCandidate, references: List<MediaReference>): Boolean = references.any { ref ->
        if (MediaAggregator.family(candidate.kind) != MediaAggregator.family(ref.kind)) false
        else if (candidate.externalIds.any { it.namespace == ref.id.namespace && it != ref.id }) false
        else ref.id in candidate.externalIds ||
            ((candidate.year == null || ref.year == null || candidate.year == ref.year) &&
                MediaAggregator.titles(candidate).intersect((ref.alternateTitles+ref.title).map(MediaAggregator::matchKey).toSet()).isNotEmpty())
    }
}
