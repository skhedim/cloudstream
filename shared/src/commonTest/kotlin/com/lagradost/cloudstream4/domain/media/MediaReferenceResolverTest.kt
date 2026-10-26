package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaReferenceResolverTest {
    private val id = ExternalMediaId(ExternalIdNamespace.TmdbTv,"1396")
    private val reference = MediaReference(id,"Breaking Bad",setOf("Le Chimiste"),2008,MediaKind.Series,setOf("tmdb-artwork:main.jpg","tmdb-artwork:alternate.jpg"))
    private fun candidate(name: String = "Breaking Bad", year: Int? = null, kind: MediaKind = MediaKind.Series,
        artwork: String = "tmdb-artwork:alternate.jpg", ids: Set<ExternalMediaId> = emptySet()) =
        MediaCandidate(ProviderBinding(ProviderKey("P","https://p.example"),name),name,year,kind,
            externalIds = ids,artworkIds = setOf(artwork))

    @Test fun verifiedArtworkResolvesMissingYearsAndErroneousSearchTypes() {
        val enriched = MediaReferenceResolver.enrich(candidate(kind = MediaKind.Movie),listOf(reference))
        assertEquals(MediaKind.Series,enriched.kind)
        assertEquals(2008,enriched.year)
        assertEquals(setOf(id),enriched.externalIds)
    }

    @Test fun localizedAndOriginalTitlesResolveToTheSameIdWithoutChangingTheBinding() {
        val original = candidate("Le Chimiste")
        val enriched = MediaReferenceResolver.enrich(original,listOf(reference))
        assertEquals("Breaking Bad",enriched.title)
        assertEquals(original.binding,enriched.binding)
        assertTrue("Le Chimiste" in enriched.alternateTitles)
        val main = candidate()
        assertEquals(1,MediaAggregator().aggregate(listOf(
            enriched,MediaReferenceResolver.enrich(main,listOf(reference)),
        )).size)
    }

    @Test fun missingYearsWithoutVerifiedArtworkNeverBecomeTitleOnlyMatches() {
        val unsupported = candidate(artwork = "different")
        assertEquals(unsupported,MediaReferenceResolver.enrich(unsupported,listOf(reference)))
        val known = unsupported.copy(year = 2008)
        assertEquals(setOf(id),MediaReferenceResolver.enrich(known,listOf(reference)).externalIds)
        val wrongFamily = known.copy(kind = MediaKind.Movie)
        assertEquals(wrongFamily,MediaReferenceResolver.enrich(wrongFamily,listOf(reference)))
    }

    @Test fun conflictingYearsIdsAndAmbiguousRemakesRemainUnresolved() {
        val remake = reference.copy(id = ExternalMediaId(ExternalIdNamespace.TmdbTv,"9999"),year = 2020)
        val unknown = candidate()
        assertEquals(unknown,MediaReferenceResolver.enrich(unknown,listOf(reference,remake)))
        val wrongYear = candidate(year = 2020)
        assertEquals(wrongYear,MediaReferenceResolver.enrich(wrongYear,listOf(reference)))
        val wrongId = candidate(ids = setOf(remake.id))
        assertEquals(wrongId,MediaReferenceResolver.enrich(wrongId,listOf(reference)))
    }

    @Test fun exactIdsGiveSeasonEntriesTheSeriesTitleAndKeepTheirScope() {
        val season = candidate("Breaking Bad Saison 2",ids = setOf(id)).copy(edition = setOf("season:2"))
        val enriched = MediaReferenceResolver.enrich(season,listOf(reference))
        assertEquals("Breaking Bad",enriched.title)
        assertEquals(season.edition,enriched.edition)
        assertEquals(1,MediaAggregator().aggregate(listOf(enriched,candidate(ids = setOf(id)))).size)
    }
}
