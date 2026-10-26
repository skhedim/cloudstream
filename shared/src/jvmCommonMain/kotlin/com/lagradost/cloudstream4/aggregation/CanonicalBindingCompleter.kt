package com.lagradost.cloudstream4.aggregation

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

    /** Queries: the canonical title, then another known title (original title) if different. */
    private fun queries(request: CanonicalDetailRequest): List<String> {
        val primary = SearchMediaMapper.cleanTitle(request.title)
        val key = MediaAggregator.matchKey(primary)
        val second = request.aliases.map(SearchMediaMapper::cleanTitle)
            .firstOrNull { it.isNotBlank() && MediaAggregator.matchKey(it) != key && it.length >= 2 }
        return listOfNotNull(primary, second)
    }

    suspend fun complete(request: CanonicalDetailRequest, providers: List<RegisteredProvider>): List<DetailBinding> {
        val bound = request.bindings.map { it.provider }.toSet() + request.bindings.mapNotNull { it.providerId }
        val targets = providers.filter { it.key !in bound && it.id !in bound && ProviderHealth.searchUsable(it.id) }
        if (targets.isEmpty()) return emptyList()
        val limiter = ProviderLimiter(concurrency, 1)
        val found = java.util.Collections.synchronizedList(mutableListOf<DetailBinding>())
        // Whatever is found before the deadline is kept.
        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                targets.map { provider -> async {
                    limiter.withPermit(provider.id) {
                        for (query in queries(request)) {
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
}
