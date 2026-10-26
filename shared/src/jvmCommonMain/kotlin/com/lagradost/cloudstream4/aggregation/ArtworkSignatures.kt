package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.domain.media.ArtworkSignature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Platform image decoding: scale an encoded image to [ArtworkSignature.WIDTH]×[ArtworkSignature.HEIGHT] RGB. */
fun interface ArtworkSampler {
    /** Null if the bytes are not a decodable image. */
    fun sample(bytes: ByteArray): ArtworkSignature?
}

/** Only public, validated TMDB image assets; no provider URLs/headers or full-resolution downloads. */
object SearchArtworkSignatures {
    private data class Saved(val time: Long, val signature: ArtworkSignature?)
    private val cache = linkedMapOf<String,Saved>()
    @Synchronized private fun cached(id: String): Saved? = cache[id]?.takeIf { System.currentTimeMillis()-it.time < if (it.signature == null) 60_000L else 24*60*60*1000L }
    @Synchronized private fun save(id: String, signature: ArtworkSignature?) {
        cache[id] = Saved(System.currentTimeMillis(),signature)
        while (cache.size > 100) cache.remove(cache.keys.first())
    }

    suspend fun load(id: String): ArtworkSignature? {
        val sampler = AggregationPlatform.artworkSampler ?: return null
        cached(id)?.let { return it.signature }
        val asset = Regex("^tmdb-artwork:([A-Za-z0-9]+\\.(?:jpg|png|webp))$").matchEntire(id)?.groupValues?.get(1)
            ?: return null
        return try {
            val signature = withTimeoutOrNull(2_500) {
                val bytes = app.get("https://image.tmdb.org/t/p/w185/$asset",cacheTime = 60*24).body.use { it.bytes() }
                if (bytes.size > 256_000) return@withTimeoutOrNull null
                sampler.sample(bytes)
            }
            save(id,signature)
            signature
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { save(id,null);null }
    }
}
