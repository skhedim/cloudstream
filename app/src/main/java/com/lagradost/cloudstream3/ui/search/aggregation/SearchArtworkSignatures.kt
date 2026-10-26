package com.lagradost.cloudstream3.ui.search.aggregation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.domain.media.ArtworkSignature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Only public, validated TMDB image assets; no provider URLs/headers or full-resolution downloads. */
internal object SearchArtworkSignatures {
    private data class Saved(val time: Long, val signature: ArtworkSignature?)
    private val cache = linkedMapOf<String,Saved>()
    @Synchronized private fun cached(id: String): Saved? = cache[id]?.takeIf { System.currentTimeMillis()-it.time < if (it.signature == null) 60_000L else 24*60*60*1000L }
    @Synchronized private fun save(id: String, signature: ArtworkSignature?) {
        cache[id] = Saved(System.currentTimeMillis(),signature)
        while (cache.size > 100) cache.remove(cache.keys.first())
    }

    suspend fun load(id: String): ArtworkSignature? {
        cached(id)?.let { return it.signature }
        val asset = Regex("^tmdb-artwork:([A-Za-z0-9]+\\.(?:jpg|png|webp))$").matchEntire(id)?.groupValues?.get(1)
            ?: return null
        return try {
            val signature = withTimeoutOrNull(2_500) {
                val bytes = app.get("https://image.tmdb.org/t/p/w185/$asset",cacheTime = 60*24).body.use { it.bytes() }
                if (bytes.size > 256_000) return@withTimeoutOrNull null
                val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return@withTimeoutOrNull null
                try {
                    val small = Bitmap.createScaledBitmap(bitmap,ArtworkSignature.WIDTH,ArtworkSignature.HEIGHT,true)
                    try {
                        val pixels = IntArray(ArtworkSignature.WIDTH*ArtworkSignature.HEIGHT)
                        small.getPixels(pixels,0,ArtworkSignature.WIDTH,0,0,ArtworkSignature.WIDTH,ArtworkSignature.HEIGHT)
                        ArtworkSignature(pixels.flatMap { pixel -> listOf((pixel shr 16) and 255,(pixel shr 8) and 255,pixel and 255) },bitmap.width.toDouble()/bitmap.height)
                    } finally { if (small !== bitmap) small.recycle() }
                } finally { bitmap.recycle() }
            }
            save(id,signature)
            signature
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { save(id,null);null }
    }
}
