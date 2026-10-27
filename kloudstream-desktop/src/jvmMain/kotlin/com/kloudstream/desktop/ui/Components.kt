package com.kloudstream.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kloudstream.desktop.app.MediaCard

val PosterWidth = 168.dp
val PosterHeight = 252.dp

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(start = 48.dp, bottom = 10.dp), style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, color = Color.White)
}

/** A poster that grows slightly on hover or focus; title and year below. */
@Composable
fun PosterCard(card: MediaCard, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = PosterWidth, progress: Float? = null, subtitle: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (hovered || focused) 1.05f else 1f)
    Column(modifier.width(width)) {
        Box(Modifier.scale(scale).clip(RoundedCornerShape(8.dp))
            .border(if (hovered || focused) BorderStroke(2.dp, Color.White) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(8.dp))
            .hoverable(interaction).clickable(interaction, null, onClick = onClick)) {
            NetImage(card.poster, Modifier.width(width).height(width * 1.5f))
            card.score?.takeIf { it > 0 }?.let { score ->
                Text("★ " + "%.1f".format(score), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Kloud.Rating, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, color = Kloud.Accent, trackColor = Color(0x66FFFFFF),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val meta = subtitle ?: card.year?.toString()
        if (meta != null) Text(meta, color = Kloud.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CardRow(title: String, cards: List<MediaCard>, onOpen: (MediaCard) -> Unit, ranked: Boolean = false) {
    if (cards.isEmpty()) return
    Column(Modifier.padding(bottom = 28.dp)) {
        SectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(if (ranked) 4.dp else 14.dp)) {
            // Positional items: rows refresh while loading and must stay at their start.
            itemsIndexed(cards.take(if (ranked) 10 else 60)) { index, card ->
                if (ranked) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${index + 1}", fontSize = 120.sp, fontWeight = FontWeight.Black, color = Color(0xFF3A3A3A),
                            modifier = Modifier.padding(end = 2.dp), lineHeight = 120.sp)
                        PosterCard(card, { onOpen(card) }, width = 150.dp)
                    }
                } else PosterCard(card, { onOpen(card) })
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = Icons.Filled.PlayArrow, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(26.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0x996D6D6E), contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
