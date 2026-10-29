package com.kloudstream.desktop

import com.kloudstream.desktop.app.HomeModel
import com.kloudstream.desktop.library.WatchEntry
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import com.lagradost.cloudstream4.aggregation.DetailBinding
import com.lagradost.cloudstream4.aggregation.ProviderIds
import com.lagradost.cloudstream4.aggregation.RegisteredProvider
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.ProviderKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouTest {
    private val a = object : MainAPI() { override var name = "A"; override var mainUrl = "https://a.invalid" }
    private val b = object : MainAPI() { override var name = "B"; override var mainUrl = "https://b.invalid" }
    private val providers = listOf(a).map { RegisteredProvider(ProviderIds.of(it), ProviderKey(it.name, it.mainUrl), it) }
    private fun request(title: String, kind: MediaKind = MediaKind.Movie) = CanonicalDetailRequest("id:$title", title, 2020, kind,
        setOf(ExternalMediaId(ExternalIdNamespace.TmdbMovie, "${title.length + 100}")),
        listOf(a, b).map { DetailBinding(ProviderKey(it.name, it.mainUrl), "${it.mainUrl}/$title") }, aliases = setOf(title))
    private fun entry(title: String, positionMs: Long, durationMs: Long = 6_000_000, updatedAt: Long = 1, request: Boolean = true) =
        WatchEntry("id:$title", title, 2020, request = if (request) request(title) else null,
            positionMs = positionMs, durationMs = durationMs, updatedAt = updatedAt)

    @Test fun watchedWorksBecomeSeedsWithTheirInstalledSourcesAndEveryWatchedWorkIsExcluded() {
        val plan = HomeModel.historyPlan(listOf(entry("Watched", 600_000, updatedAt = 3),
            entry("Finished", 5_900_000, updatedAt = 2), entry("Opened", 10_000, updatedAt = 4),
            entry("Old format", 600_000, request = false)), providers)
        // Seconds-long launches are no signal; the most recent works lead.
        assertEquals(listOf("Watched", "Finished"), plan.seeds.map { it.title })
        // Suggestions come from the installed provider only, never from the work's other pages.
        assertEquals(listOf("A"), plan.seeds.first().sources.map { it.binding.provider.name })
        // Every page of every watched work, opened or not, keeps it out of the suggestions.
        assertEquals(6, plan.excluded.size)
    }
}
