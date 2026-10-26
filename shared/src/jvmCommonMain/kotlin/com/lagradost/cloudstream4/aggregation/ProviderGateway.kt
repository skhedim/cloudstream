package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.domain.media.ProviderKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** Calls into an extension. Opaque URLs and payloads are passed through unchanged. */
interface ProviderGateway {
    /** Null when the provider failed; an empty page is a successful answer. */
    suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList?
    suspend fun quickSearch(api: MainAPI, query: String): SearchResponseList? = search(api, query, 1)
    suspend fun load(api: MainAPI, url: String): LoadResponse?
    suspend fun loadLinks(
        api: MainAPI, data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean
}

/**
 * The same guards as Android's APIRepository, without its UI coupling: declared timeouts
 * clamped to 5 s…8 min, invalid payload rejection and metadata capture for later matching.
 */
object DirectProviderGateway : ProviderGateway {
    private const val DEFAULT_TIMEOUT = 120_000L
    fun timeout(desired: Long?): Long = (desired ?: DEFAULT_TIMEOUT).coerceIn(5_000L, 4 * DEFAULT_TIMEOUT)
    fun isInvalidData(data: String): Boolean = data.isEmpty() || data == "[]" || data == "about:blank"

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        // withTimeout signals expiry with a CancellationException subclass; real cancellation propagates.
        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null else throw cancelled
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }

    override suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList? {
        if (query.isEmpty()) return newSearchResponseList(emptyList())
        return guarded { withTimeout(timeout(api.searchTimeoutMs)) { api.search(query, page) } }
    }

    override suspend fun quickSearch(api: MainAPI, query: String): SearchResponseList? {
        if (query.isEmpty()) return newSearchResponseList(emptyList())
        return guarded {
            withTimeout(timeout(api.quickSearchTimeoutMs)) {
                newSearchResponseList(api.quickSearch(query) ?: throw ErrorLoadingException(), false)
            }
        }
    }

    override suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (isInvalidData(url)) return null
        return guarded {
            withTimeout(timeout(api.loadTimeoutMs)) {
                api.load(api.fixUrl(url))?.also { response ->
                    response.tags = response.tags?.filter { it.isNotBlank() }
                    runCatching {
                        SearchMediaMetadataCache.remember(api, url, response, ProviderKey(api.name, api.mainUrl))
                    }.onFailure(::logError)
                }
            }
        }
    }

    override suspend fun loadLinks(
        api: MainAPI, data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isInvalidData(data)) return false
        return guarded {
            withTimeout(timeout(api.loadLinksTimeoutMs)) { api.loadLinks(data, isCasting, subtitleCallback, callback) }
        } == true
    }
}
