package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.DetailModel
import com.kloudstream.desktop.app.Screen
import com.kloudstream.desktop.downloads.DownloadItem
import com.kloudstream.desktop.downloads.DownloadState
import com.kloudstream.desktop.downloads.Downloads
import com.kloudstream.desktop.library.WatchHistory
import com.lagradost.cloudstream4.aggregation.CanonicalEpisodeRow
import com.lagradost.cloudstream4.aggregation.CastMember
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(screen: Screen.Detail, onBack: () -> Unit) {
    val model = remember(screen.request) { DetailModel(AppModel.scope, screen.request).also { it.load() } }
    val state by model.state.collectAsState()
    val history by WatchHistory.entries.collectAsState()
    val downloads by Downloads.items.collectAsState()
    val scope = rememberCoroutineScope()
    val entry = history.firstOrNull { it.identity == state.model?.request?.identity }
    var seasonIndex by remember(state.seasons.size) { mutableIntStateOf(-1) }
    // Open on the season of the episode to resume.
    LaunchedEffect(state.seasons, entry?.episodeKey) {
        if (seasonIndex < 0 && state.seasons.isNotEmpty()) {
            val resume = model.resumeRow()
            seasonIndex = state.seasons.indexOfFirst { group -> group.episodes.any { it == resume } }.coerceAtLeast(0)
        }
    }
    fun play(row: CanonicalEpisodeRow?) = scope.launch { model.playRequest(row)?.let { AppModel.player.play(it) } }
    fun download(row: CanonicalEpisodeRow?) = scope.launch { model.playRequest(row)?.let { Downloads.enqueue(it, state.poster ?: screen.poster) } }
    fun downloadOf(episodeKey: String?): DownloadItem? = state.model?.let { m -> downloads.firstOrNull { it.id == Downloads.id(m.request.identity, episodeKey) } }
    // "Lecture" on the home hero: play as soon as the page knows its sources (resume point for series).
    var autoplayed by remember(screen) { androidx.compose.runtime.mutableStateOf(!screen.autoplay) }
    LaunchedEffect(state.model) {
        val loaded = state.model ?: return@LaunchedEffect
        if (!autoplayed) {
            autoplayed = true
            play(if (loaded.family == "series") model.resumeRow() else null)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Kloud.Background)) {
        val headerHeight = (maxWidth * 0.48f).coerceIn(420.dp, 720.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(headerHeight)) {
                    NetImage(state.backdrop ?: screen.poster, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2000000), 0.6f to Color(0x55000000), 1f to Color.Transparent)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Kloud.Background)))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 32.dp).widthIn(max = 720.dp)) {
                        val logo = state.logo
                        if (logo != null) NetImage(logo, Modifier.width(440.dp).height(150.dp), ContentScale.Fit, Color.Transparent)
                        else Text(state.title ?: screen.title, fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 52.sp)
                        Spacer(Modifier.height(14.dp))
                        MetaLine(state.rating, state.year, state.runtimeMinutes.takeIf { state.model?.family != "series" }, state.genres)
                        state.overview?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = Color.White, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(22.dp))
                        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val detail = state.model
                            when {
                                detail == null && state.loading -> LoadingLabel("Recherche du titre chez les sources…")
                                detail == null -> Text(state.error ?: "Titre indisponible.", color = Kloud.Muted)
                                detail.family == "movie" -> {
                                    val resumeMs = WatchHistory.resumePositionMs(detail.request.identity, null)
                                    PrimaryButton(if (resumeMs > 0) "Reprendre" else "Lecture", Icons.Filled.PlayArrow) { play(null) }
                                    if (resumeMs > 0) Text("à ${formatTime(resumeMs)}", color = Kloud.Muted)
                                    DownloadButton(downloadOf(null)) { download(null) }
                                }
                                else -> {
                                    val resume = model.resumeRow()
                                    val started = entry != null
                                    PrimaryButton(if (started) "Reprendre" else "Lecture", Icons.Filled.PlayArrow) { play(resume) }
                                    resume?.let { Text(DetailModel.episodeLabel(it), color = Kloud.Muted, maxLines = 1) }
                                }
                            }
                            // In the browser, like Android's trailer button.
                            state.trailerUrl?.let { url ->
                                SecondaryButton("Bande-annonce", KloudIcons.Trailer) {
                                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                                }
                            }
                            if (detail != null && state.loading) LoadingLabel("Autres sources en cours…")
                        }
                    }
                    IconButton(onClick = onBack, modifier = Modifier.padding(16.dp).background(Color(0x66000000), RoundedCornerShape(50))) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
                    }
                }
            }
            val cast = state.cast
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = 4.dp, bottom = 24.dp)) {
                        SectionTitle("Distribution")
                        ScrollableRow(spacing = 18.dp, arrowHeight = 150.dp) {
                            items(cast) { CastItem(it) }
                        }
                    }
                }
            }
            val seasons = state.seasons
            if (seasons.isNotEmpty()) {
                item(key = "seasons") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(seasons.size) { index ->
                            val selected = index == seasonIndex.coerceAtLeast(0)
                            Text(seasons[index].name, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else Kloud.SurfaceHigh)
                                    .clickable { seasonIndex = index }.padding(horizontal = 18.dp, vertical = 9.dp))
                        }
                    }
                }
                val group = seasons[seasonIndex.coerceIn(0, seasons.lastIndex)]
                items(group.episodes, key = { "ep:" + it.episode.key }) { row ->
                    val progress = state.model?.let { WatchHistory.progress(it.request.identity, row.episode.key) }
                    EpisodeItem(row, progress?.let { p -> if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null },
                        downloadOf(row.episode.key), { download(row) }) { play(row) }
                }
            }
            // As under Android's detail page: what the sources relate to this work.
            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    Box(Modifier.padding(top = 24.dp)) {
                        CardRow("Titres similaires", state.similar, { card ->
                            AppModel.navigator.open(Screen.Detail(card.request(), card.title, card.poster))
                        })
                    }
                }
            }
        }
    }
}

/** A round photo (initials without one), the actor's name and role. */
@Composable
private fun CastItem(member: CastMember) {
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(50)).background(Kloud.SurfaceHigh), contentAlignment = Alignment.Center) {
            val initials = member.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            Text(initials, color = Kloud.Muted, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (member.photoUrl != null) NetImage(member.photoUrl, Modifier.matchParentSize(), placeholder = Color.Transparent)
        }
        Spacer(Modifier.height(8.dp))
        Text(member.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        member.character?.let {
            Text(it, color = Kloud.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Download a movie: its state once started (progress, done), else the button. */
@Composable
private fun DownloadButton(item: DownloadItem?, onDownload: () -> Unit) {
    when (item?.state) {
        null -> SecondaryButton("Télécharger", KloudIcons.Download, onDownload)
        DownloadState.Done -> SecondaryButton("Téléchargé", KloudIcons.DownloadDone) { AppModel.navigator.open(Screen.Downloads) }
        DownloadState.Failed -> SecondaryButton("Réessayer le téléchargement", KloudIcons.Download) { Downloads.retry(item) }
        else -> SecondaryButton(if (item.progress > 0) "Téléchargement ${(item.progress * 100).toInt()} %" else "En attente…", KloudIcons.Download) {
            AppModel.navigator.open(Screen.Downloads)
        }
    }
}

@Composable
private fun EpisodeDownload(item: DownloadItem?, onDownload: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(50)).clickable {
        when (item?.state) { null -> onDownload(); DownloadState.Failed -> Downloads.retry(item); else -> AppModel.navigator.open(Screen.Downloads) }
    }, contentAlignment = Alignment.Center) {
        when (item?.state) {
            DownloadState.Done -> Icon(KloudIcons.DownloadDone, "Téléchargé", tint = Kloud.Accent, modifier = Modifier.size(26.dp))
            DownloadState.Downloading, DownloadState.Queued -> {
                CircularProgressIndicator(progress = { item.progress.toFloat().coerceIn(0f, 1f) }, color = Kloud.Accent,
                    trackColor = Kloud.SurfaceHigh, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            else -> Icon(KloudIcons.Download, "Télécharger", tint = if (item?.state == DownloadState.Failed) Kloud.Accent else Kloud.Muted,
                modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun EpisodeItem(row: CanonicalEpisodeRow, progress: Float?, download: DownloadItem?, onDownload: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
        .background(if (hovered) Kloud.SurfaceHigh else Color.Transparent)
        .hoverable(interaction).clickable(interaction, null, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(row.episode.episode?.toString() ?: "•", color = Kloud.Muted, fontSize = 22.sp, modifier = Modifier.width(44.dp))
        Box(Modifier.width(220.dp).height(124.dp).clip(RoundedCornerShape(6.dp))
            .border(if (hovered) 2.dp else 0.dp, if (hovered) Color.White else Color.Transparent, RoundedCornerShape(6.dp))) {
            NetImage(row.original.posterUrl, Modifier.fillMaxSize())
            if (hovered) Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (progress != null && progress > 0f) LinearProgressIndicator(progress = { progress }, color = Kloud.Accent, trackColor = Color(0x66FFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(DetailModel.cleanName(row.original) ?: "Épisode ${row.episode.episode ?: ""}", color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Documented in seconds, but some extensions give minutes.
            row.original.runTime?.takeIf { it > 0 }?.let { Text("${if (it > 300) it / 60 else it} min", color = Kloud.Muted, fontSize = 13.sp) }
            row.original.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Kloud.Muted, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        EpisodeDownload(download, onDownload)
    }
}

@Composable
fun LoadingLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Kloud.Muted)
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
