package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.player.PlaybackState
import com.lagradost.cloudstream4.aggregation.SourceVersion

/** Shown while something plays in the mpv window (what plays, from which source, controls) or after it failed. */
@Composable
fun PlayerBar() {
    val state by AppModel.player.state.collectAsState()
    if (state == PlaybackState.Idle || state is PlaybackState.Searching) return
    Column(Modifier.fillMaxWidth().background(Kloud.Surface)) {
        val playing = state as? PlaybackState.Playing
        if (playing != null && playing.durationMs > 0) {
            LinearProgressIndicator(progress = { (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) },
                color = Kloud.Accent, trackColor = Kloud.SurfaceHigh, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when (val s = state) {
                    is PlaybackState.Playing -> {
                        Text(listOfNotNull(s.title, s.episodeLabel).joinToString(" — "), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val version = when (s.version) { SourceVersion.VF -> "VF"; SourceVersion.VOST -> "VOSTFR"; SourceVersion.VO -> "VO"; else -> null }
                        val time = if (s.durationMs > 0) "${formatTime(s.positionMs)} / ${formatTime(s.durationMs)}" else null
                        Text(listOfNotNull("Lecture dans le lecteur", if (s.quality > 0) "${s.quality}p" else null, version, time).joinToString(" · "),
                            color = Kloud.Muted, fontSize = 13.sp)
                    }
                    is PlaybackState.Failed -> {
                        Text(s.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(s.message, color = Kloud.Accent, fontSize = 13.sp)
                    }
                    PlaybackState.Idle, is PlaybackState.Searching -> Unit
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state is PlaybackState.Playing && (state as PlaybackState.Playing).alternatives > 0)
                    SecondaryButton("Autre source", Icons.Filled.Refresh) { AppModel.player.nextSource() }
                SecondaryButton(if (state is PlaybackState.Failed) "Fermer" else "Arrêter", Icons.Filled.Close) { AppModel.player.stop() }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** While sources are checked, a centred card says what is happening; the mpv window opens on top once one plays. */
@Composable
fun SearchingOverlay() {
    val state by AppModel.player.state.collectAsState()
    (state as? PlaybackState.Searching)?.let { SearchingCard(it) { AppModel.player.stop() } }
}

@Composable
internal fun SearchingCard(searching: PlaybackState.Searching, onCancel: () -> Unit) {
    // The scrim takes the clicks so the page underneath stays still meanwhile.
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).background(Kloud.Surface, RoundedCornerShape(16.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Kloud.Accent)
            Text(listOfNotNull(searching.title, searching.episodeLabel).joinToString(" — "), color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (searching.found == 0) "Recherche de la meilleure source…"
                else "Vérification des sources (${searching.found} trouvée${if (searching.found > 1) "s" else ""})…", color = Kloud.Muted)
            SecondaryButton("Annuler", Icons.Filled.Close, onCancel)
        }
    }
}
