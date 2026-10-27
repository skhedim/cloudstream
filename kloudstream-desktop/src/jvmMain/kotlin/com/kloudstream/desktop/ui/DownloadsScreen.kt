package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.downloads.DownloadItem
import com.kloudstream.desktop.downloads.DownloadState
import com.kloudstream.desktop.downloads.Downloads
import com.kloudstream.desktop.player.LocalMedia
import com.kloudstream.desktop.player.PlayRequest

/** Movies and episodes saved for offline viewing, newest first. */
@Composable
fun DownloadsScreen() {
    val items by Downloads.items.collectAsState()
    Column(Modifier.fillMaxSize().background(Kloud.Background)) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Téléchargements", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            SecondaryButton("Ouvrir le dossier", KloudIcons.Folder) {
                runCatching { Downloads.folder.mkdirs(); java.awt.Desktop.getDesktop().open(Downloads.folder) }
            }
        }
        if (items.isEmpty()) {
            Text("Rien de téléchargé pour l'instant. Sur la page d'un film ou d'un épisode, « Télécharger » le garde pour le regarder hors ligne.",
                color = Kloud.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { DownloadRow(it) }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Kloud.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(76.dp).height(112.dp).clip(RoundedCornerShape(6.dp))) { NetImage(item.poster, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.episodeLabel?.let { Text(it, color = Kloud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(status(item), color = if (item.state == DownloadState.Failed) Kloud.Accent else Kloud.Muted, fontSize = 13.sp)
            if (item.state == DownloadState.Downloading || item.state == DownloadState.Queued) {
                LinearProgressIndicator(progress = { item.progress.toFloat().coerceIn(0f, 1f) }, color = Kloud.Accent,
                    trackColor = Kloud.SurfaceHigh, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
        Spacer(Modifier.width(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (item.state) {
                DownloadState.Done -> PrimaryButton("Lire", Icons.Filled.PlayArrow) { play(item) }
                DownloadState.Failed -> SecondaryButton("Réessayer", Icons.Filled.Refresh) { Downloads.retry(item) }
                else -> SecondaryButton("Annuler", Icons.Filled.Close) { Downloads.cancel(item) }
            }
            if (item.state == DownloadState.Done || item.state == DownloadState.Failed)
                SecondaryButton("Supprimer", Icons.Filled.Delete) { Downloads.delete(item) }
        }
    }
}

private fun status(item: DownloadItem): String = when (item.state) {
    DownloadState.Queued -> "En attente…"
    DownloadState.Downloading -> listOfNotNull("Téléchargement…",
        "${(item.progress * 100).toInt()} %".takeIf { item.progress > 0 }, size(item.bytes).takeIf { item.bytes > 0 }).joinToString(" · ")
    DownloadState.Done -> listOfNotNull("Prêt", versionLabel(item.version ?: com.lagradost.cloudstream4.aggregation.SourceVersion.Unknown)
        .takeIf { item.version != null && item.version != com.lagradost.cloudstream4.aggregation.SourceVersion.Unknown },
        "${item.quality}p".takeIf { item.quality > 0 }, size(item.bytes),
        "sous-titres français".takeIf { item.subtitles.isNotEmpty() }).joinToString(" · ")
    DownloadState.Failed -> item.error ?: "Échec du téléchargement."
}

fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f Go".format(bytes / 1e9)
    else -> "${bytes / 1_000_000} Mo"
}

/** Offline playback through the usual player: position, history and subtitles as online. */
private fun play(item: DownloadItem) {
    val history = item.history ?: return
    val video = item.video ?: return
    AppModel.player.play(PlayRequest(item.title, item.episodeLabel, item.request.bindings, item.request.expectedSeconds,
        item.request.isEpisode, history, work = item.request.work,
        local = LocalMedia(video, item.audio, item.subtitles, item.version, item.quality)))
}
