package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * What a stream really is, measured with the link's own headers and without downloading media:
 * reachability, declared renditions and, for HLS, the real duration (sum of segment durations).
 * A link whose duration is far from the work's runtime is an advert loop, a trailer or another work.
 */
data class StreamInspection(
    val reachable: Boolean,
    val httpCode: Int? = null,
    val height: Int? = null,
    val audioLanguages: Set<String> = emptySet(),
    val subtitleLanguages: Set<String> = emptySet(),
    /** HLS only: total duration of the first variant's media playlist. */
    val durationSeconds: Double? = null,
    /** Progressive files: total size from Content-Range/Content-Length. */
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val error: String? = null,
) {
    val probe: StreamProbe get() = StreamProbe(height, audioLanguages, subtitleLanguages)
}

object StreamInspector {
    private val extinf = Regex("^#EXTINF:\\s*([0-9.]+)")

    private fun resolve(base: String, relative: String): String =
        runCatching { URI(base).resolve(relative.trim()).toString() }.getOrDefault(relative.trim())

    /** HTTP status, body (BOM removed) and whether the body was cut at [maxBytes]. */
    private data class Body(val code: Int, val text: String, val truncated: Boolean)

    private suspend fun text(url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int): Body {
        val response = app.get(url, headers = headers, timeout = timeoutMs / 1000 + 1)
        val code = response.code
        var truncated = false
        val body = response.body.use { body ->
            val input = body.byteStream()
            val buffer = ByteArray(maxBytes)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            if (size == buffer.size && input.read() >= 0) truncated = true
            String(buffer, 0, size, Charsets.UTF_8)
        }
        return Body(code, body.trimStart('\uFEFF'), truncated)
    }

    /** A partial playlist would understate the duration: only a complete one is measured. */
    private fun duration(body: Body): Double? = if (body.truncated) null else mediaPlaylistSeconds(body.text)

    /** Sum of #EXTINF durations; null when the text is not a media playlist. */
    fun mediaPlaylistSeconds(text: String): Double? {
        var total = 0.0
        var segments = 0
        text.lineSequence().forEach { line ->
            extinf.find(line.trim())?.groupValues?.get(1)?.toDoubleOrNull()?.let { total += it; segments++ }
        }
        return total.takeIf { segments > 0 }
    }

    /** First variant URI of a master playlist (the order servers list them in). */
    fun firstVariant(master: String, baseUrl: String): String? {
        val lines = master.lines().map { it.trim() }
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(baseUrl, it) }
    }

    private data class Saved(val inspection: StreamInspection, val at: Long)
    private val cache = linkedMapOf<String, Saved>()
    private const val TTL_MS = 20 * 60 * 1000L

    /** Measures [link]; successful measurements are reused for 20 minutes (same URL and headers). */
    suspend fun inspect(link: ExtractorLink, timeoutMs: Long = 6_000): StreamInspection {
        val key = link.url + "\n" + link.getAllHeaders().entries.sortedBy { it.key.lowercase() }.joinToString { "${it.key.lowercase()}=${it.value}" }
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.inspection } }
        val inspection = try {
            withTimeoutOrNull(timeoutMs) { inspectNow(link, timeoutMs) } ?: StreamInspection(false, error = "timeout")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (throwable: Throwable) { StreamInspection(false, error = throwable.javaClass.simpleName) }
        if (inspection.httpCode != null) synchronized(cache) {
            cache[key] = Saved(inspection, System.currentTimeMillis())
            while (cache.size > 500) cache.remove(cache.keys.first())
        }
        return inspection
    }

    private suspend fun inspectNow(link: ExtractorLink, timeoutMs: Long): StreamInspection {
        if (link is ExtractorLinkPlayList) return StreamInspection(true, error = "playlist-not-inspected")
        if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT ||
            link.type == ExtractorLinkType.DASH) return StreamInspection(false, error = "type ${link.type}")
        val headers = link.getAllHeaders()
        if (link.type == ExtractorLinkType.M3U8) {
            val first = text(link.url, headers, timeoutMs, 4_000_000)
            val code = first.code
            if (code !in 200..299) return StreamInspection(false, code)
            val master = first.text
            if (!master.trimStart().startsWith("#EXTM3U")) return StreamInspection(false, code, error = "not-hls")
            val probe = StreamProbe.parse(master)
            val variant = firstVariant(master, link.url)
            val seconds = if (variant == null) duration(first) else
                text(variant, headers, timeoutMs, 4_000_000).takeIf { it.code in 200..299 }?.let(::duration)
            return StreamInspection(true, code, probe?.height, probe?.audioLanguages.orEmpty(),
                probe?.subtitleLanguages.orEmpty(), seconds)
        }
        val response = app.get(link.url, headers = headers + ("Range" to "bytes=0-1"), timeout = timeoutMs / 1000 + 1)
        response.body.close()
        val size = response.headers["Content-Range"]?.substringAfter('/')?.toLongOrNull()
            ?: response.headers["Content-Length"]?.toLongOrNull()?.takeIf { response.code == 200 }
        return StreamInspection(response.code in 200..299, response.code, sizeBytes = size,
            contentType = response.headers["Content-Type"])
    }
}

/** Outcome of a stream check: a [SourceCheck] level, or a rejection with its reason. */
data class StreamVerdict(val check: SourceCheck?, val reason: String? = null) {
    val rejected: Boolean get() = check == null
}

/**
 * Generic content plausibility from measurements, without looking at the picture:
 * a stream far shorter/longer than the work (advert loop, trailer, other work) or a file far
 * too small for its runtime is rejected; failures to measure never reject.
 */
object StreamChecks {
    /** Ratio of measured to catalogue duration accepted for a movie (cuts, credits, extended editions). */
    val movieRatio = 0.75..1.45
    /** Episode runtimes in catalogues are approximate (recaps, double episodes). */
    val episodeRatio = 0.5..2.2
    /** Below this, a stream is not a movie or an episode. */
    const val MIN_SECONDS = 240.0
    /** Average bitrate below this cannot be watchable video (kbit/s). */
    const val MIN_KBPS = 120.0
    const val MIN_BYTES_WITHOUT_RUNTIME = 25_000_000L

    fun verdict(inspection: StreamInspection, expectedSeconds: Double?, episode: Boolean): StreamVerdict {
        val code = inspection.httpCode
        if (inspection.error != null && code == null) return StreamVerdict(SourceCheck.Unchecked, inspection.error)
        if (code != null && code !in 200..299) {
            // Missing cookies or a strict referer can deny our request but not the player's.
            return if (code == 401 || code == 403 || code == 429) StreamVerdict(SourceCheck.Unchecked, "http $code")
            else StreamVerdict(null, "http $code")
        }
        if (inspection.error == "not-hls") return StreamVerdict(null, "not-hls")
        inspection.durationSeconds?.let { seconds ->
            if (seconds < MIN_SECONDS) return StreamVerdict(null, "short ${seconds.toInt()}s")
            if (expectedSeconds != null && expectedSeconds > 0) {
                val ratio = seconds / expectedSeconds
                if (ratio !in (if (episode) episodeRatio else movieRatio)) return StreamVerdict(null, "duration ${"%.2f".format(ratio)}")
            }
            return StreamVerdict(SourceCheck.Verified)
        }
        inspection.sizeBytes?.let { size ->
            if (expectedSeconds != null && expectedSeconds > 0) {
                val kbps = size * 8 / expectedSeconds / 1000
                if (kbps < MIN_KBPS) return StreamVerdict(null, "tiny ${kbps.toInt()}kbps")
            } else if (size < MIN_BYTES_WITHOUT_RUNTIME) return StreamVerdict(null, "tiny ${size / 1_000_000}MB")
            return StreamVerdict(SourceCheck.Verified)
        }
        return if (inspection.reachable) StreamVerdict(SourceCheck.Alive) else StreamVerdict(SourceCheck.Unchecked, inspection.error)
    }
}
