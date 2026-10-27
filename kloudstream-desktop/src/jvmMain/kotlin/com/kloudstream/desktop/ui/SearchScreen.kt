package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.AppModel
import com.kloudstream.desktop.app.MediaCard

@Composable
fun SearchScreen(onOpen: (MediaCard) -> Unit) {
    val state by AppModel.search.state.collectAsState()
    var text by remember { mutableStateOf(AppModel.search.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val cards = state.cards.map { MediaCard(it.media, it.responses) }
    Column(Modifier.fillMaxSize().background(Kloud.Background).padding(top = 32.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; AppModel.search.type(it) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Kloud.Muted) },
            placeholder = { Text("Titres, films, séries…", color = Kloud.Faint, fontSize = 18.sp) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Kloud.Faint,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                focusedContainerColor = Kloud.Surface, unfocusedContainerColor = Kloud.Surface),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
            modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().widthIn(max = 900.dp).focusRequester(focus),
        )
        Box(Modifier.fillMaxSize()) {
            when {
                text.trim().length < 2 -> Text("Cherchez un film ou une série : chaque titre n'apparaît qu'une fois, quelle que soit la source.",
                    color = Kloud.Muted, modifier = Modifier.padding(48.dp))
                cards.isEmpty() && (state.loading || !state.published) -> Box(Modifier.padding(48.dp)) { LoadingLabel("Recherche…") }
                cards.isEmpty() -> Text("Aucun résultat pour « ${text.trim()} ».", color = Kloud.Muted, modifier = Modifier.padding(48.dp))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterWidth + 16.dp),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(cards, key = { it.media.id }) { card -> PosterCard(card, { onOpen(card) }) }
                    if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { LoadingLabel("Autres sources en cours…") } }
                }
            }
        }
    }
}
