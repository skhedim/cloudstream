package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What an HLS master playlist declares: best variant height and French audio/subtitle renditions. */
data class StreamProbe(val height: Int?, val audioLanguages: Set<String>, val subtitleLanguages: Set<String>) {
    /** Evidence only: French audio means VF; French subtitles without French audio mean VOST. */
    val version: SourceVersion? get() = when {
        audioLanguages.any(::isFrench) -> SourceVersion.VF
        audioLanguages.isNotEmpty() && subtitleLanguages.any(::isFrench) -> SourceVersion.VOST
        else -> null
    }

    companion object {
        private val french = setOf("fr", "fre", "fra", "french", "francais", "français", "vf", "vff", "vfq")
        fun isFrench(language: String): Boolean =
            language.lowercase().substringBefore('-').substringBefore('_').trim() in french

        private val attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        private fun attributes(line: String): Map<String, String> = attribute.findAll(line.substringAfter(':'))
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

        /** Parses a master playlist. A media playlist (segments only) declares nothing useful. */
        fun parse(text: String): StreamProbe? {
            if (!text.trimStart().startsWith("#EXTM3U")) return null
            var height: Int? = null
            val audio = mutableSetOf<String>()
            val subtitles = mutableSetOf<String>()
            text.lineSequence().map { it.trim() }.forEach { line ->
                when {
                    line.startsWith("#EXT-X-STREAM-INF") -> attributes(line)["RESOLUTION"]
                        ?.substringAfter('x')?.toIntOrNull()?.takeIf { it in 100..4320 }
                        ?.let { h -> height = maxOf(height ?: 0, h) }
                    line.startsWith("#EXT-X-MEDIA") -> {
                        val values = attributes(line)
                        val language = values["LANGUAGE"] ?: values["NAME"] ?: return@forEach
                        when (values["TYPE"]) {
                            "AUDIO" -> audio += language
                            "SUBTITLES" -> subtitles += language
                        }
                    }
                }
            }
            if (height == null && audio.isEmpty() && subtitles.isEmpty()) return null
            return StreamProbe(height, audio, subtitles)
        }
    }
}

/**
 * Reads only the small master playlist, with the link's own headers. Never rewrites the URL,
 * never follows into segments, and is skipped when the extension already supplied the facts.
 */
object StreamProbes {
    private val cache = linkedMapOf<String, Pair<Long, StreamProbe?>>()
    private const val TTL_MS = 20 * 60 * 1000L

    fun worthProbing(link: ExtractorLink): Boolean =
        link.type == ExtractorLinkType.M3U8 && link !is ExtractorLinkPlayList &&
            (PlaybackSources.quality(link) == 0 || PlaybackSources.version(link.name) == SourceVersion.Unknown)

    suspend fun probe(link: ExtractorLink, timeoutMs: Long = 2_500): StreamProbe? {
        val key = link.url
        synchronized(cache) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                val response = app.get(link.url, headers = link.getAllHeaders(), timeout = timeoutMs / 1000 + 1)
                if (response.code !in 200..299) return@withTimeoutOrNull null
                // A master playlist is a few kilobytes; anything large is not one.
                val text = response.body.use { body ->
                    val input = body.byteStream()
                    val buffer = ByteArray(256_000)
                    var size = 0
                    while (size < buffer.size) {
                        val read = input.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    String(buffer, 0, size, Charsets.UTF_8)
                }
                StreamProbe.parse(text)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { null }
        synchronized(cache) {
            cache[key] = System.currentTimeMillis() to result
            while (cache.size > 200) cache.remove(cache.keys.first())
        }
        return result
    }

    /**
     * Whether the stream answers at all, with its own headers: a 2-byte range request (the
     * playlist itself for HLS). Used before replacing a source that is already playing.
     */
    suspend fun reachable(link: ExtractorLink, timeoutMs: Long = 3_000): Boolean = try {
        withTimeoutOrNull(timeoutMs) {
            val headers = link.getAllHeaders() +
                if (link.type == ExtractorLinkType.M3U8) emptyMap() else mapOf("Range" to "bytes=0-1")
            val response = app.get(link.url, headers = headers, timeout = timeoutMs / 1000 + 1)
            response.body.close()
            response.code in 200..299
        } == true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { false }
}
