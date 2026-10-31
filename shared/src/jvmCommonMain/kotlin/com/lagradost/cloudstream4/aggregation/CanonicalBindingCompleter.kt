package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Finds the same work at providers that are not bound yet (a home or recommendation card often
 * comes from one catalogue only). Strict on purpose: a shared ID, or the same title, media
 * family and a known year at most one apart, with no contradicting ID. Season entries need a
 * shared ID. Loaded pages are verified again by [CanonicalDetailBuilder].
 */
class CanonicalBindingCompleter(
    private val concurrency: Int = 6,
    private val searchTimeoutMs: Long = 8_000,
    private val totalTimeoutMs: Long = 12_000,
    private val gateway: () -> ProviderGateway = { AggregationPlatform.gateway },
) {
    fun matches(request: CanonicalDetailRequest, candidate: MediaCandidate): Boolean {
        val known = request.externalIds
        if (candidate.externalIds.any { id -> known.any { it.namespace == id.namespace && it != id } }) return false
        if (candidate.externalIds.any { it in known }) return candidate.edition.isEmpty() ||
            candidate.edition.all { it.startsWith("season:") }
        if (candidate.edition.isNotEmpty() || candidate.edition != request.edition) return false
        val family = MediaAggregator.family(request.kind) ?: return false
        if (MediaAggregator.family(candidate.kind) != family) return false
        val titles = (request.aliases + request.title).map(MediaAggregator::matchKey).toSet()
        if (MediaAggregator.titles(candidate).none { it in titles }) return false
        val year = request.year ?: return false
        val other = candidate.year ?: return false
        return abs(other - year) <= MediaAggregator.YEAR_TOLERANCE
    }

    private fun covers(provider: RegisteredProvider, request: CanonicalDetailRequest): Boolean {
        val family = MediaAggregator.family(request.kind) ?: return false
        return provider.api.supportedTypes.any { MediaAggregator.family(SearchMediaMapper.kind(it)) == family }
    }

    /** Queries: the canonical title, the English one if known, then another known title (original title). */
    private fun queries(request: CanonicalDetailRequest, english: String?): List<String> {
        val primary = SearchMediaMapper.cleanTitle(request.title)
        val keys = mutableSetOf(MediaAggregator.matchKey(primary))
        val second = english?.let(SearchMediaMapper::cleanTitle)?.takeIf { it.length >= 2 && keys.add(MediaAggregator.matchKey(it)) }
        val third = request.aliases.map(SearchMediaMapper::cleanTitle)
            .firstOrNull { it.isNotBlank() && MediaAggregator.matchKey(it) !in keys && it.length >= 2 }
        return listOfNotNull(primary, second, third)
    }

    /**
     * @param english the work's English title ([SearchMetadataCatalog.englishTitle] by default):
     * original-version and anime catalogues know it rather than the French one.
     */
    suspend fun complete(request: CanonicalDetailRequest, providers: List<RegisteredProvider>,
        english: suspend (CanonicalDetailRequest) -> String? = { work ->
            work.externalIds.firstOrNull { it.namespace == ExternalIdNamespace.TmdbMovie || it.namespace == ExternalIdNamespace.TmdbTv }
                ?.let { SearchMetadataCatalog.englishTitle(it) }
        }): List<DetailBinding> {
        val bound = request.bindings.map { it.provider }.toSet() + request.bindings.mapNotNull { it.providerId }
        val targets = providers.filter { it.key !in bound && it.id !in bound && ProviderHealth.searchUsable(it.id) }
        if (targets.isEmpty()) return emptyList()
        val viewer = PlaybackSources.viewerLanguage()
        val foreign = targets.any { it.language.substringBefore('-').lowercase() !in setOf(viewer, "multi", "mul", "all") }
        val englishTitle = if (!foreign) null else runCatching { english(request) }.getOrNull()
        @Suppress("NAME_SHADOWING") val request = englishTitle?.let { request.copy(aliases = request.aliases + it) } ?: request
        val limiter = ProviderLimiter(concurrency, 1)
        val found = java.util.Collections.synchronizedList(mutableListOf<DetailBinding>())
        // Whatever is found before the deadline is kept.
        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                targets.map { provider -> async {
                    limiter.withPermit(provider.id) {
                        for (query in queries(request, englishTitle)) {
                            val page = try {
                                withTimeoutOrNull(searchTimeoutMs) { gateway().search(provider.api, query, 1) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            // An empty answer only counts against a provider that claims this kind of work.
                            if (page?.items?.isNotEmpty() == true) ProviderHealth.recordSearch(provider.id, true)
                            else if (covers(provider, request)) ProviderHealth.recordSearch(provider.id, false)
                            val match = page?.items.orEmpty().map { response ->
                                response to SearchMediaMapper.candidate(provider.key, response, provider.api.javaClass.name)
                            }.filter { (_, candidate) -> matches(request, candidate) }
                                // A shared ID first, then the closest year.
                                .sortedWith(compareByDescending<Pair<*, MediaCandidate>> { (_, c) -> c.externalIds.any { it in request.externalIds } }
                                    .thenBy { (_, c) -> abs((c.year ?: 0) - (request.year ?: 0)) })
                                .firstOrNull()
                            if (match != null) {
                                found += DetailBinding(provider.key, match.first.url, match.second.edition, provider.id)
                                break
                            }
                        }
                    }
                } }.awaitAll()
            }
        }
        return synchronized(found) { found.toList() }
    }

    companion object {
        /**
         * Whether to look for the work at other providers now: once a day, or every 30 minutes
         * while a single provider offers it. A home card often comes from one catalogue, which may
         * offer no stream (Frembed FR), and a new release reaches the other providers within days.
         */
        fun due(request: CanonicalDetailRequest, now: Long = System.currentTimeMillis()): Boolean {
            val at = request.completedAt ?: return true
            val single = request.bindings.map { it.provider }.distinct().size < 2
            return now - at >= if (single) 30 * 60_000L else 24 * 60 * 60_000L
        }
    }
}
