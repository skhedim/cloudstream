package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.Updates
import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.downloads.Downloads
import com.kloudstream.desktop.extensions.ExtensionManager
import com.kloudstream.desktop.extensions.ExtensionState
import com.kloudstream.desktop.extensions.RepositoryPlugin
import com.kloudstream.desktop.library.WatchHistory
import com.kloudstream.desktop.platform.DesktopPreferences
import com.kloudstream.desktop.player.Mpv
import com.kloudstream.desktop.player.PlaybackController
import com.lagradost.cloudstream4.aggregation.SourceVersion
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().background(Kloud.Background).verticalScroll(rememberScrollState()).padding(48.dp).widthIn(max = 960.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Text("Réglages", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        SourcesSection()
        PlaybackSection()
        DownloadsSection()
        DataSection()
        AboutSection()
    }
}

@Composable
private fun SourcesSection() {
    val extensions by ExtensionManager.status.collectAsState()
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    Section("Sources") {
        Text("Les sources recommandées sont choisies d'après des mesures de disponibilité et de qualité ; elles sont installées et mises à jour automatiquement. " +
            "Vous pouvez en ajouter d'autres depuis un dépôt d'extensions CloudStream.", color = Kloud.Muted)
        extensions.forEach { status ->
            Row(Modifier.fillMaxWidth().background(Kloud.Surface, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.extension.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull("Version ${status.extension.version}", if (status.extension.user) "ajoutée" else "recommandée",
                        status.providers.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · "), color = Kloud.Muted, fontSize = 13.sp)
                    status.error?.let { Text(it, color = Kloud.Accent, fontSize = 12.sp, maxLines = 2) }
                }
                Text(when (status.state) { ExtensionState.Loaded -> "Active"; ExtensionState.Failed -> "Erreur"; ExtensionState.Installed -> "Installée" },
                    color = if (status.state == ExtensionState.Failed) Kloud.Accent else Kloud.Muted)
                if (status.extension.user) {
                    Spacer(Modifier.padding(start = 12.dp))
                    SecondaryButton("Retirer", Icons.Filled.Delete) { scope.launch { ExtensionManager.uninstall(status.extension.key) } }
                }
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
            SecondaryButton("Actualiser l'accueil", Icons.Filled.Refresh) { AppModel.home.load() }
            updateResult?.let { Text(it, color = Kloud.Muted) }
        }
        AddExtension(extensions.map { it.extension.internalName.lowercase() + "|" + it.extension.repository }.toSet())
    }
}

/** Browse a repository (its repo.json URL) and install extensions from it. */
@Composable
private fun AddExtension(installed: Set<String>) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var plugins by remember { mutableStateOf<List<RepositoryPlugin>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ajouter une extension", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Adresse du dépôt (…/repo.json)", color = Kloud.Faint) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Kloud.Accent, unfocusedBorderColor = Kloud.SurfaceHigh, cursorColor = Color.White))
            SecondaryButton("Afficher", Icons.Filled.Search) {
                if (url.isNotBlank()) scope.launch {
                    message = "Chargement…"; plugins = null
                    val found = runCatching { ExtensionManager.browse(url) }.getOrNull()
                    plugins = found
                    message = when { found == null -> "Dépôt illisible : vérifiez l'adresse."; found.isEmpty() -> "Ce dépôt ne propose aucune extension."; else -> null }
                }
            }
        }
        message?.let { Text(it, color = Kloud.Muted) }
        plugins?.forEach { plugin ->
            val done = plugin.internalName.lowercase() + "|" + url.trim() in installed
            Row(Modifier.fillMaxWidth().background(Kloud.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(plugin.language?.uppercase(), plugin.types.take(3).joinToString().takeIf { it.isNotBlank() },
                        "v${plugin.version}", "bêta".takeIf { plugin.status == 3 }).joinToString(" · "), color = Kloud.Muted, fontSize = 13.sp)
                }
                when {
                    done -> Text("Installée", color = Kloud.Muted)
                    busy == plugin.internalName -> Text("Installation…", color = Kloud.Muted)
                    else -> SecondaryButton("Installer", Icons.Filled.Add) {
                        scope.launch {
                            busy = plugin.internalName
                            message = runCatching { ExtensionManager.install(url, plugin) }
                                .fold({ "${plugin.name} est installée." }, { "Installation impossible : ${it.message}" })
                            busy = null
                        }
                    }
                }
            }
        }
        Text("Les extensions ajoutées ne sont pas vérifiées : certaines sont lentes ou trouvent peu de titres. Elles participent à la recherche et à la lecture.",
            color = Kloud.Faint, fontSize = 13.sp)
    }
}

@Composable
private fun PlaybackSection() {
    val prefs = remember { DesktopPreferences.open("kloudstream") }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("player_fullscreen", true)) }
    var version by remember { mutableStateOf(prefs.getString(PlaybackController.VERSION_KEY, null) ?: "Auto") }
    var scale by remember { mutableStateOf(prefs.getString(PlaybackController.SUBTITLE_SCALE_KEY, null) ?: "1.0") }
    var next by remember { mutableStateOf(prefs.getBoolean(PlaybackController.AUTOPLAY_NEXT_KEY, true)) }
    Section("Lecture") {
        Toggle("Lancer la lecture en plein écran", "Échap ou F pour quitter le plein écran pendant la lecture.", fullscreen) { value ->
            fullscreen = value
            prefs.edit().putBoolean("player_fullscreen", value).apply()
            AppModel.player.setFullscreen(value)
        }
        Toggle("Enchaîner l'épisode suivant", "À la fin d'un épisode, le suivant démarre dans la même fenêtre.", next) { value ->
            next = value
            prefs.edit().putBoolean(PlaybackController.AUTOPLAY_NEXT_KEY, value).apply()
        }
        Choice("Version préférée", "Auto choisit la VF quand elle existe, sinon la VOST.",
            listOf("Auto" to "Auto", SourceVersion.VF.name to "VF", SourceVersion.VOST.name to "VOSTFR", SourceVersion.VO.name to "VO"), version) {
            version = it
            prefs.edit().putString(PlaybackController.VERSION_KEY, it).apply()
        }
        Choice("Taille des sous-titres", null, listOf("0.8" to "Petite", "1.0" to "Normale", "1.3" to "Grande", "1.6" to "Très grande"), scale) {
            scale = it
            prefs.edit().putString(PlaybackController.SUBTITLE_SCALE_KEY, it).apply()
        }
        Text("Lecteur : " + (Mpv.executable()?.path ?: "mpv introuvable"), color = Kloud.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun DownloadsSection() {
    var folder by remember { mutableStateOf(Downloads.folder.path) }
    Section("Téléchargements") {
        Text("Dossier : $folder", color = Kloud.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SecondaryButton("Changer de dossier", KloudIcons.Folder) {
                val chooser = javax.swing.JFileChooser(Downloads.folder).apply {
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Dossier des téléchargements"
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    Downloads.folder = chooser.selectedFile
                    folder = chooser.selectedFile.path
                }
            }
            SecondaryButton("Ouvrir le dossier") { runCatching { Downloads.folder.mkdirs(); java.awt.Desktop.getDesktop().open(Downloads.folder) } }
        }
        Text("Les téléchargements en cours reprennent au prochain démarrage. Les fichiers déjà téléchargés restent dans l'ancien dossier.",
            color = Kloud.Faint, fontSize = 13.sp)
    }
}

@Composable
private fun DataSection() {
    var cleared by remember { mutableStateOf<String?>(null) }
    var confirmHistory by remember { mutableStateOf(false) }
    Section("Données") {
        Text("Historique, sources et journaux : " + AppDirs.root.path, color = Kloud.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Vider le cache des images", Icons.Filled.Delete) {
                SingletonImageLoader.get(PlatformContext.INSTANCE).let { loader -> loader.memoryCache?.clear(); loader.diskCache?.clear() }
                cleared = "Cache des images vidé."
            }
            SecondaryButton(if (confirmHistory) "Confirmer : effacer l'historique" else "Effacer l'historique", Icons.Filled.Delete) {
                if (confirmHistory) { WatchHistory.clear(); confirmHistory = false; cleared = "Historique effacé." } else confirmHistory = true
            }
            SecondaryButton("Ouvrir le dossier") { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.root) } }
        }
        cleared?.let { Text(it, color = Kloud.Muted) }
    }
}

@Composable
private fun AboutSection() {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updates.Release?>(null) }
    Section("À propos") {
        Text("KloudStream ${Updates.current} — basé sur CloudStream (GPL-3.0). Lecteur mpv, interface uosc (LGPL-2.1).", color = Kloud.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Rechercher une mise à jour", Icons.Filled.Refresh) {
                scope.launch {
                    result = "Recherche…"
                    val latest = Updates.latest()
                    release = latest?.takeIf { Updates.isNewer(it.version) }
                    result = when { latest == null -> "Impossible de joindre GitHub."; release != null -> "La version ${latest.version} est disponible."
                        else -> "KloudStream est à jour (${latest.version})." }
                }
            }
            release?.let { found ->
                PrimaryButton("Télécharger ${found.version}", KloudIcons.Download) {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(found.installer ?: found.page)) }
                }
            }
            result?.let { Text(it, color = Kloud.Muted) }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            detail?.let { Text(it, color = Kloud.Muted, fontSize = 13.sp) }
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Kloud.Accent))
    }
}

@Composable
private fun Choice(title: String, detail: String?, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            detail?.let { Text(it, color = Kloud.Muted, fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Kloud.SurfaceHigh)
                        .clickable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        content()
        Spacer(Modifier.height(4.dp))
    }
}
