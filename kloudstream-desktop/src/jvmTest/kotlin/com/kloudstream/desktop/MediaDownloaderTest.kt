package com.kloudstream.desktop

import com.kloudstream.desktop.downloads.Hls
import com.kloudstream.desktop.downloads.MediaDownloader
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MediaDownloaderTest {
    private val routes = mutableMapOf<String, ByteArray>()
    private val referers = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            referers += exchange.requestHeaders.getFirst("Referer")
            val body = routes[exchange.requestURI.path]
            if (body == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            if (range != null) {
                val start = range.substringBefore('-').toInt()
                val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                val part = body.copyOfRange(start, end + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                exchange.sendResponseHeaders(206, part.size.toLong())
                exchange.responseBody.use { it.write(part) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val dir = Files.createTempDirectory("downloads").toFile()

    @After fun stop() = server.stop(0)

    @Suppress("DEPRECATION")
    private fun link(path: String) = ExtractorLink("Fixture", "Fixture", base + path, "https://referer.invalid/", 1080,
        emptyMap(), null, ExtractorLinkType.VIDEO)

    @Test fun aFileResumesWhereItStoppedWithTheLinksHeaders() = runBlocking {
        val content = Random(1).nextBytes(3_000_000)
        routes["/film.mp4"] = content
        val target = File(dir, "Film")
        // A first attempt stopped after 1 MB.
        File(dir, "Film.mp4.part").writeBytes(content.copyOfRange(0, 1_000_000))
        var last = 0.0
        val result = MediaDownloader({ OkHttpClient() }).download(link("/film.mp4"), target) { progress, _ -> last = progress }
        assertArrayEquals(content, result.video.readBytes())
        assertEquals("Film.mp4", result.video.name)
        assertEquals(1.0, last, 0.0)
        assertTrue(referers.all { it == "https://referer.invalid/" })
    }

    @Test fun hlsTakesTheBest1080pVariantDecryptsSegmentsAndKeepsTheFrenchAudio() = runBlocking {
        val key = Random(2).nextBytes(16)
        fun encrypt(data: ByteArray, sequence: Long): ByteArray {
            val iv = ByteArray(16).also { var n = sequence; for (i in 15 downTo 8) { it[i] = (n and 0xff).toByte(); n = n shr 8 } }
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)
        }
        val init = "INIT".toByteArray()
        val segments = (0 until 5).map { Random(10 + it).nextBytes(50_000) }
        routes["/master.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Français",LANGUAGE="fr",URI="audio/fr.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="aud"
            v720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            v1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,AUDIO="aud"
            v2160/index.m3u8
        """.trimIndent().toByteArray()
        routes["/v1080/index.m3u8"] = buildString {
            appendLine("#EXTM3U"); appendLine("#EXT-X-MEDIA-SEQUENCE:7"); appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k1\"")
            segments.indices.forEach { appendLine("#EXTINF:6.0,"); appendLine("seg$it.m4s") }
            appendLine("#EXT-X-ENDLIST")
        }.toByteArray()
        routes["/v1080/init.mp4"] = init
        routes["/keys/k1"] = key
        segments.forEachIndexed { index, data -> routes["/v1080/seg$index.m4s"] = encrypt(data, 7L + index) }
        routes["/audio/fr.m3u8"] = "#EXTM3U\n#EXTINF:6.0,\nfr0.ts\n#EXT-X-ENDLIST\n".toByteArray()
        routes["/audio/fr0.ts"] = "FRENCH".toByteArray()

        val result = MediaDownloader({ OkHttpClient() }, parallel = 2).download(link("/master.m3u8"), File(dir, "Episode")) { _, _ -> }
        assertEquals("Episode.mp4", result.video.name)
        assertArrayEquals(init + segments.reduce { acc, bytes -> acc + bytes }, result.video.readBytes())
        assertNotNull(result.audio)
        assertEquals("FRENCH", result.audio!!.readText())
    }

    @Test fun playlistsParseRelativeUrisByteRangesAndPlainKeys() {
        val media = Hls.media("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0000000000000000000000000000000A
            #EXTINF:4,
            #EXT-X-BYTERANGE:100@0
            all.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            #EXT-X-BYTERANGE:50
            all.ts
        """.trimIndent(), "https://cdn.invalid/show/ep1/index.m3u8")
        assertEquals(2, media.segments.size)
        assertEquals("https://cdn.invalid/show/ep1/all.ts", media.segments[0].uri)
        assertEquals(0L..99L, media.segments[0].range)
        assertEquals(100L..149L, media.segments[1].range)
        assertEquals("https://cdn.invalid/show/ep1/key.bin", media.segments[0].key?.uri)
        assertEquals(10, media.segments[0].key?.iv?.last()?.toInt())
        assertEquals(null, media.segments[1].key)
    }
}
