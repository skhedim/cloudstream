package com.lagradost.cloudstream3.ui.search

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.catalog.CatalogPreferences
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream4.AppSettings

/** Catalogue categories apply to aggregated search; provider view uses native preferences. */
class SearchScope(context: Context, selectedTypes: Set<TvType>, private val aggregate: Boolean) {
    private val selection = CatalogPreferences.read(context)
    private val types = if (aggregate) selectedTypes.intersect(selection.types).ifEmpty { selection.types } else selectedTypes
    fun allowsProvider(api: MainAPI): Boolean =
        (!aggregate || selection.normalProvider(api)) && (types.isEmpty() || api.supportedTypes.any { it in types })
    fun allowsItem(api: MainAPI, item: SearchResponse): Boolean = !aggregate ||
        (allowsProvider(api) && selection.normalItem(item) && (item.type == null || item.type in types))

    companion object {
        fun availableProviders(context: Context, aggregate: Boolean): List<MainAPI> =
            if (aggregate) {
                val selection = CatalogPreferences.read(context)
                APIHolder.apis.withLock { APIHolder.apis.filter { selection.normalProvider(it) } }
            } else context.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
        fun availableTypes(context: Context, providers: List<MainAPI>): List<TvType> {
            val types = providers.flatMap { it.supportedTypes }.distinct()
            return if (AppSettings(context).ui.aggregateSearch.get()) types.filter { it in CatalogPreferences.read(context).types }
                else types
        }
    }
}
