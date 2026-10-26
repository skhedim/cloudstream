package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.CanonicalDetailStore
import com.lagradost.cloudstream4.aggregation.DetailBinding
import com.lagradost.cloudstream4.aggregation.LoadedDetail
import android.app.Application
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.result.aggregation.*
import com.lagradost.cloudstream4.aggregation.SearchMediaMetadataCache
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.domain.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalDetailTest {
    private val apiA = provider("Detail A")
    private val apiB = provider("Detail B")
    private fun provider(label: String) = object : MainAPI() {
        override var name = label
        override var mainUrl = "https://${label.last().lowercaseChar()}.example"
    }
    private fun binding(api: MainAPI, editions: Set<String> = emptySet()) =
        DetailBinding(ProviderKey(api.name, api.mainUrl), "${api.mainUrl}/show", editions)
    private fun request(bindings: List<DetailBinding> = listOf(binding(apiA), binding(apiB))) =
        CanonicalDetailRequest("canonical:v1:TmdbTv:1396", "Breaking Bad", 2008, MediaKind.Series,
            emptySet(), bindings)
    private suspend fun series(api: MainAPI, episodes: List<Triple<Int?, Int?, String>>): LoadedDetail {
        val response = api.newTvSeriesLoadResponse("Provider title", "${api.mainUrl}/show", TvType.TvSeries,
            episodes.map { (s, n, payload) -> api.newEpisode(payload, initializer = { season = s; episode = n }, fix = false) })
        return LoadedDetail(binding(api), response, api)
    }
    private var previousContext: Any? = null
    @Before fun context() {
        val field = CloudStreamApp::class.java.getDeclaredField("_context").apply { isAccessible = true }
        previousContext = field.get(null)
        field.set(null, WeakReference(RuntimeEnvironment.getApplication()))
        // Robolectric gives each test fresh preferences, as a reinstalled app would have.
        com.lagradost.cloudstream4.aggregation.AggregationPlatform.store =
            com.lagradost.cloudstream3.ui.search.aggregation.AndroidKeyValueStore
    }
    @After fun restore() {
        CloudStreamApp::class.java.getDeclaredField("_context").apply { isAccessible = true }
            .set(null, previousContext)
        com.lagradost.cloudstream4.aggregation.AggregationPlatform.store =
            com.lagradost.cloudstream4.aggregation.InMemoryKeyValueStore()
    }

    @Test fun seriesUnionsSeasonsAndPreservesPayloadsWithoutMutatingProviderResponses() = runBlocking {
        val a = series(apiA, listOf(Triple(1, 1, "{\"opaque\":\"a\"}")))
        val b = series(apiB, listOf(Triple(1, 1, "opaque:b"), Triple(2, 1, "opaque:b:s2")))
        val detail = CanonicalDetail.create(request(), listOf(a, b))!!
        assertEquals(listOf(1, 2), detail.episodes.values.map { it.season })
        assertEquals(2, detail.episodes.values.first().bindings.size)
        assertEquals("{\"opaque\":\"a\"}", (a.response as TvSeriesLoadResponse).episodes.single().data)
        assertEquals("Provider title", a.response.name)
        assertEquals("Breaking Bad", detail.response.name)
        assertNotSame(a.response.syncData, detail.response.syncData)
        val reordered = CanonicalDetail.create(request().copy(bindings = request().bindings.reversed()), listOf(b, a))!!
        assertEquals(detail.response.getId(), reordered.response.getId())
        assertEquals(detail.episodes.values.map { it.key }, reordered.episodes.values.map { it.key })
    }

    @Test fun unknownNumberingAndConflictingSeasonScopesAreKeptSeparate() = runBlocking {
        val a = series(apiA, listOf(Triple(null, 1, "a"), Triple(1, null, "a2")))
        val b = series(apiB, listOf(Triple(null, 1, "b"), Triple(1, 1, "b2")))
        val scoped = b.copy(binding = binding(apiB, setOf("season:2")))
        val detail = CanonicalDetail.create(request(), listOf(a, scoped))!!
        assertEquals(4, detail.episodes.size)
        val rows = (detail.response as TvSeriesLoadResponse).episodes
        assertEquals(3, rows.count { it.season == -1 })
        assertEquals(4, rows.map { it.season to it.episode }.distinct().size)
    }

    @Test fun conflictingDetailIdsAndWrongResponseFamiliesCannotAddEpisodes() = runBlocking {
        val a = series(apiA, listOf(Triple(1, 1, "a")))
        val b = series(apiB, listOf(Triple(1, 2, "b")))
        b.response.apply { LoadResponse.run { addTMDbId("9999") } }
        SearchMediaMetadataCache.remember(apiB, b.binding.url, b.response)
        val expected = request().copy(externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "1396")))
        val detail = CanonicalDetail.create(expected, listOf(a, b))!!
        assertEquals(1, detail.loadedBindings)
        assertEquals(1, detail.rejectedBindings)
        assertTrue(detail.incomplete)
        assertEquals(1, detail.episodes.size)
        val movie = apiB.newMovieLoadResponse("Wrong family", b.binding.url, TvType.Movie, "payload")
        assertNull(CanonicalDetail.create(expected, listOf(b.copy(response = movie))))
    }

    @Test fun movieKeepsEveryBindingAndOriginalData() = runBlocking {
        val responses = listOf(apiA, apiB).map { api -> LoadedDetail(binding(api),
            api.newMovieLoadResponse("Film", "${api.mainUrl}/film", TvType.Movie, "opaque:${api.name}"), api) }
        val detail = CanonicalDetail.create(request().copy(kind = MediaKind.Movie), responses)!!
        assertEquals(2, detail.episodes.values.single().bindings.size)
        val token = (detail.response as MovieLoadResponse).dataUrl
        val resolved = detail.resolve(buildResultEpisode("Film", episode = 0, data = token, apiName = apiA.name,
            id = 10, parentId = 10, index = 0, tvType = TvType.Movie))
        assertEquals("opaque:${apiA.name}", resolved.data)
        assertEquals(10, resolved.id)
        assertEquals(2, resolved.sourceBindings.size)
    }

    @Test fun resolvedEpisodeRoutesToItsOwnProviderAndRetainsProgressAcrossProviderChanges() = runBlocking {
        val seen = mutableListOf<String>()
        val api = object : MainAPI() {
            override var name = "Routing fixture"
            override var mainUrl = "https://routing.example"
            override suspend fun loadLinks(data: String, isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
                seen += data
                return true
            }
        }
        val a = series(apiA, listOf(Triple(1, 1, "a")))
        val b = series(api, listOf(Triple(2, 1, "{\"opaque\":42}")))
        val detail = CanonicalDetail.create(request(), listOf(a, b))!!
        val row = (detail.response as TvSeriesLoadResponse).episodes.single { it.season == 2 }
        val episode = buildResultEpisode("Breaking Bad", episode = 1, season = 2, data = row.data,
            apiName = apiA.name, id = 20, parentId = detail.response.getId(), index = 0, tvType = TvType.TvSeries)
        val resolved = detail.resolve(episode)
        assertEquals(api.name, resolved.apiName)
        DataStoreHelper.setViewPos(resolved.id, 30_000, 60_000)
        val reordered = CanonicalDetail.create(request(), listOf(b, a))!!
        assertEquals(resolved.id, reordered.resolve(episode).id)
        assertEquals(30_000L, reordered.resolve(episode).position)
        APIHolder.addPluginMapping(api)
        val decoy = object : MainAPI() {
            override var name = api.name
            override var mainUrl = "https://different-provider.example"
            override suspend fun loadLinks(data: String, isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
                seen += "wrong-provider"
                return false
            }
        }
        APIHolder.addPluginMapping(decoy)
        try {
            RepoLinkGenerator(listOf(resolved)).generateLinks(true, emptySet(), {}, {}, 0, false)
            assertEquals(listOf("{\"opaque\":42}"), seen)
        } finally { APIHolder.removePluginMapping(decoy); APIHolder.removePluginMapping(api) }
        assertEquals(resolved, parseJson<com.lagradost.cloudstream3.ui.result.ResultEpisode>(resolved.toJsonLiteral()))
    }

    @Test fun navigationAndStoredAliasesSurviveSerializationAndPinLearnedIdentity() {
        val first = request().copy(identity = "canonical:v1:title-year")
        val restored = parseJson<CanonicalDetailRequest>(first.toJsonLiteral())
        assertEquals(first, restored)
        val pinned = CanonicalDetailStore.remember(restored)
        assertEquals(first, CanonicalDetailStore.find(apiB.name, binding(apiB).url))
        assertEquals(pinned.identity, CanonicalDetailStore.remember(request()).identity)
        assertEquals(request().bindings, CanonicalDetailStore.find(apiA.name, binding(apiA).url)!!.bindings)
    }

    @Test fun externalIdAliasesKeepTheContextWhenANewProviderBecomesRepresentativeButNeverJoinCours() {
        val id = ExternalMediaId(ExternalIdNamespace.TmdbTv, "424242")
        val first = request(listOf(binding(apiA))).copy(identity = "first-pinned-identity", externalIds = setOf(id))
        CanonicalDetailStore.remember(first)
        val next = CanonicalDetailStore.pin(request(listOf(binding(apiB))).copy(externalIds = setOf(id)))
        assertEquals(first.identity, next.identity)
        assertEquals(2, next.bindings.size)
        val cour = CanonicalDetailStore.pin(request(listOf(binding(apiB))).copy(externalIds = setOf(id), edition = setOf("cour:2")))
        assertNotEquals(first.identity, cour.identity)
        assertEquals(1, cour.bindings.size)
    }

    @Test fun savedAliasesWithConflictingIdsOrYearsCannotResetAnotherTitlesIdentity() {
        val old = request(listOf(binding(apiA))).copy(identity = "old-homonym", year = 1984,
            externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "123123")))
        CanonicalDetailStore.remember(old)
        val remake = CanonicalDetailStore.pin(request(listOf(binding(apiA))))
        assertNotEquals(old.identity, remake.identity)
        val conflict = CanonicalDetailStore.pin(old.copy(identity = "corrected-id",
            externalIds = setOf(ExternalMediaId(ExternalIdNamespace.TmdbTv, "321321"))))
        assertEquals("corrected-id", conflict.identity)
    }

    @Test fun vfAndVostBecomeOneEpisodeWithVersionedSourcesAndNoDetailLanguageSelector() = runBlocking {
        apiA.lang = "fr"
        val anime = apiA.newAnimeLoadResponse("Fixture", binding(apiA).url, TvType.Anime) {
            episodes[DubStatus.Dubbed] = listOf(apiA.newEpisode("vf", { season = 1; episode = 1 }, fix = false))
            episodes[DubStatus.Subbed] = listOf(apiA.newEpisode("vost", { season = 1; episode = 1 }, fix = false))
        }
        val b = series(apiB, listOf(Triple(1, 1, "unknown")))
        val detail = CanonicalDetail.create(request(), listOf(LoadedDetail(binding(apiA), anime, apiA), b))!!
        assertEquals(1, detail.episodes.size)
        assertEquals(setOf("None", "Dubbed", "Subbed"), detail.episodes.values.single().tracks)
        val projected = detail.response as AnimeLoadResponse
        assertEquals(1, projected.seasonNames!!.single().displaySeason)
        assertEquals(setOf(DubStatus.None), projected.episodes.keys)
        val row = projected.episodes.values.single().single()
        val resolved = detail.resolve(buildResultEpisode("Fixture", episode = 1, season = 1,
            data = row.data, apiName = apiA.name, id = 1, parentId = detail.response.getId(), index = 0, tvType = TvType.Anime))
        assertEquals(3, resolved.sourceBindings.size)
        assertEquals(setOf("vf", "vost", "unknown"), resolved.sourceBindings.map { it.data }.toSet())
        assertEquals("fr", resolved.sourceBindings.first { it.data == "vf" }.language)
    }

    @Test fun milestone3VersionProgressMigratesToTheCommonEpisodeWithoutOverwritingNewProgress() = runBlocking {
        val detail = CanonicalDetail.create(request(), listOf(series(apiA, listOf(Triple(1, 1, "fixture")))))!!
        val row = (detail.response as TvSeriesLoadResponse).episodes.single()
        val episode = buildResultEpisode("Fixture", episode = 1, season = 1, data = row.data,
            apiName = apiA.name, id = 0, parentId = detail.response.getId(), index = 0, tvType = TvType.TvSeries)
        val legacy = "${request().identity}:Dubbed:s1:e1".hashCode()
        DataStoreHelper.setViewPos(legacy, 40_000, 60_000)
        DataStoreHelper.setVideoWatchState(legacy, VideoWatchState.Watched)
        val resolved = detail.resolve(episode)
        assertNotEquals(legacy, resolved.id)
        assertEquals(40_000L, resolved.position)
        assertEquals(VideoWatchState.Watched, resolved.videoWatchState)
        assertTrue(legacy in resolved.legacyIds)
        DataStoreHelper.setViewPos(resolved.id, 70_000, 120_000)
        assertEquals(70_000L, detail.resolve(episode).position)
        DataStoreHelper.setVideoWatchState(resolved.id, VideoWatchState.None)
        assertEquals(VideoWatchState.None, detail.resolve(episode).videoWatchState)
    }
}
