package com.lagradost.cloudstream4.domain.media

data class ProviderPage<T>(val items: List<T>, val page: Int, val hasNext: Boolean)

data class PageRequest(val generation: Long, val provider: ProviderKey, val page: Int, val attempt: Long)

/** Single-owner reducer. Network work happens outside it, then returns with its request token. */
class SearchSession<T>(private val itemKey: (T) -> String) {
    private var generation = 0L
    private var attempt = 0L
    private val pages = linkedMapOf<ProviderKey, ProviderPage<T>>()
    private val pending = mutableMapOf<ProviderKey, PageRequest>()
    private val failed = mutableSetOf<ProviderKey>()

    fun begin(providers: List<ProviderKey>) {
        generation++
        pages.clear()
        pending.clear()
        failed.clear()
        providers.forEach { pages[it] = ProviderPage(emptyList(), 0, true) }
    }

    fun request(provider: ProviderKey): PageRequest? {
        val current = pages[provider] ?: return null
        if (!current.hasNext || provider in pending) return null
        return PageRequest(generation, provider, current.page + 1, ++attempt).also { pending[provider] = it }
    }

    fun accept(request: PageRequest, items: List<T>, hasNext: Boolean): Boolean {
        if (pending[request.provider] != request) return false
        val current = pages.getValue(request.provider)
        pages[request.provider] = ProviderPage(
            (current.items + items).distinctBy(itemKey), request.page, hasNext,
        )
        pending.remove(request.provider)
        failed.remove(request.provider)
        return true
    }

    fun fail(request: PageRequest): Boolean {
        if (pending[request.provider] != request) return false
        pending.remove(request.provider)
        failed.add(request.provider)
        return true // Keep the page cursor so the same page can be retried.
    }

    fun snapshot(): Map<ProviderKey, ProviderPage<T>> = pages.toMap()
    fun isCurrent(request: PageRequest): Boolean = request.generation == generation
    val hasNext: Boolean get() = pages.values.any { it.hasNext }
    val hasFailures: Boolean get() = failed.isNotEmpty()
}
