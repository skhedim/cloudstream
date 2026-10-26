package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkMatchingTest {
    private fun candidate(provider: String, year: Int? = null, title: String = "Breaking in Badly",
        artwork: Set<String> = setOf("tmdb-artwork:shared.jpg"), kind: MediaKind = MediaKind.Movie,
        ids: Set<ExternalMediaId> = emptySet(), edition: Set<String> = emptySet(),
    ) = MediaCandidate(ProviderBinding(ProviderKey(provider, "https://$provider.example"), "/film"),
        title, year, kind, externalIds = ids, edition = edition, artworkIds = artwork)

    @Test fun realProviderMissingYearPatternMergesAgainstTheMetadataRichResult() {
        val a = candidate("Frembed")
        val b = candidate("AfterDark", 2025)
        val c = candidate("Movix")
        for (order in listOf(listOf(a,b,c), listOf(c,a,b), listOf(b,c,a))) {
            val card = MediaAggregator().aggregate(order).single()
            assertEquals(3, card.bindings.size)
            assertEquals(2025, card.year)
            assertTrue(MatchReason.TitleArtworkType in card.matchReasons)
        }
    }

    @Test fun twoMissingYearsStillRequireIndependentArtworkEvidence() {
        assertEquals(1, MediaAggregator().aggregate(listOf(candidate("A"), candidate("B"))).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(candidate("A"), candidate("B", artwork = emptySet()))).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(candidate("A"), candidate("B", artwork = setOf("other")))).size)
    }

    @Test fun artworkDoesNotOverrideDifferentTitlesTypesYearsEditionsOrIds() {
        val first = candidate("A", 2000)
        val conflicts = listOf(
            candidate("B", 2020), candidate("B", title = "Other Film"),
            candidate("B", kind = MediaKind.Series), candidate("B", edition = setOf("season:2")),
        )
        conflicts.forEach { assertEquals(2, MediaAggregator().aggregate(listOf(first, it)).size) }
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", ids = setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "1"))),
            candidate("B", ids = setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "2"))),
        )).size)
    }

    @Test fun reusedArtworkCannotAttachMissingYearToAnArbitraryRemake() {
        val old = candidate("Old", 1984, "Dune")
        val remake = candidate("New", 2021, "Dune")
        val unknown = candidate("Unknown", title = "Dune")
        for (order in listOf(listOf(old,unknown,remake),listOf(unknown,old,remake),listOf(remake,unknown,old))) {
            assertEquals(3, MediaAggregator().aggregate(order).size)
        }
    }

    @Test fun punctuationAndAnimationCategoriesDoNotHideTheSameWork() {
        assertEquals(1, MediaAggregator().aggregate(listOf(
            candidate("A", 2020, "Film: The Return", kind = MediaKind.Movie),
            candidate("B", 2020, "Film — The Return", kind = MediaKind.AnimeMovie),
        )).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", 2020, "Film 2"), candidate("B", 2020, "Film"),
        )).size)
    }

    @Test fun enrichmentMergeRetainsCardIdentityAndOriginalRank() {
        val aggregator = MediaAggregator()
        val a = candidate("A", artwork = emptySet())
        val b = candidate("B", 2020, artwork = emptySet())
        val before = aggregator.aggregate(listOf(a,b))
        val after = aggregator.aggregate(listOf(a.copy(year = 2020),b)).single()
        assertEquals(before.first().id,after.id)
        assertEquals(listOf(a.binding,b.binding),after.bindings)
    }
}
