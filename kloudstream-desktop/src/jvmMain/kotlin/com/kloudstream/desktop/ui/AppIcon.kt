package com.kloudstream.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/** The KloudStream mark: a white K on a red rounded square (window and taskbar icon). */
@Composable
fun appIcon(): Painter = remember { KloudIconPainter() }

class KloudIconPainter : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() {
        val s = size.minDimension
        drawRoundRect(Color(0xFFE50914), size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f))
        val path = Path().apply {
            // Stem, then both arms starting inside the stem and overlapping at the junction.
            moveTo(s * 0.26f, s * 0.2f); lineTo(s * 0.42f, s * 0.2f); lineTo(s * 0.42f, s * 0.8f); lineTo(s * 0.26f, s * 0.8f); close()
            moveTo(s * 0.36f, s * 0.56f); lineTo(s * 0.62f, s * 0.2f); lineTo(s * 0.80f, s * 0.2f); lineTo(s * 0.52f, s * 0.56f); close()
            moveTo(s * 0.42f, s * 0.45f); lineTo(s * 0.80f, s * 0.8f); lineTo(s * 0.61f, s * 0.8f); lineTo(s * 0.36f, s * 0.56f); close()
        }
        drawPath(path, Color.White)
    }
}
