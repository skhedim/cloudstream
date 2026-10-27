package com.kloudstream.desktop

import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.JsonFile
import com.kloudstream.desktop.library.WatchEntry
import com.kloudstream.desktop.library.WatchHistory
import com.kloudstream.desktop.platform.DesktopPreferences
import com.kloudstream.desktop.platform.JsonKeyValueStore
import com.kloudstream.desktop.player.Mpv
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoresTest {
    init { TestHome.ensure() }

    data class Sample(val name: String = "", val values: List<Int> = emptyList())

    @Test fun keyValuesSurviveARestart() {
        val file = File(AppDirs.data, "kv-test.json")
        JsonKeyValueStore(file).set("a/b", Sample("x", listOf(1, 2)))
        JsonFile.flushAll()
        assertEquals(Sample("x", listOf(1, 2)), JsonKeyValueStore(file).get("a/b", Sample::class.java))
        assertNull(JsonKeyValueStore(file).get("missing", Sample::class.java))
    }

    @Test fun extensionPreferencesAndDataStoreKeysPersist() {
        val prefs = DesktopPreferences.open("unit-test")
        prefs.edit().putString("domain", "https://example.org").putBoolean("vf", true).putInt("count", 3).apply()
        prefs.edit().remove("count").apply()
        assertEquals("https://example.org", prefs.getString("domain", null))
        assertTrue(prefs.getBoolean("vf", false))
        assertFalse(prefs.contains("count"))
        CloudStreamApp.setKey("extension/setting", listOf("a", "b"))
        assertEquals(listOf("a", "b"), DataStore.getKey("extension/setting", List::class.java))
        JsonFile.flushAll()
        assertTrue(File(AppDirs.data, "preferences/unit-test.json").readText().contains("example.org"))
    }

    @Test fun onlyStartedUnfinishedProgressIsResumed() {
        val base = WatchEntry("work-1", "Film", family = "movie")
        WatchHistory.save(base.copy(positionMs = 20_000, durationMs = 6_000_000))
        assertEquals(0, WatchHistory.resumePositionMs("work-1", null))
        WatchHistory.save(base.copy(positionMs = 1_200_000, durationMs = 6_000_000))
        assertEquals(1_200_000, WatchHistory.resumePositionMs("work-1", null))
        WatchHistory.save(base.copy(positionMs = 5_800_000, durationMs = 6_000_000))
        assertEquals(0, WatchHistory.resumePositionMs("work-1", null))
        assertTrue(WatchHistory.entries.value.first { it.identity == "work-1" }.finished)
        WatchHistory.save(WatchEntry("work-2", "Série", family = "series", episodeKey = "s1:e2", positionMs = 600_000, durationMs = 2_700_000))
        assertEquals("work-2", WatchHistory.entries.value.first().identity)
        assertEquals(600_000, WatchHistory.resumePositionMs("work-2", "s1:e2"))
        WatchHistory.remove("work-2")
        assertTrue(WatchHistory.entries.value.none { it.identity == "work-2" })
        assertEquals(0, WatchHistory.resumePositionMs("work-2", "s1:e2"))
    }

    @Test fun anExplicitPlayerPathWins() {
        val fake = File.createTempFile("mpv", ".exe").apply { setExecutable(true) }
        System.setProperty("kloudstream.mpv", fake.path)
        try { assertEquals(fake, Mpv.executable()) } finally { System.clearProperty("kloudstream.mpv") }
    }
}
