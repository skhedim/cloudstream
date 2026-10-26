package com.lagradost.cloudstream4.domain.media

/** One instance per query. Only enrich unresolved duplicate-title candidates, within a hard budget. */
class MetadataEnrichmentPlanner(private val maxRequests: Int = 12) {
    private val attempted = mutableSetOf<ProviderBinding>()

    fun next(candidates: List<MediaCandidate>, media: List<CanonicalMedia>): List<ProviderBinding> {
        val cardIds = media.flatMap { card -> card.bindings.map { it to card.id } }.toMap()
        val byTitle = candidates.flatMap { candidate ->
            MediaAggregator.titles(candidate).map { (it to candidate.edition) to candidate }
        }.groupBy({ it.first }, { it.second })
        val unresolved = byTitle.values.filter { matches ->
            matches.any { MediaAggregator.family(it.kind) != null } &&
                matches.mapNotNull { cardIds[it.binding] }.distinct().size > 1
        }.flatten().map { it.binding }.toSet()
        val uncertainTypes = byTitle.values.filter { matches ->
            matches.map { MediaAggregator.family(it.kind) }.distinct().size > 1
        }.flatten().map { it.binding }.toSet()
        return candidates.asSequence().filter {
            // Detail loading verifies type too: some extensions label all search hits as movies.
            it.binding in unresolved && it.binding !in attempted && it.externalIds.isEmpty() &&
                (it.year == null || it.binding in uncertainTypes)
        }.map { it.binding }.distinct().take((maxRequests - attempted.size).coerceAtLeast(0))
            .toList().also { attempted.addAll(it) }
    }
}
