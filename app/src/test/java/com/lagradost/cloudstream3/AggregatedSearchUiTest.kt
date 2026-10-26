package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.SearchMediaMapper
import com.lagradost.cloudstream3.ui.search.aggregation.aggregate
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.ProviderKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/** Runs entirely on the host JVM: no emulator, installed plugins, or provider network requests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
// The app ships Android Conscrypt JNI; these UI tests use the host JDK's TLS provider.
@ConscryptMode(ConscryptMode.Mode.OFF)
class AggregatedSearchUiTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.setup()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun card(count: Int = 3): AggregatedSearchResponse {
        val results = (1..count).map { index ->
            val api = object : MainAPI() {
                override var name = "Provider $index"
                override var mainUrl = "https://provider$index.example"
            }
            ProviderKey(api.name, api.mainUrl) to api.newTvSeriesSearchResponse("Breaking Bad", "/show") { year = 2008 }
        }
        return SearchMediaMapper.aggregate(MediaAggregator(), results).single() as AggregatedSearchResponse
    }

    @Test fun groupedCardCarriesAllBindingsWithoutAProviderDialog() {
        val card = card()
        val adapter = SearchAdapter(AutofitRecyclerView(activity)) { }
        adapter.submitList(listOf(card))
        assertEquals(1, adapter.itemCount)
        assertEquals(3, card.media.bindings.size)
        val callback = SearchClickCallback(SEARCH_ACTION_LOAD, View(activity), 0, card)
        val navigation = SearchHelper.navigationCallback(callback)
        assertSame(card, navigation.card)
        val bundle = com.lagradost.cloudstream3.ui.result.ResultFragment.newInstance(navigation.card)
        val request = com.lagradost.cloudstream3.utils.AppUtils.parseJson<
            com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest>(bundle.getString("canonicalDetailV1")!!)
        assertEquals(3, request.bindings.size)
        assertEquals("https://provider1.example/show", navigation.card.url)
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test fun focusKeepsCanonicalCardAndLongPressDoesNotExposeAProviderPopup() {
        val card = card()
        val focus = SearchClickCallback(SEARCH_ACTION_FOCUSED, View(activity), 0, card)
        assertSame(focus, SearchHelper.navigationCallback(focus))
        val longPress = SearchClickCallback(SEARCH_ACTION_SHOW_METADATA, View(activity), 0, card)
        val navigation = SearchHelper.navigationCallback(longPress)
        assertEquals(SEARCH_ACTION_LOAD, navigation.action)
        assertSame(card, navigation.card)
        val legacy = SearchClickCallback(SEARCH_ACTION_SHOW_METADATA, View(activity), 0, card.representative)
        assertSame(legacy, SearchHelper.navigationCallback(legacy))
    }

    @Test fun pagingButtonCanRetryEvenWhenMoreBindingsDidNotIncreaseTheCardCount() {
        var calls = 0
        val adapter = SearchAdapter(AutofitRecyclerView(activity), loadMore = { calls++ }) { }
        val holder = adapter.onCreateFooter(FrameLayout(activity))
        val button = holder.itemView.findViewById<MaterialButton>(R.id.load_more)
        adapter.updatePaging(true, false, true)
        adapter.onBindFooter(holder)
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()
        adapter.updatePaging(true, true)
        adapter.onBindFooter(holder)
        assertFalse(button.isEnabled)
        adapter.updatePaging(true, false, false)
        adapter.onBindFooter(holder)
        button.performClick()
        assertEquals(2, calls)
        adapter.updatePaging(false, false)
        adapter.onBindFooter(holder)
        assertEquals(View.GONE, button.visibility)
    }

    @Test fun bothPosterLayoutsShowYearWithoutProvidersAndClearItWhenRecycled() {
        val card = card()
        SearchResultBuilder.updateCache(activity)
        for (layout in listOf(R.layout.search_result_grid, R.layout.search_result_grid_expanded)) {
            val view = LayoutInflater.from(activity).inflate(layout, FrameLayout(activity), false)
            SearchResultBuilder.bind({}, card, 0, view)
            val summary = view.findViewById<TextView>(R.id.search_media_info)
            assertEquals(View.VISIBLE, summary.visibility)
            assertEquals("2008", summary.text.toString())
            assertFalse(summary.text.contains("Provider"))
            var clicked: SearchResponse? = null
            SearchResultBuilder.bind({ clicked = it.card }, card, 0, view)
            view.performClick()
            assertSame(card, clicked)
            SearchResultBuilder.bind({}, card.representative, 0, view)
            assertEquals(View.GONE, summary.visibility)
        }
    }
}
