package com.lagradost.cloudstream4.domain.media

import kotlin.test.*

class EpisodeAggregatorTest {
    private fun episode(provider: String, season: Int?, episode: Int?, track: String = "None",
                        index: Int = 0, scope: Int? = null, data: String = "opaque:$provider:$index") =
        EpisodeCandidate(EpisodeProviderBinding(ProviderBinding(ProviderKey(provider, "https://$provider.example"),
            "/show"), data), season, episode, track, index, scope)

    @Test fun mergesNumberedEpisodesAndRetainsEveryOpaqueBinding() {
        val inputs = listOf(episode("A", 1, 1), episode("B", 1, 1), episode("B", 1, 2, index = 1))
        val result = EpisodeAggregator.aggregate(inputs)
        assertEquals(2, result.size)
        assertEquals(inputs.take(2).map { it.binding }, result.first().bindings)
        assertEquals(3, result.sumOf { it.bindings.size })
    }

    @Test fun seasonsAndSpecialsStayDistinctWhileLanguagesBelongToBindings() {
        val result = EpisodeAggregator.aggregate(listOf(
            episode("A", 0, 1), episode("A", 1, 1), episode("A", 2, 1),
            episode("A", 1, 1, "Subbed"), episode("A", 1, 1, "Dubbed")))
        assertEquals(3, result.size)
        assertEquals(3, result.map { it.key }.distinct().size)
        assertEquals(setOf("None", "Subbed", "Dubbed"), result.single { it.season == 1 }.tracks)
    }

    @Test fun scopedSeasonFillsMissingSeasonWithoutGuessingEpisodeNumbers() {
        val result = EpisodeAggregator.aggregate(listOf(
            episode("A", null, 3, scope = 2), episode("B", 2, 3),
            episode("A", null, null, index = 1, scope = 2), episode("B", 2, null, index = 1)))
        assertEquals(3, result.size)
        assertEquals(2, result.first { it.episode == 3 }.bindings.size)
    }

    @Test fun missingSeasonOrEpisodeNeverMergesByListIndexAndNeverDisappears() {
        val result = EpisodeAggregator.aggregate(listOf(
            episode("A", null, 1), episode("B", null, 1),
            episode("A", 1, null, index = 1), episode("B", 1, null, index = 1)))
        assertEquals(4, result.size)
        assertTrue(result.all { it.bindings.size == 1 })
    }

    @Test fun repeatedSnapshotsAndProviderOrderingKeepNumberedEpisodeKeysStable() {
        val inputs = listOf(episode("A", 1, 1), episode("B", 1, 1), episode("B", 2, 1))
        assertEquals(EpisodeAggregator.aggregate(inputs).map { it.key },
            EpisodeAggregator.aggregate(inputs.reversed() + inputs).map { it.key })
        assertEquals(3, EpisodeAggregator.aggregate(inputs + inputs).sumOf { it.bindings.size })
    }

    @Test fun mediaIdentityDoesNotUseEphemeralSearchNumberOrProviderOrder() {
        val bindings = listOf(episode("A", 1, 1).binding.media, episode("B", 1, 1).binding.media)
        val media = CanonicalMedia(1, "Breaking Bad", 2008, MediaKind.Series,
            setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "1396")), bindings, emptySet())
        assertEquals(CanonicalIdentity.key(media), CanonicalIdentity.key(media.copy(id = 42,
            title = "Titre traduit", bindings = bindings.reversed())))
        assertNotEquals(CanonicalIdentity.key(media), CanonicalIdentity.key(media.copy(
            externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "1396")))))
        assertNotEquals(CanonicalIdentity.key(media), CanonicalIdentity.key(media.copy(
            bindingEditions = mapOf(bindings.first() to setOf("cour:2")))))
        assertEquals(CanonicalIdentity.key(media), CanonicalIdentity.key(media.copy(
            bindingEditions = mapOf(bindings.first() to setOf("season:2")))))
    }

    @Test fun numberingLabelsSupplyCoordinatesButEpisodeTitlesAndListPositionsDoNot() {
        assertEquals(EpisodeNumbers(2, 3), EpisodeNumbering.fromLabel("S02E03 - Pilot"))
        assertEquals(EpisodeNumbers(null, 4), EpisodeNumbering.fromLabel("Épisode 04"))
        assertEquals(EpisodeNumbers(null, 7), EpisodeNumbering.fromLabel("Episode 7 : A title"))
        assertEquals(EpisodeNumbers(null, 7), EpisodeNumbering.fromLabel("Episode 7 VF 🇫🇷"))
        for (label in listOf("Pilot", "1", "24 heures", "Episode 0", "Season 2", "Episode 1 and 2", "S01E02E03")) {
            assertNull(EpisodeNumbering.fromLabel(label), label)
        }
    }
}
