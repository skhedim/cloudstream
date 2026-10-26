package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataEnrichmentPlannerTest {
    private fun candidate(index: Int, year: Int? = null, title: String = "Same title") =
        MediaCandidate(ProviderBinding(ProviderKey("P$index", "https://$index.example"), "/film"), title, year, MediaKind.Movie)
    private fun plan(planner: MetadataEnrichmentPlanner, inputs: List<MediaCandidate>) =
        planner.next(inputs, MediaAggregator().aggregate(inputs))

    @Test fun onlyUnresolvedDuplicatesWithMissingMetadataAreFetched() {
        val missing = candidate(1)
        val known = candidate(2, 2020)
        val unique = candidate(3, title = "Unique")
        assertEquals(listOf(missing.binding),plan(MetadataEnrichmentPlanner(),listOf(missing,known,unique)))
    }

    @Test fun resolvedArtworkGroupsAndKnownRemakesNeedNoExtraCalls() {
        val artwork = setOf("tmdb-artwork:same.jpg")
        assertTrue(plan(MetadataEnrichmentPlanner(),listOf(candidate(1).copy(artworkIds = artwork), candidate(2).copy(artworkIds = artwork))).isEmpty())
        assertTrue(plan(MetadataEnrichmentPlanner(),listOf(candidate(1,1984), candidate(2,2021))).isEmpty())
    }

    @Test fun budgetAndAttemptsAreSharedAcrossAllPagesOfTheQuery() {
        val planner = MetadataEnrichmentPlanner(3)
        assertEquals(2,plan(planner,listOf(candidate(1),candidate(2))).size)
        assertEquals(listOf(candidate(3).binding),plan(planner,(1..6).map { candidate(it) }))
        assertTrue(plan(planner,(1..10).map { candidate(it) }).isEmpty())
        assertEquals(10,plan(MetadataEnrichmentPlanner(),(1..10).map { candidate(it) }).size)
    }

    @Test fun typeDisagreementsCanBeVerifiedButCompletelyUnknownTypesStaySeparate() {
        assertEquals(2,plan(MetadataEnrichmentPlanner(),listOf(candidate(1),candidate(2).copy(kind = MediaKind.Series))).size)
        assertTrue(plan(MetadataEnrichmentPlanner(),listOf(candidate(1).copy(kind = MediaKind.Unknown),candidate(2).copy(kind = MediaKind.Unknown))).isEmpty())
    }
}
