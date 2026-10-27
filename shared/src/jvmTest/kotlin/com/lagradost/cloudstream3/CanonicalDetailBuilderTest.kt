package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream4.aggregation.*
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CanonicalDetailBuilderTest {
    private fun api(label: String) = object : MainAPI() {
        override var name = label
        override var mainUrl = "https://${label.lowercase()}.example"
    }
    private val a = api("A"); private val b = api("B"); private val c = api("C")
    private fun binding(api: MainAPI, editions: Set<String> = emptySet()) =
        DetailBinding(ProviderKey(api.name, api.mainUrl), "${api.mainUrl}/show", editions)
    private fun request(vararg apis: MainAPI, aliases: Set<String> = setOf("Breaking Bad", "Le Chimiste")) =
        CanonicalDetailRequest("fixture", "Breaking Bad", 2008, MediaKind.Series, emptySet(),
            apis.map { binding(it) }, aliases = aliases)
    private suspend fun series(api: MainAPI, title: String = "Breaking Bad", year: Int? = 2008, tmdb: String? = null,
        episodes: List<Pair<Int?, Int>> = listOf(1 to 1)): LoadedDetail {
        val response = api.newTvSeriesLoadResponse(title, "${api.mainUrl}/show", TvType.TvSeries,
            episodes.map { (s, n) -> api.newEpisode("${api.name}:$s:$n", initializer = { season = s; episode = n }, fix = false) }) {
            this.year = year
            tmdb?.let { addTMDbId(it) }
        }
        return LoadedDetail(binding(api), response, api)
    }

    @Test fun pagesOfAnotherWorkAreRejectedByYearOrTitleUnlessAnIdIsShared() = runBlocking {
        val model = CanonicalDetailBuilder.build(request(a, b, c), listOf(
            series(a), series(b, title = "Breaking Bad", year = 2015), series(c, title = "Breaking In", year = null),
        ))!!
        assertEquals(listOf(a.name), model.compatible.map { it.api.name })
        assertEquals(setOf("year", "title"), model.rejected.map { it.reason }.toSet())
        // Translated title with the same year, or a shared ID despite a different title, is accepted.
        val translated = CanonicalDetailBuilder.build(request(a, b, c), listOf(series(a, tmdb = "1396"),
            series(b, title = "Le Chimiste"), series(c, title = "Totally different", year = 2009, tmdb = "1396")))!!
        assertEquals(3, translated.compatible.size)
        assertTrue(ExternalMediaId(ExternalIdNamespace.TmdbTv, "1396") in translated.knownIds)
    }

    @Test fun catalogueLabelsAndYearsInDetailTitlesAreNotTitleConflicts() = runBlocking {
        val model = CanonicalDetailBuilder.build(request(a, b, c), listOf(series(a),
            series(b, title = "Breaking Bad - série", year = null), series(c, title = "Breaking Bad (2008) [VF]", year = null)))!!
        assertEquals(3, model.compatible.size)
    }

    @Test fun learnedIdsAreDecidedByMajorityIndependentlyOfCompletionOrder() = runBlocking {
        val pages = listOf(series(a, tmdb = "1"), series(b, tmdb = "2"), series(c, tmdb = "2"))
        for (order in listOf(pages, pages.reversed(), listOf(pages[1], pages[0], pages[2]))) {
            val model = CanonicalDetailBuilder.build(request(a, b, c), order)!!
            assertEquals(setOf(b.name, c.name), model.compatible.map { it.api.name }.toSet())
            assertEquals("minority-id", model.rejected.single().reason)
        }
    }

    @Test fun legacyContextsWithoutRecordedTitlesOnlyCheckTheYear() = runBlocking {
        val model = CanonicalDetailBuilder.build(request(a, b, aliases = emptySet()),
            listOf(series(a), series(b, title = "Whatever the provider calls it", year = null)))!!
        assertEquals(2, model.compatible.size)
    }

    @Test fun flatListsBecomeOneAbsoluteSeasonAndJoinSeasonsOnlyWithinTheFirstOne() = runBlocking {
        val flat = CanonicalDetailBuilder.build(request(a, b), listOf(
            series(a, episodes = listOf(null to 1, null to 2)), series(b, episodes = listOf(null to 2, null to 3))))!!
        assertTrue(flat.absoluteOnly)
        assertEquals(listOf("abs:e1", "abs:e2", "abs:e3"), flat.episodes.map { it.episode.key })
        assertEquals(2, flat.episodes.single { it.episode.key == "abs:e2" }.episode.bindings.size)
        // An anime catalogue's absolute list next to a season list: 1–2 are S1E1–S1E2, 3 is not guessed.
        val mixed = CanonicalDetailBuilder.build(request(a, b), listOf(
            series(a, episodes = listOf(null to 1, null to 2, null to 3)), series(b, episodes = listOf(1 to 1, 1 to 2))))!!
        assertFalse(mixed.absoluteOnly)
        assertEquals(setOf("s1:e1", "s1:e2", "abs:e3"), mixed.episodes.map { it.episode.key }.toSet())
        assertEquals(2, mixed.episodes.single { it.episode.key == "s1:e1" }.episode.bindings.size)
    }

    @Test fun providerLimiterBoundsEachProviderAndTheTotal() = runBlocking {
        val limiter = ProviderLimiter(global = 3, perProvider = 1)
        val active = mutableMapOf<String, AtomicInteger>(); val total = AtomicInteger()
        var peakProvider = 0; var peakTotal = 0
        kotlinx.coroutines.coroutineScope {
            (1..12).map { i ->
                val provider = "P${i % 4}"
                launch {
                    limiter.withPermit(provider) {
                        val p = synchronized(active) { active.getOrPut(provider) { AtomicInteger() } }.incrementAndGet()
                        val t = total.incrementAndGet()
                        synchronized(active) { peakProvider = maxOf(peakProvider, p); peakTotal = maxOf(peakTotal, t) }
                        delay(5)
                        p.let { synchronized(active) { active.getValue(provider).decrementAndGet() } }
                        total.decrementAndGet()
                    }
                }
            }
        }
        assertEquals(1, peakProvider)
        assertEquals(3, peakTotal)
    }

    @Test fun savedBindingsResolveByStableIdThenByNameAndDomainButNeverByAnAmbiguousName() {
        val twin = api("A").apply { mainUrl = "https://mirror.example" }
        val registry = object : SourceRegistry {
            override fun providers() = listOf(a, twin, b).map { RegisteredProvider(ProviderIds.of(it) + it.mainUrl, ProviderKey(it.name, it.mainUrl), it) }
        }
        val idOfTwin = ProviderIds.of(twin) + twin.mainUrl
        assertSame(twin, registry.resolve(ProviderKey("A", "https://moved.example"), idOfTwin)!!.api)
        assertSame(a, registry.resolve(ProviderKey("A", a.mainUrl))!!.api)
        assertNull(registry.resolve(ProviderKey("A", "https://moved.example")))
        assertSame(b, registry.resolve(ProviderKey("B", "https://moved.example"))!!.api)
    }
}
