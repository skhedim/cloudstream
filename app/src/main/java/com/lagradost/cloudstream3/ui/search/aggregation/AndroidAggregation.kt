package com.lagradost.cloudstream3.ui.search.aggregation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.ArtworkSampler
import com.lagradost.cloudstream4.aggregation.KeyValueStore
import com.lagradost.cloudstream4.aggregation.ProviderGateway
import com.lagradost.cloudstream4.aggregation.ReviewedExtensions
import com.lagradost.cloudstream4.domain.media.ArtworkSignature

/** Android side of the shared aggregation engine: existing stores, APIRepository guards, Bitmap decoding. */
object AndroidAggregation {
    fun install() {
        AggregationPlatform.store = AndroidKeyValueStore
        AggregationPlatform.gateway = ApiRepositoryGateway
        AggregationPlatform.artworkSampler = BitmapArtworkSampler
        AggregationPlatform.debug = BuildConfig.DEBUG
        (CloudStreamApp.context?.applicationContext as? android.app.Application)
            ?.let(com.lagradost.cloudstream3.ui.catalog.ExtensionPromptBlocker::install)
        com.lagradost.cloudstream3.ui.catalog.CatalogAutoUpdate.install()
        // Reviewed extension settings (e.g. catalogue-only providers off), unless already set.
        ReviewedExtensions.all.flatMap { it.preferences.entries }.forEach { (key, value) ->
            if (CloudStreamApp.getKey<Any>(key) == null) CloudStreamApp.setKey(key, value)
        }
    }
}

/** The account-independent app store already used for canonical aliases. */
object AndroidKeyValueStore : KeyValueStore {
    override fun <T : Any> get(key: String, type: Class<T>): T? = CloudStreamApp.getKeyClass(key, type)
    override fun set(key: String, value: Any) = CloudStreamApp.setKey(key, value)
}

/** Keeps upstream behaviour (timeouts, 10-minute load cache, metadata capture) for every engine call. */
object ApiRepositoryGateway : ProviderGateway {
    override suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList? =
        (APIRepository(api).search(query, page) as? Resource.Success)?.value

    override suspend fun quickSearch(api: MainAPI, query: String): SearchResponseList? =
        (APIRepository(api).quickSearch(query) as? Resource.Success)?.value

    override suspend fun load(api: MainAPI, url: String): LoadResponse? =
        (APIRepository(api).load(url) as? Resource.Success)?.value

    override suspend fun loadLinks(
        api: MainAPI, data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean = APIRepository(api).loadLinks(data, isCasting, subtitleCallback, callback)
}

object BitmapArtworkSampler : ArtworkSampler {
    override fun sample(bytes: ByteArray): ArtworkSignature? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val small = Bitmap.createScaledBitmap(bitmap, ArtworkSignature.WIDTH, ArtworkSignature.HEIGHT, true)
            try {
                val pixels = IntArray(ArtworkSignature.WIDTH * ArtworkSignature.HEIGHT)
                small.getPixels(pixels, 0, ArtworkSignature.WIDTH, 0, 0, ArtworkSignature.WIDTH, ArtworkSignature.HEIGHT)
                return ArtworkSignature(pixels.flatMap { pixel -> listOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) },
                    bitmap.width.toDouble() / bitmap.height)
            } finally { if (small !== bitmap) small.recycle() }
        } finally { bitmap.recycle() }
    }
}
