package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MediaAggregatorTest {
    private fun candidate(
        provider: String, title: String = "Breaking Bad", year: Int? = 2008,
        kind: MediaKind = MediaKind.Series, ids: Set<ExternalMediaId> = emptySet(),
        aliases: Set<String> = emptySet(), edition: Set<String> = emptySet(), url: String = "/show",
    ) = MediaCandidate(ProviderBinding(ProviderKey(provider, "https://$provider.example"), url),
        title, year, kind, aliases, ids, edition)

    private fun imdb(value: String) = ExternalMediaId(ExternalIdNamespace.Imdb, value)

    @Test fun threeProvidersBecomeOneCardWithoutLosingBindings() {
        val inputs = listOf(candidate("A"), candidate("B", "  BREAKING   BAD "), candidate("C"))
        val result = MediaAggregator().aggregate(inputs).single()
        assertEquals(inputs.map { it.binding }, result.bindings)
        assertEquals(setOf(MatchReason.TitleYearType), result.matchReasons)
    }

    @Test fun remakesAndDifferentTypesStaySeparate() {
        val result = MediaAggregator().aggregate(listOf(
            candidate("A", "The Office", 2001), candidate("B", "The Office", 2005),
            candidate("C", "The Office", 2005, MediaKind.Movie),
        ))
        assertEquals(3, result.size)
    }

    @Test fun unknownYearCannotBridgeRemakes() {
        val result = MediaAggregator().aggregate(listOf(
            candidate("A", "Dune", 1984, MediaKind.Movie),
            candidate("B", "Dune", null, MediaKind.Movie),
            candidate("C", "Dune", 2021, MediaKind.Movie),
        ))
        assertEquals(3, result.size)
    }

    @Test fun unknownTypeAndMissingYearsNeedExternalEvidence() {
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", year = null), candidate("B", year = null),
        )).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", kind = MediaKind.Unknown), candidate("B", kind = MediaKind.Unknown),
        )).size)
    }

    @Test fun externalIdMatchesTranslatedTitlesWithoutYears() {
        val ids = setOf(imdb("tt0903747"))
        val result = MediaAggregator().aggregate(listOf(
            candidate("A", "Breaking Bad", null, ids = ids),
            candidate("B", "Le Chimiste", null, ids = ids),
        )).single()
        assertEquals(2, result.bindings.size)
        assertEquals(setOf(MatchReason.ExternalId), result.matchReasons)
    }

    @Test fun conflictingIdsBlockTitleMatching() {
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", ids = setOf(imdb("tt1111111"))),
            candidate("B", ids = setOf(imdb("tt2222222"))),
        )).size)
    }

    @Test fun incompleteResultCannotTransitivelyMergeConflictingIds() {
        val inputs = listOf(candidate("A", ids = setOf(imdb("tt1111111"))), candidate("B"),
            candidate("C", ids = setOf(imdb("tt2222222"))))
        for (order in listOf(inputs, inputs.reversed(), listOf(inputs[0], inputs[2], inputs[1]))) {
            val groups = MediaAggregator().aggregate(order)
            assertTrue(groups.size >= 2)
            assertTrue(groups.all { group -> group.externalIds.count { it.namespace == ExternalIdNamespace.Imdb } <= 1 })
            assertEquals(3, groups.sumOf { it.bindings.size })
        }
    }

    @Test fun ambiguousTitleDoesNotArbitrarilySelectAWork() {
        val inputs = listOf(candidate("A", ids = setOf(imdb("tt1111111"))),
            candidate("B", ids = setOf(imdb("tt2222222"))), candidate("C"))
        assertEquals(3, MediaAggregator().aggregate(inputs).size)
    }

    @Test fun anExternalIdTakesPrecedenceOverAmbiguousTitleEvidence() {
        val inputs = listOf(candidate("A", ids = setOf(imdb("tt1111111"))),
            candidate("B", ids = setOf(imdb("tt2222222"))), candidate("C"),
            candidate("D", ids = setOf(imdb("tt1111111"))))
        val groups = MediaAggregator().aggregate(inputs)
        assertEquals(3, groups.size)
        assertEquals(listOf("A", "D"), groups.first().bindings.map { it.provider.name })
    }

    @Test fun tmdbNamespacesDoNotCollide() {
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", ids = setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "123"))),
            candidate("B", ids = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "123"))),
        )).size)
    }

    @Test fun seasonsJoinTheSeriesOnlyWithSameShowIdAndRetainScope() {
        val ids = setOf(imdb("tt0903747"))
        val result = MediaAggregator().aggregate(listOf(
            candidate("A", ids = ids), candidate("B", ids = ids, edition = setOf("season:2")),
            candidate("C", ids = ids, edition = setOf("season:3")),
        ))
        assertEquals(1, result.size)
        assertEquals(setOf("season:2"), result.single().bindingEditions[result.single().bindings[1]])
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A", ids = ids), candidate("B", ids = ids, edition = setOf("cour:2"))
        )).size)
        assertEquals(2, MediaAggregator().aggregate(listOf(
            candidate("A"), candidate("B", edition = setOf("season:2"))
        )).size)
    }

    @Test fun verifiedAlternateTitleCanMatchButNumbersAndAccentsArePreserved() {
        val result = MediaAggregator().aggregate(listOf(candidate("A"),
            candidate("B", "Le Chimiste", aliases = setOf("Breaking Bad"))))
        assertEquals(1, result.size)
        assertNotEquals(MediaAggregator.normalizeTitle("It 2"), MediaAggregator.normalizeTitle("It"))
        assertNotEquals(MediaAggregator.normalizeTitle("cote"), MediaAggregator.normalizeTitle("côté"))
    }

    @Test fun provenSeasonBindingsDoNotBlockMissingIdWholeShowArtworkMatchesOrLendSeasonProof() {
        val id = setOf(imdb("tt0903747"))
        val whole = candidate("A", ids = id).copy(artworkIds = setOf("tmdb-artwork:whole"))
        val season = candidate("B", ids = id, edition = setOf("season:2"))
            .copy(artworkIds = setOf("tmdb-artwork:season2"))
        val missing = candidate("C", year = null).copy(artworkIds = whole.artworkIds)
        assertEquals(1, MediaAggregator().aggregate(listOf(whole, season, missing)).size)
        val unprovenSeason = candidate("D", year = null, edition = setOf("season:3"))
            .copy(artworkIds = whole.artworkIds)
        assertEquals(2, MediaAggregator().aggregate(listOf(whole, season, missing, unprovenSeason)).size)
    }

    @Test fun repeatedPageDoesNotDuplicateBindingsAndNewProviderRetainsIdentity() {
        val aggregator = MediaAggregator()
        val first = candidate("B")
        val id = aggregator.aggregate(listOf(first)).single().id
        val next = aggregator.aggregate(listOf(candidate("A"), first, first)).single()
        assertEquals(id, next.id)
        assertEquals(2, next.bindings.size)
    }

    @Test fun sameProviderMayHaveSeveralBindingsAndSameUrlsAcrossProvidersRemainDistinct() {
        val inputs = listOf(candidate("A"), candidate("A", url = "/another"), candidate("B"))
        assertEquals(3, MediaAggregator().aggregate(inputs).single().bindings.size)
    }

    @Test fun splittingAGroupAfterNewEvidenceDoesNotReuseTheSameCardIdTwice() {
        val aggregator = MediaAggregator()
        aggregator.aggregate(listOf(candidate("A"), candidate("B")))
        val split = aggregator.aggregate(listOf(
            candidate("A", ids = setOf(imdb("tt1111111"))),
            candidate("B", ids = setOf(imdb("tt2222222"))),
        ))
        assertEquals(2, split.map { it.id }.distinct().size)
    }

    @Test fun snapshotsAreNotMutatedByFurtherAggregation() {
        val aggregator = MediaAggregator()
        val old = aggregator.aggregate(listOf(candidate("A"))).single()
        aggregator.aggregate(listOf(candidate("A"), candidate("B")))
        assertEquals(1, old.bindings.size)
        assertFalse(old.matchReasons.isNotEmpty())
    }
}
