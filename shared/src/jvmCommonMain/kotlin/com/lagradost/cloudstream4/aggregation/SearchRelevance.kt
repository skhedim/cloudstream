package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream4.domain.media.MediaAggregator

/**
 * Orders search cards by how well a title answers the query, so the list does not depend on
 * which provider answered first: the exact title, then titles starting with the query, then
 * titles containing it, then the rest. Among exact titles, a work offered by more providers comes
 * first (the popular series before an obscure homonym); otherwise the providers' order is kept.
 */
object SearchRelevance {
    const val EXACT = 0
    const val PREFIX = 1
    const val CONTAINS = 2
    const val OTHER = 3

    fun tier(query: String, titles: Collection<String>): Int {
        val key = MediaAggregator.matchKey(query)
        if (key.isBlank()) return OTHER
        val keys = titles.map(MediaAggregator::matchKey)
        return when {
            keys.any { it == key } -> EXACT
            keys.any { it.startsWith("$key ") } -> PREFIX
            keys.any { " $it ".contains(" $key ") } -> CONTAINS
            else -> OTHER
        }
    }

    fun rank(query: String?, cards: List<CanonicalSearchCard>): List<CanonicalSearchCard> {
        if (query.isNullOrBlank()) return cards
        val tiers = cards.map { tier(query, it.media.titles + it.media.title) }
        val providers = cards.map { card -> card.media.bindings.map { it.provider }.distinct().size }
        return cards.indices.sortedWith(compareBy<Int> { tiers[it] }
            .thenByDescending { if (tiers[it] == EXACT) providers[it] else 0 }
            .thenBy { it }).map(cards::get)
    }
}
