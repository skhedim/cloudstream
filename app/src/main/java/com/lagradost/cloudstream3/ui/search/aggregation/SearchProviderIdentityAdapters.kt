package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.ProviderKey
import java.net.URI

/** Optional audited contracts above the extension API. Never infer namespaces from generic slugs.
 * Sources reviewed for the installed versions (Movix 33, AfterDark 110, AMSC Frembed 1):
 * https://github.com/Kraptor123/Cs-Karma/blob/HEAD/Movix/src/main/kotlin/com/byayzen/Movix.kt
 * https://github.com/yorik100/Cloudstream/blob/HEAD/AfterDark/src/main/kotlin/com/afterdark/cloudstream/AfterDarkProvider.kt
 * https://github.com/mouradchaouche/cloudstream-frenchrepo/blob/HEAD/Frembed/src/main/kotlin/com/lagradost/Frembed.kt
 * All three construct these exact routes from TMDB search IDs. Other implementations opt out.
 */
internal object SearchProviderIdentityAdapters {
    private val contracts = mapOf(
        "com.byayzen.Movix" to Regex("^/(movie|tv)/([1-9][0-9]*)/?$"),
        "com.afterdark.cloudstream.AfterDarkProvider" to Regex("^/watch/(movie|tv)-([1-9][0-9]*)/?$"),
        "com.lagradost.Frembed" to Regex("^/(movies|tv-show)/[^/]+/([1-9][0-9]*)/?$"),
    )

    fun externalIds(providerClass: String?, provider: ProviderKey, response: SearchResponse, kind: MediaKind): Set<ExternalMediaId> {
        val contract = contracts[providerClass] ?: return emptySet()
        // A changed/unsupported protocol degrades to ordinary matching rather than guessing.
        if (SearchIdentityEvidence.artworkIds(response.posterUrl).isEmpty()) return emptySet()
        return runCatching {
            val base = URI(provider.mainUrl)
            val uri = URI(response.url)
            if (uri.scheme !in setOf("https", "http") || uri.scheme != base.scheme ||
                uri.host.isNullOrBlank() || uri.host != base.host || uri.port != base.port ||
                uri.userInfo != null || uri.query != null || uri.fragment != null) return emptySet()
            val match = contract.matchEntire(uri.path) ?: return emptySet()
            val movie = match.groupValues[1] in setOf("movie", "movies")
            if (MediaAggregator.family(kind) != if (movie) "movie" else "series") return emptySet()
            setOf(ExternalMediaId(
                if (movie) ExternalIdNamespace.TmdbMovie else ExternalIdNamespace.TmdbTv,
                match.groupValues[2],
            ))
        }.getOrDefault(emptySet())
    }
}
