package com.kloudstream.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.HeroItem
import com.kloudstream.desktop.app.MediaCard
import com.kloudstream.desktop.app.Screen
import com.kloudstream.desktop.library.WatchEntry
import com.kloudstream.desktop.library.WatchHistory
import com.lagradost.cloudstream3.ui.home.aggregation.HomeShelf
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onOpen: (MediaCard) -> Unit, onResume: (WatchEntry) -> Unit) {
    val state by AppModel.home.state.collectAsState()
    val history by WatchHistory.entries.collectAsState()
    val resume = history.filter { !it.finished || it.family == "series" }.take(20)
    LazyColumn(Modifier.fillMaxSize().background(Kloud.Background), contentPadding = PaddingValues(bottom = 64.dp)) {
        item(key = "hero") {
            if (state.hero.isNotEmpty()) Hero(state.hero, onOpen)
            else Spacer(Modifier.height(96.dp))
        }
        if (resume.isNotEmpty()) item(key = "resume") { ResumeRow(resume, onResume) }
        items(state.rows, key = { it.shelf.name }) { row ->
            CardRow(row.title, row.cards, onOpen, ranked = row.shelf == HomeShelf.Top10)
        }
        if (state.loading) item(key = "loading") {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.status ?: "Chargement…", color = Kloud.Muted)
            }
        }
        if (!state.loading && state.rows.isEmpty()) item(key = "empty") {
            Text("Aucun contenu pour le moment. Vérifiez la connexion puis actualisez depuis les réglages.",
                color = Kloud.Muted, modifier = Modifier.padding(48.dp))
        }
    }
}

/** Featured works of the week, rotating every 9 seconds. */
@Composable
private fun Hero(items: List<HeroItem>, onOpen: (MediaCard) -> Unit) {
    var index by remember(items) { mutableIntStateOf(0) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Rotates every 9 seconds, never under the pointer (the user may be about to click).
    LaunchedEffect(items, hovered) {
        while (!hovered) { delay(9_000); index = (index + 1) % items.size }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().hoverable(interaction)) {
        val height = (maxWidth * 0.5f).coerceIn(420.dp, 760.dp)
        Crossfade(items[index], label = "hero") { item ->
            Box(Modifier.fillMaxWidth().height(height)) {
                NetImage(item.highlight.backdropUrl, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.55f to Color(0x33000000), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Kloud.Background)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 56.dp).widthIn(max = 640.dp)) {
                    val logo = item.highlight.logoUrl
                    if (logo != null) NetImage(logo, Modifier.heightIn(max = 140.dp).width(420.dp).height(140.dp), ContentScale.Fit, Color.Transparent)
                    else Text(item.highlight.title, fontSize = 52.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 56.sp)
                    Spacer(Modifier.height(14.dp))
                    MetaLine(item.highlight.rating, item.highlight.year, item.highlight.runtimeMinutes, item.highlight.genres)
                    item.highlight.overview?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Color.White, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton("Lecture") { onOpen(item.card) }
                        SecondaryButton("Plus d'infos", Icons.Outlined.Info) { onOpen(item.card) }
                    }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.indices.forEach { i ->
                        Box(Modifier.size(if (i == index) 10.dp else 7.dp).background(if (i == index) Color.White else Kloud.Faint, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
fun MetaLine(rating: Double?, year: Int?, runtime: Int?, genres: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        rating?.let { Text("★ " + "%.1f".format(it), color = Kloud.Rating, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        year?.let { Text("$it", color = Color.White, fontSize = 15.sp) }
        runtime?.takeIf { it > 0 }?.let { Text(if (it >= 60) "${it / 60} h ${"%02d".format(it % 60)}" else "$it min", color = Color.White, fontSize = 15.sp) }
        if (genres.isNotEmpty()) Text(genres.take(3).joinToString(" · "), color = Kloud.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun ResumeRow(entries: List<WatchEntry>, onResume: (WatchEntry) -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        SectionTitle("Continuer à regarder")
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = { it.identity }) { entry ->
                val card = remember(entry) { entry.toCard() }
                val subtitle = if (entry.season != null && entry.episode != null) "S${entry.season} É${entry.episode}" else entry.year?.toString()
                if (card != null) PosterCard(card, { onResume(entry) }, progress = entry.progress.takeIf { !entry.finished }, subtitle = subtitle)
            }
        }
    }
}

/** History entries reuse the poster card with their saved title and artwork. */
private fun WatchEntry.toCard(): MediaCard? = request?.let { MediaCard.of(title, year, poster, null, it) }

fun openEntry(entry: WatchEntry) {
    val request = entry.request ?: return
    AppModel.navigator.open(Screen.Detail(request, entry.title, entry.poster))
}
