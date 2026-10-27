package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.extensions.ExtensionState
import com.kloudstream.desktop.player.Mpv
import com.kloudstream.desktop.platform.DesktopPreferences
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val extensions by ExtensionManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    val prefs = remember { DesktopPreferences.open("kloudstream") }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("player_fullscreen", true)) }
    Column(Modifier.fillMaxSize().background(Kloud.Background).verticalScroll(rememberScrollState()).padding(48.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text("Réglages", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Section("Sources") {
            Text("Les sources sont des extensions CloudStream sélectionnées d'après des mesures de disponibilité et de qualité. " +
                "Elles sont installées et mises à jour automatiquement.", color = Kloud.Muted)
            extensions.forEach { status ->
                Row(Modifier.fillMaxWidth().background(Kloud.Surface, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status.extension.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Version ${status.extension.version}" + status.providers.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString() }.orEmpty(),
                            color = Kloud.Muted, fontSize = 13.sp)
                        status.error?.let { Text(it, color = Kloud.Accent, fontSize = 12.sp, maxLines = 2) }
                    }
                    Text(when (status.state) { ExtensionState.Loaded -> "Active"; ExtensionState.Failed -> "Erreur"; ExtensionState.Installed -> "Installée" },
                        color = if (status.state == ExtensionState.Failed) Kloud.Accent else Kloud.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SecondaryButton(if (updating) "Mise à jour…" else "Mettre à jour les sources", Icons.Filled.Refresh) {
                    if (!updating) scope.launch {
                        updating = true
                        val count = runCatching { AppModel.updateExtensions() }.getOrElse { -1 }
                        updateResult = when { count < 0 -> "La mise à jour a échoué."; count == 0 -> "Les sources sont à jour."; else -> "$count source(s) mise(s) à jour." }
                        updating = false
                    }
                }
                updateResult?.let { Text(it, color = Kloud.Muted) }
            }
            SecondaryButton("Actualiser l'accueil", Icons.Filled.Refresh) { AppModel.home.load() }
        }

        Section("Lecture") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lancer la lecture en plein écran", color = Color.White)
                    Text("Échap ou F pour quitter le plein écran pendant la lecture.", color = Kloud.Muted, fontSize = 13.sp)
                }
                Switch(fullscreen, { value ->
                    fullscreen = value
                    prefs.edit().putBoolean("player_fullscreen", value).apply()
                    AppModel.player.setFullscreen(value)
                }, colors = SwitchDefaults.colors(checkedTrackColor = Kloud.Accent))
            }
            Text("Lecteur : " + (Mpv.executable()?.path ?: "mpv introuvable"), color = Kloud.Muted, fontSize = 13.sp)
        }

        Section("Données") {
            Text("Historique, sources et journaux : " + AppDirs.root.path, color = Kloud.Muted)
            SecondaryButton("Ouvrir le dossier") { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.root) } }
        }

        Section("À propos") {
            Text("KloudStream ${System.getProperty("kloudstream.version") ?: "dev"} — basé sur CloudStream (GPL-3.0).", color = Kloud.Muted)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        content()
        Spacer(Modifier.height(4.dp))
    }
}
