package com.lagradost.cloudstream3

import android.app.Application
import com.lagradost.cloudstream3.ui.catalog.*
import com.lagradost.cloudstream3.ui.search.SearchScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SearchScopeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val film = object : MainAPI() {
        override var lang = "fr"
        override val supportedTypes = setOf(TvType.Movie)
    }
    private val series = object : MainAPI() {
        override var lang = "fr"
        override val supportedTypes = setOf(TvType.TvSeries)
    }
    @Before fun selection() {
        CatalogPreferences.save(ctx, CatalogSelection(setOf("fr"), setOf(TvType.Movie, TvType.TvSeries)))
    }
    @Test fun aggregatedScopeUsesCatalogueCategoriesAndLanguages() {
        val scope = SearchScope(ctx, setOf(TvType.Movie), true)
        assertTrue(scope.allowsProvider(film)); assertFalse(scope.allowsProvider(series))
        film.lang = "vi"
        assertFalse(scope.allowsProvider(film))
    }
    @Test fun unavailableRememberedCategoriesFallBackToTheCatalogue() {
        val scope = SearchScope(ctx, setOf(TvType.Audio), true)
        assertTrue(scope.allowsProvider(film)); assertTrue(scope.allowsProvider(series))
        assertFalse(scope.allowsItem(film, film.newMovieSearchResponse("Audio fixture", "/audio", TvType.Audio)))
    }
    @Test fun legacyProviderViewDoesNotApplyCatalogueOrResponseRules() {
        film.lang = "vi"
        val scope = SearchScope(ctx, emptySet(), false)
        assertTrue(scope.allowsProvider(film))
        assertTrue(scope.allowsItem(film, film.newMovieSearchResponse("Audio fixture", "/audio", TvType.Audio)))
        assertFalse(SearchScope(ctx, setOf(TvType.TvSeries), false).allowsProvider(film))
    }
}
