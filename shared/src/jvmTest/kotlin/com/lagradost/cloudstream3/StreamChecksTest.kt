package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.*
import org.junit.Assert.*
import org.junit.Test

class StreamChecksTest {
    @Test fun mediaPlaylistsAreMeasuredAndMasterPlaylistsPointToTheirFirstVariant() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n" + (1..600).joinToString("") { "#EXTINF:10.0,\nseg$it.ts\n" } + "#EXT-X-ENDLIST"
        assertEquals(6000.0, StreamInspector.mediaPlaylistSeconds(media)!!, 0.01)
        assertNull(StreamInspector.mediaPlaylistSeconds("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nlow.m3u8"))
        val master = "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:URI=\"iframe.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080\nhigh/index.m3u8?token=a\n"
        assertEquals("https://cdn.example/movie/high/index.m3u8?token=a",
            StreamInspector.firstVariant(master, "https://cdn.example/movie/master.m3u8"))
    }

    @Test fun contentThatCannotBeTheWorkIsRejectedButFailuresToMeasureNeverAre() {
        val film = 120 * 60.0
        fun hls(seconds: Double) = StreamInspection(true, 200, durationSeconds = seconds)
        assertEquals(SourceCheck.Verified, StreamChecks.verdict(hls(film * 1.02), film, false).check)
        assertEquals(SourceCheck.Verified, StreamChecks.verdict(hls(film * 1.3), film, false).check) // extended edition
        assertTrue(StreamChecks.verdict(hls(150.0), film, false).rejected) // advert loop / trailer
        assertTrue(StreamChecks.verdict(hls(film * 0.5), film, false).rejected) // another, shorter work
        assertEquals(SourceCheck.Verified, StreamChecks.verdict(hls(2 * 2700.0), 2700.0, true).check) // double episode
        assertEquals(SourceCheck.Verified, StreamChecks.verdict(hls(5400.0), null, false).check)
        assertTrue(StreamChecks.verdict(StreamInspection(false, 404), film, false).rejected)
        assertEquals(SourceCheck.Unchecked, StreamChecks.verdict(StreamInspection(false, 403), film, false).check)
        assertEquals(SourceCheck.Unchecked, StreamChecks.verdict(StreamInspection(false, error = "timeout"), film, false).check)
        assertEquals(SourceCheck.Alive, StreamChecks.verdict(StreamInspection(true, 200), film, false).check)
        // Progressive files: size against runtime.
        assertEquals(SourceCheck.Verified, StreamChecks.verdict(StreamInspection(true, 206, sizeBytes = 900_000_000), film, false).check)
        assertTrue(StreamChecks.verdict(StreamInspection(true, 206, sizeBytes = 20_000_000), film, false).rejected)
        assertTrue(StreamChecks.verdict(StreamInspection(false, 200, error = "not-hls"), film, false).rejected)
    }
}
