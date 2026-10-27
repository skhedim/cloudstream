package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.MediaCard
import com.kloudstream.desktop.app.Screen
import com.kloudstream.desktop.app.Startup

@Composable
fun KloudApp() {
    InstallImageLoader()
    val startup by AppModel.startup.collectAsState()
    KloudTheme {
        Box(Modifier.fillMaxSize().background(Kloud.Background)) {
            when (val s = startup) {
                is Startup.Loading -> Splash(s.message)
                is Startup.Failed -> Splash("Démarrage impossible : ${s.message}", loading = false)
                Startup.Ready -> Shell()
            }
        }
    }
}

@Composable
private fun Splash(message: String, loading: Boolean = true) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(64)
        Spacer(Modifier.height(28.dp))
        if (loading) CircularProgressIndicator(color = Kloud.Accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Kloud.Muted)
    }
}

@Composable
fun Logo(size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("K", fontSize = size.sp, fontWeight = FontWeight.Black, color = Kloud.Accent)
        Text("loudStream", fontSize = (size * 0.62).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

private fun open(card: MediaCard) = AppModel.navigator.open(Screen.Detail(card.request(), card.title, card.poster))
private fun play(card: MediaCard) = AppModel.navigator.open(Screen.Detail(card.request(), card.title, card.poster, autoplay = true))

@Composable
private fun Shell() {
    val stack by AppModel.navigator.stack.collectAsState()
    val screen = stack.last()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            NavRail(screen)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (screen) {
                    Screen.Home -> HomeScreen(::open, ::openEntry, ::play)
                    Screen.Search -> SearchScreen(::open)
                    Screen.Settings -> SettingsScreen()
                    is Screen.Detail -> DetailScreen(screen) { AppModel.navigator.back() }
                }
            }
        }
        PlayerBar()
    }
}

@Composable
private fun NavRail(screen: Screen) {
    Column(Modifier.width(84.dp).fillMaxHeight().background(Kloud.Background).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("K", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Kloud.Accent)
        Spacer(Modifier.height(24.dp))
        RailItem(Icons.Filled.Home, "Accueil", screen == Screen.Home || screen is Screen.Detail) { AppModel.navigator.open(Screen.Home) }
        RailItem(Icons.Filled.Search, "Recherche", screen == Screen.Search) { AppModel.navigator.open(Screen.Search) }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Filled.Settings, "Réglages", screen == Screen.Settings) { AppModel.navigator.open(Screen.Settings) }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if (hovered) Kloud.SurfaceHigh else Color.Transparent)
        .hoverable(interaction).clickable(interaction, null, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (selected) Color.White else Kloud.Faint, modifier = Modifier.size(28.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Color.White else Kloud.Faint)
    }
}
