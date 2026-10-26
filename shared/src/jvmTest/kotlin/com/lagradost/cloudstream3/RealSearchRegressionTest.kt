package com.lagradost.cloudstream3

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream4.domain.media.*
import org.junit.Assert.*
import org.junit.Test

/** Replays sanitized candidates captured from real French extensions (see resources/aggregation-fixtures). */
class RealSearchRegressionTest {
    private data class Fixture(val query: String, val candidates: List<Captured>)
    private data class Captured(val provider: String, val title: String, val year: Int?, val kind: String,
        val editions: List<String>, val artwork: List<String>, val externalIds: List<String>)

    private fun load(name: String): List<MediaCandidate> {
        val text = javaClass.getResource("/aggregation-fixtures/$name")!!.readText()
        return mapper.readValue<Fixture>(text).candidates.mapIndexed { index, c ->
            MediaCandidate(ProviderBinding(ProviderKey(c.provider, "https://fixture.invalid"), "/$index"),
                c.title, c.year, MediaKind.valueOf(c.kind), externalIds = c.externalIds.map {
                    ExternalMediaId(ExternalIdNamespace.valueOf(it.substringBefore(':')), it.substringAfter(':'))
                }.toSet(), edition = c.editions.toSet(), artworkIds = c.artwork.toSet())
        }
    }

    private fun cardWith(cards: List<CanonicalMedia>, id: ExternalMediaId) = cards.filter { id in it.externalIds }

    @Test fun breakingBadKeepsEveryIdentifiedProviderOnOneCard() {
        val candidates = load("breaking-bad-search.json")
        val cards = MediaAggregator().aggregate(candidates)
        val show = ExternalMediaId(ExternalIdNamespace.TmdbTv, "1396")
        val card = cardWith(cards, show).single()
        assertTrue(candidates.filter { show in it.externalIds }.all { it.binding in card.bindings })
        assertEquals(2008, card.year)
        // Other works that merely contain the words stay apart.
        assertTrue(cards.none { it !== card && it.title.equals("Breaking Bad", true) && it.year == 2008 })
        assertTrue(cards.size <= 49)
    }

    @Test fun frierenJoinsFrenchAndEnglishTitlesButNotTheHomonyms() {
        val candidates = load("frieren-search.json")
        val cards = MediaAggregator().aggregate(candidates)
        val show = ExternalMediaId(ExternalIdNamespace.TmdbTv, "209867")
        val card = cardWith(cards, show).single()
        assertTrue(candidates.filter { show in it.externalIds }.all { it.binding in card.bindings })
        // The provider that only knows the AniList/MAL IDs joins through the one knowing both.
        assertTrue(candidates.filter { c -> c.externalIds.any { it.namespace == ExternalIdNamespace.AniList } }
            .all { it.binding in card.bindings })
        assertTrue(card.titles.any { it.startsWith("Frieren: Beyond") } && "Frieren" in card.titles)
        val homonyms = cards.filter { it.year == 1996 && it.title == "Frieren" }
        assertTrue(homonyms.isNotEmpty() && homonyms.all { it.kind == MediaKind.Movie && show !in it.externalIds })
    }
}
