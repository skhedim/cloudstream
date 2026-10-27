package com.kloudstream.desktop.downloads

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * Saves one stream to disk with the link's own headers: a file (resumed with Range requests) or an
 * HLS playlist (the best variant up to 1080p, AES-128 segments decrypted, fMP4 init kept; a separate
 * audio rendition, French first, is saved next to the video and given to mpv at playback).
 */
class MediaDownloader(
    private val client: () -> OkHttpClient = { app.baseClient },
    private val parallel: Int = 6,
) {
    data class Result(val video: File, val audio: File?, val bytes: Long)

    /** [onProgress] receives a fraction (negative when unknown) and the bytes written so far. */
    suspend fun download(link: ExtractorLink, base: File, onProgress: (Double, Long) -> Unit): Result {
        val headers = headers(link)
        val head = fetch(link.url, headers, range = 0L..2047L, allowFullBody = false)
        return if (head.decodeToString().trimStart().startsWith("#EXTM3U")) hls(link.url, headers, base, onProgress)
            else file(link.url, headers, base, onProgress)
    }

    private fun headers(link: ExtractorLink): Map<String, String> {
        val all = linkedMapOf<String, String>()
        link.getAllHeaders().forEach { (key, value) -> all[key] = value }
        if (all.keys.none { it.equals("User-Agent", true) }) all["User-Agent"] = USER_AGENT
        return all
    }

    private fun request(url: String, headers: Map<String, String>, range: LongRange? = null, from: Long? = null): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        when {
            range != null -> builder.header("Range", "bytes=${range.first}-${range.last}")
            from != null && from > 0 -> builder.header("Range", "bytes=$from-")
        }
        return builder.build()
    }

    /** A whole (small) body, retried a few times: playlists, keys, segments. */
    private suspend fun fetch(url: String, headers: Map<String, String>, range: LongRange? = null, allowFullBody: Boolean = true): ByteArray {
        var failure: Exception? = null
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            try {
                return runInterruptible {
                    client().newCall(request(url, headers, range)).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${URI(url).host}")
                        val body = response.body ?: throw IOException("Empty answer")
                        // A server ignoring the Range of a probe may send a whole film: read the start only.
                        if (!allowFullBody && range != null && response.code == 200) body.byteStream().use { it.readNBytes((range.last - range.first + 1).toInt()) }
                        else body.bytes()
                    }
                }
            } catch (e: IOException) {
                failure = e
                delay(500L * (attempt + 1))
            }
        }
        throw failure ?: IOException("Download failed")
    }

    private suspend fun file(url: String, headers: Map<String, String>, base: File, onProgress: (Double, Long) -> Unit): Result {
        val extension = URI(url).path.substringAfterLast('.', "").lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v") } ?: "mp4"
        val target = File(base.parentFile, base.name + "." + extension)
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile.mkdirs()
        runInterruptible {
            client().newCall(request(url, headers, from = part.length())).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val resumed = response.code == 206
                val body = response.body ?: throw IOException("Empty answer")
                val start = if (resumed) part.length() else 0L
                val total = body.contentLength().takeIf { it > 0 }?.plus(start) ?: -1L
                var written = start
                java.io.FileOutputStream(part, resumed).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written - last > 1 shl 20) { last = written; onProgress(if (total > 0) written.toDouble() / total else -1.0, written) }
                        }
                    }
                }
                if (total > 0 && written < total) throw IOException("Interrupted at $written of $total bytes")
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name}")
        onProgress(1.0, target.length())
        return Result(target, null, target.length())
    }

    // HLS -------------------------------------------------------------------------------------

    class Key(val method: String, val uri: String?, val iv: ByteArray?)
    class Segment(val uri: String, val key: Key?, val sequence: Long, val range: LongRange?)
    class Media(val segments: List<Segment>, val init: Segment?)
    class Variant(val uri: String, val bandwidth: Long, val height: Int?, val audioGroup: String?)
    class Rendition(val group: String, val uri: String, val language: String?, val default: Boolean)

    private suspend fun hls(url: String, headers: Map<String, String>, base: File, onProgress: (Double, Long) -> Unit): Result {
        var mediaUrl = url
        var text = fetch(url, headers).decodeToString()
        var audioUrl: String? = null
        if ("#EXT-X-STREAM-INF" in text) {
            val (variants, renditions) = Hls.master(text, url)
            val variant = Hls.choose(variants) ?: throw IOException("No playable variant")
            mediaUrl = variant.uri
            audioUrl = variant.audioGroup?.let { group -> Hls.audio(renditions.filter { it.group == group }) }?.uri
            text = fetch(mediaUrl, headers).decodeToString()
        }
        val video = Hls.media(text, mediaUrl)
        val audio = audioUrl?.let { Hls.media(fetch(it, headers).decodeToString(), it) }
        val total = video.segments.size + (audio?.segments?.size ?: 0)
        var done = 0
        var bytes = 0L
        val keys = mutableMapOf<String, ByteArray>()
        suspend fun save(media: Media, target: File) {
            val part = File(target.parentFile, target.name + ".part")
            target.parentFile.mkdirs()
            java.io.FileOutputStream(part).use { out ->
                media.init?.let { out.write(fetch(it.uri, headers, it.range)) }
                for (chunk in media.segments.chunked(parallel)) {
                    val data = coroutineScope {
                        chunk.map { segment -> async { decrypt(segment, fetch(segment.uri, headers, segment.range), headers, keys) } }.awaitAll()
                    }
                    data.forEach { out.write(it); bytes += it.size }
                    done += chunk.size
                    onProgress(done.toDouble() / total, bytes)
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not move ${part.name}")
        }
        val videoFile = File(base.parentFile, base.name + if (video.init != null) ".mp4" else ".ts")
        save(video, videoFile)
        val audioFile = audio?.let { File(base.parentFile, base.name + ".audio" + if (it.init != null) ".mp4" else ".ts") }
        if (audio != null && audioFile != null) save(audio, audioFile)
        return Result(videoFile, audioFile, bytes)
    }

    private suspend fun decrypt(segment: Segment, data: ByteArray, headers: Map<String, String>, keys: MutableMap<String, ByteArray>): ByteArray {
        val key = segment.key ?: return data
        if (key.method != "AES-128") throw IOException("Unsupported encryption ${key.method}")
        val uri = key.uri ?: throw IOException("Key without URI")
        val secret = synchronized(keys) { keys[uri] } ?: fetch(uri, headers).also { synchronized(keys) { keys[uri] = it } }
        val iv = key.iv ?: ByteArray(16).also { iv -> var n = segment.sequence; for (i in 15 downTo 8) { iv[i] = (n and 0xff).toByte(); n = n shr 8 } }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}

/** HLS playlist parsing (RFC 8216), the parts a download needs. */
object Hls {
    private val attribute = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attributes(line: String): Map<String, String> = attribute.findAll(line.substringAfter(':'))
        .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, uri: String): String = URI(base).resolve(uri.trim()).toString()

    fun master(text: String, url: String): Pair<List<MediaDownloader.Variant>, List<MediaDownloader.Rendition>> {
        val lines = text.lines().map { it.trim() }
        val variants = mutableListOf<MediaDownloader.Variant>()
        val renditions = mutableListOf<MediaDownloader.Rendition>()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = attributes(line)
                val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
                variants += MediaDownloader.Variant(resolve(url, uri), attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                    attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull(), attrs["AUDIO"])
            } else if (line.startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in line) {
                val attrs = attributes(line)
                val uri = attrs["URI"] ?: return@forEachIndexed
                renditions += MediaDownloader.Rendition(attrs["GROUP-ID"].orEmpty(), resolve(url, uri), attrs["LANGUAGE"], attrs["DEFAULT"] == "YES")
            }
        }
        return variants to renditions
    }

    /** The best variant up to 1080p, else the smallest one. */
    fun choose(variants: List<MediaDownloader.Variant>): MediaDownloader.Variant? =
        variants.filter { (it.height ?: 0) <= 1080 }.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.bandwidth }))
            ?: variants.minByOrNull { it.height ?: Int.MAX_VALUE }

    /** French audio first, then the default rendition. */
    fun audio(renditions: List<MediaDownloader.Rendition>): MediaDownloader.Rendition? =
        renditions.firstOrNull { it.language?.lowercase()?.startsWith("fr") == true } ?: renditions.firstOrNull { it.default } ?: renditions.firstOrNull()

    fun media(text: String, url: String): MediaDownloader.Media {
        val segments = mutableListOf<MediaDownloader.Segment>()
        var key: MediaDownloader.Key? = null
        var init: MediaDownloader.Segment? = null
        var sequence = 0L
        var range: LongRange? = null
        var nextOffset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = attributes(line)
                    val method = attrs["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else MediaDownloader.Key(method, attrs["URI"]?.let { resolve(url, it) },
                        attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray())
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val attrs = attributes(line)
                    init = attrs["URI"]?.let { MediaDownloader.Segment(resolve(url, it), null, -1, attrs["BYTERANGE"]?.let { r -> byteRange(r, 0) }) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'), nextOffset)
                line.startsWith("#") -> Unit
                else -> {
                    segments += MediaDownloader.Segment(resolve(url, line), key, sequence++, range)
                    nextOffset = range?.let { it.last + 1 } ?: 0
                    range = null
                }
            }
        }
        return MediaDownloader.Media(segments, init)
    }

    /** `length[@offset]`; without an offset the range follows the previous one. */
    private fun byteRange(value: String, previousEnd: Long): LongRange {
        val length = value.substringBefore('@').trim().toLong()
        val offset = value.substringAfter('@', "").trim().toLongOrNull() ?: previousEnd
        return offset until offset + length
    }
}
