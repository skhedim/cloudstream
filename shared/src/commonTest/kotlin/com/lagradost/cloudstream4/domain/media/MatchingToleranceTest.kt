package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MatchingToleranceTest {
    private fun candidate(
        provider: String, title: String = "Breaking Bad", year: Int? = 2008,
        kind: MediaKind = MediaKind.Series, ids: Set<ExternalMediaId> = emptySet(),
    ) = MediaCandidate(ProviderBinding(ProviderKey(provider, "https://$provider.example"), "/$title/$year"),
        title, year, kind, externalIds = ids)

    @Test fun accentsAmpersandsAndRomanSequelNumbersDoNotSeparateTheSameWork() {
        assertEquals(MediaAggregator.matchKey("Les Misérables"), MediaAggregator.matchKey("Les Miserables"))
        assertEquals(MediaAggregator.matchKey("Fast & Furious"), MediaAggregator.matchKey("Fast and Furious"))
        assertEquals(MediaAggregator.matchKey("Tom et Jerry"), MediaAggregator.matchKey("Tom & Jerry"))
        assertEquals(MediaAggregator.matchKey("Rocky II"), MediaAggregator.matchKey("Rocky 2"))
        assertEquals(MediaAggregator.matchKey("Œdipe"), MediaAggregator.matchKey("Oedipe"))
        // Sequel numbers and single-letter words stay meaningful.
        assertNotEquals(MediaAggregator.matchKey("Rocky II"), MediaAggregator.matchKey("Rocky III"))
        assertNotEquals(MediaAggregator.matchKey("Malcolm X"), MediaAggregator.matchKey("Malcolm 10"))
        assertNotEquals(MediaAggregator.matchKey("It"), MediaAggregator.matchKey("It 2"))
        // Persistent identities keep the stricter display normalization.
        assertNotEquals(MediaAggregator.normalizeTitle("Élite"), MediaAggregator.normalizeTitle("Elite"))
        val merged = MediaAggregator().aggregate(listOf(candidate("A", "Élite", 2018), candidate("B", "Elite", 2018)))
        assertEquals(1, merged.size)
    }

    @Test fun neighbouringYearsMergeButAWiderSpreadOrConflictingIdsDoNot() {
        assertEquals(1, MediaAggregator().aggregate(listOf(candidate("A", year = 2008), candidate("B", year = 2009))).size)
        val spread = MediaAggregator().aggregate(listOf(candidate("A", year = 2008), candidate("B", year = 2009),
            candidate("C", year = 2010)))
        assertEquals(2, spread.size)
        val tmdb = { v: String -> setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, v)) }
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", "Dracula", 1992, MediaKind.Movie, tmdb("6114")),
            candidate("B", "Dracula", 1993, MediaKind.Movie, tmdb("999")))).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(candidate("A", year = 2008), candidate("B", year = 2008,
            kind = MediaKind.Movie))).size)
    }

    @Test fun anExactYearIsPreferredOverANeighbouringOne() {
        val cards = MediaAggregator().aggregate(listOf(
            candidate("A", "Dune", 2020, MediaKind.Movie), candidate("B", "Dune", 2022, MediaKind.Movie),
            candidate("C", "Dune", 2021, MediaKind.Movie), candidate("D", "Dune", 2020, MediaKind.Movie),
        ))
        // 2021 is next to both 2020 and 2022: ambiguous, it stays alone; the two 2020 entries merge.
        assertEquals(3, cards.size)
        assertEquals(2, cards.single { it.year == 2020 }.bindings.size)
    }

    @Test fun referencesAcceptANeighbouringYearButPreferTheExactOne() {
        val ref = { id: String, year: Int -> MediaReference(ExternalMediaId(ExternalIdNamespace.TmdbTv, id),
            "Breaking Bad", emptySet(), year, MediaKind.Series, emptySet()) }
        val near = MediaReferenceResolver.enrich(candidate("A", year = 2009), listOf(ref("1396", 2008)))
        assertEquals("1396", near.externalIds.single().value)
        assertEquals(2008, near.year)
        val exact = MediaReferenceResolver.enrich(candidate("A", year = 2009), listOf(ref("1", 2008), ref("2", 2009)))
        assertEquals("2", exact.externalIds.single().value)
        val far = MediaReferenceResolver.enrich(candidate("A", year = 2011), listOf(ref("1396", 2008)))
        assertTrue(far.externalIds.isEmpty())
    }

    @Test fun flatAbsoluteListsMergeWithEachOtherButNeverWithSeasonNumbering() {
        fun ep(provider: String, season: Int?, episode: Int, absolute: Boolean) = EpisodeCandidate(
            EpisodeProviderBinding(ProviderBinding(ProviderKey(provider, "https://$provider.example"), "/show"), "$provider:$episode"),
            season, episode, "None", episode, absolute = absolute)
        val episodes = EpisodeAggregator.aggregate(listOf(ep("A", null, 13, true), ep("B", null, 13, true),
            ep("C", 2, 1, false), ep("D", null, 13, false)))
        val absolute = episodes.single { it.key == "abs:e13" }
        assertEquals(2, absolute.bindings.size)
        assertTrue(absolute.absolute)
        assertEquals(1, episodes.count { it.key == "s2:e1" })
        // A season-less entry that is not a flat list stays unlocated.
        assertEquals(1, episodes.count { it.key.contains("unlocated") })
    }
}
