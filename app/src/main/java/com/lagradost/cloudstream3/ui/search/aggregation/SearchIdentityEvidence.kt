package com.lagradost.cloudstream3.ui.search.aggregation

import com.lagradost.cloudstream4.domain.media.ExternalIdNamespace
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import java.net.URI

/** Evidence from canonical hosts only. Provider IDs, slugs and JSON payloads remain opaque. */
object SearchIdentityEvidence {
    fun externalIds(url: String): Set<ExternalMediaId> = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return emptySet()
        val match = when (uri.host?.lowercase()) {
            "themoviedb.org", "www.themoviedb.org" -> {
                val parts = Regex("^/(movie|tv)/([1-9][0-9]*)(?:-[^/]*)?/?$").matchEntire(uri.path)
                    ?: return emptySet()
                val namespace = if (parts.groupValues[1] == "movie") ExternalIdNamespace.TmdbMovie else ExternalIdNamespace.TmdbTv
                ExternalMediaId(namespace, parts.groupValues[2])
            }
            "imdb.com", "www.imdb.com" -> {
                val id = Regex("^/title/(tt[0-9]+)/?$").matchEntire(uri.path)?.groupValues?.get(1)
                    ?: return emptySet()
                ExternalMediaId(ExternalIdNamespace.Imdb, id)
            }
            else -> return emptySet()
        }
        setOf(match)
    }.getOrDefault(emptySet())

    fun artworkIds(url: String?): Set<String> = runCatching {
        val uri = URI(url ?: return emptySet())
        if (uri.scheme != "https" || uri.host?.lowercase() != "image.tmdb.org" ||
            uri.userInfo != null || uri.port != -1) return emptySet()
        val file = Regex("^/t/p/(?:original|w[1-9][0-9]*|h[1-9][0-9]*)/([A-Za-z0-9]+\\.(?:jpg|png|webp))$")
            .matchEntire(uri.path)?.groupValues?.get(1) ?: return emptySet()
        setOf("tmdb-artwork:$file")
    }.getOrDefault(emptySet())
}
