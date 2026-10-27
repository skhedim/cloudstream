package com.kloudstream.desktop.player

import com.fasterxml.jackson.databind.JsonNode
import com.kloudstream.desktop.core.AppLog
import com.kloudstream.desktop.library.WatchEntry
import com.kloudstream.desktop.library.WatchHistory
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.aggregation.CanonicalSourceLoader
import com.lagradost.cloudstream4.aggregation.EpisodeSourceBinding
import com.lagradost.cloudstream4.aggregation.ExternalSubtitles
import com.lagradost.cloudstream4.aggregation.PlaybackPolicy
import com.lagradost.cloudstream4.aggregation.PlaybackSource
import com.lagradost.cloudstream4.aggregation.PlaybackSourceMetadata
import com.lagradost.cloudstream4.aggregation.PlaybackSources
import com.lagradost.cloudstream4.aggregation.PlaybackVerification
import com.lagradost.cloudstream4.aggregation.PlaybackWork
import com.lagradost.cloudstream4.aggregation.ProviderHealth
import com.lagradost.cloudstream4.aggregation.SourceCheck
import com.lagradost.cloudstream4.aggregation.SourceRanking
import com.lagradost.cloudstream4.aggregation.SourceVersion
import com.lagradost.cloudstream4.aggregation.StreamProbes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** One thing to play: a movie or an episode, with every provider route to it. */
data class PlayRequest(
    val title: String,
    val episodeLabel: String?,
    val bindings: List<EpisodeSourceBinding>,
    /** Catalogue runtime, for the duration checks of the source loader. */
    val expectedSeconds: Double?,
    val isEpisode: Boolean,
    /** History entry template; position and duration are filled while playing. */
    val history: WatchEntry,
    /** The next episode, resolved when this one ends. */
    val next: (suspend () -> PlayRequest?)? = null,
    /** For French subtitles when only original-version sources exist. */
    val work: PlaybackWork? = null,
    /** A finished download: played from disk, without asking the providers. */
    val local: LocalMedia? = null,
)

/** A downloaded movie or episode: the video, a separate audio track if any, French subtitles. */
data class LocalMedia(val video: String, val audio: String? = null, val subtitles: List<String> = emptyList(),
    val version: SourceVersion? = null, val quality: Int = 0)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Searching(val title: String, val episodeLabel: String?, val found: Int) : PlaybackState
    data class Playing(
        val title: String, val episodeLabel: String?, val source: String, val quality: Int,
        val version: SourceVersion, val positionMs: Long, val durationMs: Long, val alternatives: Int,
        /** Versions the sources offer, and the remembered choice (Auto: the engine decides). */
        val versions: List<SourceVersion> = emptyList(), val preference: SourceVersion = SourceVersion.Auto,
    ) : PlaybackState
    data class Failed(val title: String, val message: String) : PlaybackState
}

/**
 * Plays canonical sources in mpv: starts as soon as the shared [PlaybackPolicy] allows, moves
 * silently to the next source when one fails or stalls, switches to a clearly better verified
 * source once every provider answered (reverting if it does not play), saves the position and
 * chains episodes in the same mpv window.
 */
class PlaybackController(private val scope: CoroutineScope) {
    companion object {
        const val VERSION_KEY = "source_version"
        const val SUBTITLE_SCALE_KEY = "subtitle_scale"
        const val AUTOPLAY_NEXT_KEY = "autoplay_next"
    }
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> get() = _state

    private var mpv: Mpv? = null
    private var session: Session? = null
    private var fullscreen = true

    fun setFullscreen(value: Boolean) { fullscreen = value }

    fun play(request: PlayRequest) {
        session?.cancel()
        val created = Session(request)
        session = created
        created.start()
    }

    fun stop() {
        session?.cancel()
        session = null
        mpv?.quit()
        mpv = null
        _state.value = PlaybackState.Idle
    }

    /** Skips to the next source (user request). */
    fun nextSource() { session?.skip() }

    /** The version to prefer from now on (like Android's player menu); switches now if one is available. */
    fun chooseVersion(version: SourceVersion) {
        prefs.edit().putString(VERSION_KEY, version.name).apply()
        session?.choose(version)
    }

    private val prefs get() = com.kloudstream.desktop.platform.DesktopPreferences.open("kloudstream")
    private fun preferredVersion(): SourceVersion =
        runCatching { SourceVersion.valueOf(prefs.getString(VERSION_KEY, null) ?: "Auto") }.getOrDefault(SourceVersion.Auto)

    private fun ensureMpv(title: String): Mpv {
        mpv?.takeIf { it.isAlive }?.let { return it }
        // Settings → Lecture: subtitle size.
        val started = Mpv.start(title, fullscreen, listOf("--sub-scale=" + (prefs.getString(SUBTITLE_SCALE_KEY, null) ?: "1.0")))
        mpv = started
        Thread({
            started.observe(listOf("time-pos", "duration", "paused-for-cache"),
                onProperty = { name, value -> session?.onProperty(started, name, value) },
                onEvent = { name, node -> session?.onEvent(started, name, node) })
            session?.onClosed(started)
        }, "mpv-observer").apply { isDaemon = true }.start()
        return started
    }

    private inner class Session(val request: PlayRequest) {
        @Volatile private var preference = preferredVersion()
        private val sources = mutableListOf<PlaybackSource>()
        private val subtitles = mutableListOf<SubtitleFile>()
        private val failed = mutableSetOf<List<Any?>>()
        private var current: PlaybackSource? = null
        private var previous: PlaybackSource? = null
        private var previousDurationMs = 0L
        private var trial = false
        @Volatile private var started = false
        @Volatile private var loadedAt = 0L
        @Volatile private var startPositionMs = 0L
        @Volatile private var positionMs = 0L
        @Volatile private var durationMs = 0L
        private var confirmed = false
        private var job: Job? = null
        @Volatile private var cancelled = false
        /** Every source failed while providers were still answering: the next one starts at once. */
        @Volatile private var waiting = false
        @Volatile private var waitingFromMs = 0L
        @Volatile private var acquisitionDone = false
        private val policy = PlaybackPolicy()

        fun start() {
            request.local?.let { return startLocal(it) }
            _state.value = PlaybackState.Searching(request.title, request.episodeLabel, 0)
            job = scope.launch(Dispatchers.IO) {
                val ready = CompletableDeferred<Unit>()
                val acquisition = launch {
                    try {
                        CanonicalSourceLoader().load(request.bindings, clearCache = false, casting = false,
                            preference = preference, expectedSeconds = request.expectedSeconds, episode = request.isEpisode,
                            work = request.work,
                            onSource = { source ->
                                synchronized(sources) {
                                    sources.removeAll { PlaybackSources.key(it.link) == PlaybackSources.key(source.link) }
                                    sources += source
                                }
                                if (current == null) _state.value = PlaybackState.Searching(request.title, request.episodeLabel, sources.size)
                                if (waiting) {
                                    waiting = false
                                    scope.launch(Dispatchers.IO) { ranked().firstOrNull()?.let { load(it, waitingFromMs) } }
                                }
                            },
                            onSubtitle = { synchronized(subtitles) { subtitles += it } },
                            onReady = { ready.complete(Unit) })
                    } finally {
                        acquisitionDone = true
                        ready.complete(Unit)
                        if (waiting && ranked().isEmpty()) fail("Aucune source n'a pu être lue.")
                    }
                }
                ready.await()
                val first = ranked().firstOrNull()
                if (first == null) {
                    acquisition.join()
                    val best = ranked().firstOrNull() ?: return@launch fail("Aucune source n'est disponible pour le moment.")
                    load(best, WatchHistory.resumePositionMs(request.history.identity, request.history.episodeKey))
                } else {
                    load(first, WatchHistory.resumePositionMs(request.history.identity, request.history.episodeKey))
                }
                launch { watch() }
                acquisition.join()
                upgrade()
            }
        }

        /** Watchdog: a source that does not start within 30 s is replaced; progress is saved. */
        private suspend fun watch() {
            var lastSave = 0L
            while (currentCoroutineContext().isActive && !cancelled) {
                delay(1_000)
                if (!started && loadedAt > 0 && System.currentTimeMillis() - loadedAt > 30_000) {
                    AppLog.warn("Source did not start in time: ${current?.link?.name}")
                    onError("stalled")
                }
                if (started && System.currentTimeMillis() - lastSave > 5_000) { lastSave = System.currentTimeMillis(); saveProgress() }
            }
        }

        @Suppress("DEPRECATION")
        private fun startLocal(local: LocalMedia) {
            val version = local.version ?: SourceVersion.Unknown
            val link = ExtractorLink("Local", "Téléchargé", local.video, "", local.quality, emptyMap(), null, ExtractorLinkType.VIDEO)
            val source = PlaybackSource(link, PlaybackSourceMetadata(version, "Téléchargement", 0, check = SourceCheck.Verified,
                verifiedHeight = local.quality.takeIf { it > 0 }, addedSubtitles = local.subtitles.isNotEmpty() && version != SourceVersion.VF))
            synchronized(sources) { sources += source }
            synchronized(subtitles) { subtitles += local.subtitles.map { SubtitleFile("Français", it) } }
            acquisitionDone = true
            job = scope.launch(Dispatchers.IO) {
                load(source, WatchHistory.resumePositionMs(request.history.identity, request.history.episodeKey))
                watch()
            }
        }

        fun cancel() {
            cancelled = true
            if (started) saveProgress()
            job?.cancel()
        }

        fun skip() = scope.launch(Dispatchers.IO) { trial = false; onError("skipped") }

        private fun score(source: PlaybackSource) = SourceRanking.score(source.metadata, source.quality, preference)

        private fun ranked(): List<PlaybackSource> = synchronized(sources) {
            sources.filter { PlaybackSources.key(it.link) !in failed }.sortedWith(SourceRanking.comparator(::score, { 0 },
                { it.quality }, { it.metadata.ordinal }, { it.link.name }, { it.link.url }))
        }

        fun choose(version: SourceVersion) = scope.launch(Dispatchers.IO) {
            preference = version
            val playing = current
            val target = ranked().firstOrNull { version == SourceVersion.Auto || it.version == version }
            if (playing == null || target == null || PlaybackSources.key(target.link) == PlaybackSources.key(playing.link)) {
                publishPlaying()
                return@launch
            }
            AppLog.info("Version ${version.name} chosen: ${target.link.name}")
            trial = false
            previous = null
            load(target, if (started) positionMs else startPositionMs)
        }

        private fun load(source: PlaybackSource, startMs: Long) {
            if (cancelled) return
            val player = try { ensureMpv(request.title) } catch (e: Exception) {
                AppLog.error("Could not start mpv", e)
                return fail("Le lecteur vidéo (mpv) est introuvable ou n'a pas pu démarrer.")
            }
            current = source
            started = false
            startPositionMs = startMs
            loadedAt = System.currentTimeMillis()
            val link = source.link
            val headers = linkedMapOf<String, String>()
            if (link.referer.isNotBlank()) headers["Referer"] = link.referer
            link.headers.forEach { (key, value) -> headers[key] = value }
            val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
            val fields = headers.filterKeys { !it.equals("User-Agent", true) }.map { (key, value) -> "$key: $value" }
            val title = listOfNotNull(request.title, request.episodeLabel).joinToString(" — ")
            AppLog.info("Playing ${link.name} (${PlaybackSources.quality(link)}p, ${source.version}, ${source.metadata.check}) from ${startMs / 1000}s")
            player.commands(
                listOf("set_property", "http-header-fields", fields),
                listOf("set_property", "user-agent", agent),
                listOf("set_property", "start", if (startMs > 1_000) "%.1f".format(java.util.Locale.ROOT, startMs / 1000.0) else "none"),
                listOf("set_property", "force-media-title", title),
                listOf("loadfile", link.url, "replace"),
            )
            publishPlaying()
        }

        private fun publishPlaying() {
            val source = current ?: return
            val versions = ranked().map { it.version }.filter { it != SourceVersion.Unknown && it != SourceVersion.Auto }
                .distinct().sortedByDescending(PlaybackSources::versionRank)
            _state.value = PlaybackState.Playing(request.title, request.episodeLabel, source.link.name,
                PlaybackSources.quality(source.link), source.version, positionMs, durationMs, ranked().size - 1,
                versions, preference)
        }

        fun onProperty(player: Mpv, name: String, value: JsonNode?) {
            if (cancelled || player !== mpv) return
            when (name) {
                "time-pos" -> value?.takeIf { it.isNumber }?.asDouble()?.let { seconds ->
                    positionMs = (seconds * 1000).toLong()
                    if (seconds > 0.5 && !started) { started = true; AppLog.info("Playback started: ${current?.link?.name}") }
                    checkTrial()
                    publishPlaying()
                }
                "duration" -> value?.takeIf { it.isNumber }?.asDouble()?.let { durationMs = (it * 1000).toLong(); publishPlaying() }
            }
        }

        fun onEvent(player: Mpv, name: String, node: JsonNode) {
            if (cancelled || player !== mpv) return
            when (name) {
                "file-loaded" -> {
                    addSubtitles(player)
                    request.local?.audio?.let { player.commands(listOf("audio-add", it, "select")) }
                }
                "end-file" -> when (node.path("reason").asText()) {
                    "error" -> scope.launch(Dispatchers.IO) { onError(node.path("file_error").asText("error")) }
                    "eof" -> scope.launch(Dispatchers.IO) { onEnded() }
                }
            }
        }

        fun onClosed(player: Mpv) {
            if (player !== mpv) return
            mpv = null
            if (cancelled) return
            if (started) saveProgress()
            cancelled = true
            job?.cancel()
            if (session === this) { session = null; _state.value = PlaybackState.Idle }
        }

        /**
         * French subtitles are selected for original audio only: a VO source, or one that is VOST
         * through subtitles fetched for it (those first). VF and VOST streams carry their own.
         */
        private fun addSubtitles(player: Mpv) {
            val playing = current
            val list = synchronized(subtitles) { subtitles.toList() }.sortedBy { if (ExternalSubtitles.isExternal(it)) 0 else 1 }
            if (list.isEmpty()) return
            val wantFrench = playing != null && (playing.metadata.addedSubtitles || playing.version == SourceVersion.VO)
            var selected = false
            val commands = list.take(12).map { subtitle ->
                val french = subtitle.lang.lowercase().let { it.startsWith("fr") || "fran" in it }
                val flag = if (wantFrench && french && !selected) { selected = true; "select" } else "auto"
                listOf("sub-add", subtitle.url, flag, subtitle.lang, subtitle.lang)
            }
            player.commands(*commands.toTypedArray())
        }

        private fun checkTrial() {
            val source = current ?: return
            if (!confirmed && positionMs - startPositionMs >= PlaybackVerification.CONFIRMED_AFTER_MS) {
                confirmed = true
                providerOf(source)?.let { ProviderHealth.recordPlayback(it, true) }
            }
            if (trial && durationMs > 0 && previousDurationMs > 0 && !PlaybackVerification.sameWork(durationMs, previousDurationMs)) {
                AppLog.warn("Upgrade duration ${durationMs / 1000}s differs from ${previousDurationMs / 1000}s: reverting")
                scope.launch(Dispatchers.IO) { onError("duration") }
            } else if (trial && positionMs - startPositionMs >= PlaybackVerification.CONFIRMED_AFTER_MS) {
                trial = false
            }
        }

        private fun providerOf(source: PlaybackSource): String? = source.metadata.providerId ?: source.metadata.provider

        @Synchronized
        private fun onError(reason: String) {
            if (cancelled) return
            val failedSource = current ?: return
            AppLog.warn("Source failed ($reason): ${failedSource.link.name}")
            failed += PlaybackSources.key(failedSource.link)
            if (reason != "skipped") providerOf(failedSource)?.let { ProviderHealth.recordPlayback(it, false) }
            val resumeAt = if (started) positionMs else startPositionMs
            val back = previous?.takeIf { trial }
            trial = false
            previous = null
            confirmed = false
            val next = back ?: ranked().firstOrNull()
            if (next == null) {
                if (!acquisitionDone) {
                    waiting = true
                    waitingFromMs = resumeAt
                    current = null
                    loadedAt = 0
                    _state.value = PlaybackState.Searching(request.title, request.episodeLabel, 0)
                    return
                }
                return fail("Aucune source n'a pu être lue.")
            }
            load(next, resumeAt)
        }

        private suspend fun upgrade() {
            val playing = current ?: return
            if (cancelled || trial) return
            val currentVersion = playing.version
            val candidates = ranked().filter {
                PlaybackSources.key(it.link) != PlaybackSources.key(playing.link) &&
                    it.metadata.check == SourceCheck.Verified &&
                    (it.version != SourceVersion.VO || currentVersion == SourceVersion.VO) &&
                    policy.shouldUpgrade(score(playing), score(it))
            }.take(3)
            for (candidate in candidates) {
                if (!StreamProbes.reachable(candidate.link)) {
                    failed += PlaybackSources.key(candidate.link)
                    continue
                }
                // Wait until the current source really plays, so the switch keeps the position.
                withTimeoutOrNull(60_000) { while (!started && !cancelled) delay(500) }
                if (cancelled || current !== playing) return
                AppLog.info("Upgrading to ${candidate.link.name}")
                previous = playing
                previousDurationMs = durationMs
                trial = true
                confirmed = false
                load(candidate, positionMs)
                return
            }
        }

        private fun saveProgress() {
            if (durationMs <= 0 && positionMs <= 0) return
            WatchHistory.save(request.history.copy(positionMs = positionMs, durationMs = durationMs))
        }

        private suspend fun onEnded() {
            if (cancelled) return
            positionMs = durationMs
            saveProgress()
            val next = request.next?.takeIf { prefs.getBoolean(AUTOPLAY_NEXT_KEY, true) }?.let { runCatching { it() }.getOrNull() }
            if (next != null && !cancelled) {
                play(next)
            } else {
                stop()
            }
        }

        private fun fail(message: String) {
            AppLog.warn("Playback failed: $message")
            _state.value = PlaybackState.Failed(request.title, message)
            cancelled = true
            val player = mpv ?: return
            player.command("show-text", message, 4000)
            scope.launch(Dispatchers.IO) { delay(4_000); if (mpv === player) { player.quit(); mpv = null } }
        }
    }
}
