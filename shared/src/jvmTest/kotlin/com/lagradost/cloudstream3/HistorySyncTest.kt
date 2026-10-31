package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.*
import com.lagradost.cloudstream4.domain.media.*
import com.lagradost.cloudstream4.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistorySyncTest {
    private fun request(title: String, tmdb: String? = null) = CanonicalDetailRequest("title:$title", title, 2026, MediaKind.Movie,
        setOfNotNull(tmdb?.let { ExternalMediaId(ExternalIdNamespace.TmdbMovie, it) }),
        listOf(DetailBinding(ProviderKey("Movix", "https://movix.invalid"), "/$title")))

    private fun work(title: String, positionMs: Long, at: Long, finished: Boolean = false) = request(title, "1").let {
        SyncedWork(SyncedWork.keyOf(it), it, positionMs = positionMs, durationMs = 6_000_000, finished = finished, updatedAt = at)
    }

    /** A device's history: what [apply] receives is kept as is, a removal deletes the work. */
    private class Device : SyncedHistoryStore {
        val works = linkedMapOf<String, SyncedWork>()
        var refuse = false
        override fun states() = works.values.toList()
        override fun apply(state: SyncedWork): Boolean {
            if (refuse) return false
            if (state.removed) works.remove(state.key) else works[state.key] = state.copy(device = null)
            return true
        }
    }

    /** The shared file, versioned like GitHub's contents: a write with an old version is refused. */
    private class Remote : SyncRemote {
        var history = SyncedHistory()
        var version = 0
        var writes = 0
        var interference: (() -> Unit)? = null
        override suspend fun read() = history to version.takeIf { it > 0 }?.toString()
        override suspend fun write(history: SyncedHistory, version: String?): Boolean {
            interference?.let { interference = null; it() }
            if (version != this.version.takeIf { it > 0 }?.toString()) return false
            this.history = history
            this.version++
            writes++
            return true
        }
    }

    private fun sync(device: Device, remote: Remote, store: KeyValueStore, name: String) =
        runBlocking { HistorySync(device, remote, name, { store }, { 10_000 }).sync() }

    @Test fun aWorkStartedOnOneDeviceResumesOnTheOther() {
        val (shield, pc, remote) = Triple(Device(), Device(), Remote())
        val (shieldStore, pcStore) = InMemoryKeyValueStore() to InMemoryKeyValueStore()
        shield.works["k"] = work("Dune", 600_000, 100).let { it.copy(key = "k") }
        assertEquals(HistorySync.Result(0, true), sync(shield, remote, shieldStore, "SHIELD"))
        assertEquals("SHIELD", remote.history.works.getValue("k").device)

        assertEquals(1, sync(pc, remote, pcStore, "PC").applied)
        assertEquals(600_000L, pc.works.getValue("k").positionMs)
        // Applied as shared: nothing new to write back.
        assertEquals(1, remote.writes)

        pc.works["k"] = pc.works.getValue("k").copy(positionMs = 1_800_000, updatedAt = 200)
        sync(pc, remote, pcStore, "PC")
        assertEquals(1, sync(shield, remote, shieldStore, "SHIELD").applied)
        assertEquals(1_800_000L, shield.works.getValue("k").positionMs)
    }

    @Test fun anOlderStateNeverReplacesANewerOne() {
        val history = SyncedHistory().merge(listOf(work("Dune", 900_000, 300)))
        val key = history.works.keys.single()
        assertEquals(900_000L, history.merge(listOf(work("Dune", 100_000, 200))).works.getValue(key).positionMs)
        // On a tie, the shared state stays (a device re-exporting what it applied writes nothing).
        assertEquals(history, history.merge(listOf(work("Dune", 100_000, 300))))
    }

    @Test fun aWorkRemovedFromContinueWatchingIsRemovedEverywhere() {
        val (shield, pc, remote) = Triple(Device(), Device(), Remote())
        val (shieldStore, pcStore) = InMemoryKeyValueStore() to InMemoryKeyValueStore()
        val dune = work("Dune", 600_000, 100)
        shield.works[dune.key] = dune
        sync(shield, remote, shieldStore, "SHIELD")
        sync(pc, remote, pcStore, "PC")
        assertTrue(dune.key in pc.works)

        shield.works.clear()
        sync(shield, remote, shieldStore, "SHIELD")
        assertTrue(remote.history.works.getValue(dune.key).removed)
        sync(pc, remote, pcStore, "PC")
        assertFalse(dune.key in pc.works)
        // Once removed everywhere, nothing more is written.
        val writes = remote.writes
        sync(shield, remote, shieldStore, "SHIELD")
        sync(pc, remote, pcStore, "PC")
        assertEquals(writes, remote.writes)
    }

    @Test fun aFinishedWorkLeavingADeviceStaysFinished() {
        val (box, remote, store) = Triple(Device(), Remote(), InMemoryKeyValueStore())
        val finished = work("Dune", 6_000_000, 100, finished = true)
        box.works[finished.key] = finished
        sync(box, remote, store, "MIBOX3")
        // Android keeps the 50 latest finished works: an older one leaving is not a removal.
        box.works.clear()
        sync(box, remote, store, "MIBOX3")
        assertFalse(remote.history.works.getValue(finished.key).removed)
    }

    @Test fun aWorkThisDeviceCannotOpenIsNotTakenForARemoval() {
        val (shield, pc, remote) = Triple(Device(), Device(), Remote())
        val dune = work("Dune", 600_000, 100)
        shield.works[dune.key] = dune
        sync(shield, remote, InMemoryKeyValueStore(), "SHIELD")
        pc.refuse = true
        val pcStore = InMemoryKeyValueStore()
        assertEquals(0, sync(pc, remote, pcStore, "PC").applied)
        sync(pc, remote, pcStore, "PC")
        assertFalse(remote.history.works.getValue(dune.key).removed)
    }

    @Test fun twoDevicesWritingAtOnceKeepBothWorks() {
        val (pc, remote) = Device() to Remote()
        val dune = work("Dune", 600_000, 100)
        val alien = request("Alien", "2").let { SyncedWork(SyncedWork.keyOf(it), it, positionMs = 60_000, durationMs = 6_000_000, updatedAt = 150) }
        pc.works[alien.key] = alien
        // The SHIELD writes while the PC is between its read and its write: the PC reads again and merges.
        remote.interference = { remote.history = remote.history.merge(listOf(dune)); remote.version++ }
        sync(pc, remote, InMemoryKeyValueStore(), "PC")
        assertEquals(setOf(dune.key, alien.key), remote.history.works.keys)
        assertTrue(dune.key in pc.works)
    }

    @Test fun theSameWorkHasTheSameKeyOnEveryDevice() {
        // Different identities (one device learnt the TMDB ID later), one key.
        assertEquals("canonical:v1:TmdbMovie:1084244",
            SyncedWork.keyOf(request("Toy Story 5", "1084244").copy(identity = "canonical:v1:title:toy story 5:2026")))
        assertEquals("title:Toy Story 5", SyncedWork.keyOf(request("Toy Story 5")))
    }

    @Test fun theSharedFileRoundTripsThroughJson() {
        val history = SyncedHistory().merge(listOf(work("Dune", 600_000, 100)))
        assertEquals(history, mapper.readValue(mapper.writeValueAsString(history), SyncedHistory::class.java))
    }
}
