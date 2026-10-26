package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkSignatureTest {
    private fun signature(shift: Int = 0, inverted: Boolean = false): ArtworkSignature {
        val samples = (0 until ArtworkSignature.WIDTH*ArtworkSignature.HEIGHT).flatMap { pixel ->
            val value = ((if (inverted) 383-pixel else pixel)*255/383+shift).coerceIn(0,255)
            listOf(value,value,value)
        }
        return ArtworkSignature(samples,2.0/3)
    }
    private fun candidate(index: Int, year: Int? = null, visual: ArtworkSignature? = signature(), title: String = "Breaking Bad") =
        MediaCandidate(ProviderBinding(ProviderKey("P$index","https://p$index.example"),"/show"),title,year,MediaKind.Series,
            artworkIds = setOf("tmdb-artwork:asset$index.jpg"),artworkSignature = visual)

    @Test fun smallEncodingDifferencesMatchButUnrelatedOrBlankImagesDoNot() {
        assertTrue(signature().equivalent(signature(4)))
        assertFalse(signature().equivalent(signature(20)))
        assertFalse(signature().equivalent(signature(inverted = true)))
        val blank = ArtworkSignature(List(ArtworkSignature.WIDTH*ArtworkSignature.HEIGHT*3) { 64 },2.0/3)
        assertFalse(blank.equivalent(blank))
        assertFalse(signature().equivalent(signature().copy(aspectRatio = 1.0)))
    }

    @Test fun sameTitleAndVisualPosterCanJoinDifferentAssetNamesWithoutIgnoringGuards() {
        val a = candidate(1,2008)
        val b = candidate(2,visual = signature(4))
        val card = MediaAggregator().aggregate(listOf(a,b)).single()
        assertTrue(MatchReason.TitleVisualArtworkType in card.matchReasons)
        assertEquals(2,card.bindings.size)
        assertEquals(2,MediaAggregator().aggregate(listOf(a,b.copy(title = "Other Show"))).size)
        assertEquals(2,MediaAggregator().aggregate(listOf(a,b.copy(year = 2020))).size)
        assertEquals(2,MediaAggregator().aggregate(listOf(a,b.copy(edition = setOf("season:2")))).size)
        val knownA = a.copy(externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv,"1")))
        val knownB = b.copy(externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv,"2")))
        assertEquals(2,MediaAggregator().aggregate(listOf(knownA,knownB)).size)
    }

    @Test fun ambiguousRemakesAreNotBridgedByIdenticalVisualPosters() {
        val inputs = listOf(candidate(1,1984),candidate(2,2021),candidate(3))
        for (order in listOf(inputs,inputs.reversed())) assertEquals(3,MediaAggregator().aggregate(order).size)
    }

    @Test fun onlyUnresolvedMissingYearPostersConsumeTheQueryBudget() {
        val inputs = (1..10).map { candidate(it,visual = null) }
        val planner = VisualArtworkPlanner(3)
        assertEquals(3,planner.next(inputs,MediaAggregator().aggregate(inputs)).size)
        assertTrue(planner.next(inputs,MediaAggregator().aggregate(inputs)).isEmpty())
        val remakes = listOf(candidate(1,1984,visual = null),candidate(2,2021,visual = null))
        assertTrue(VisualArtworkPlanner().next(remakes,MediaAggregator().aggregate(remakes)).isEmpty())
    }
}
