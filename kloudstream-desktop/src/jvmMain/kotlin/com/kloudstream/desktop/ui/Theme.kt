package com.kloudstream.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode

/** Pure black, white actions and a red accent for progress and rankings. */
object Kloud {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val SurfaceHigh = Color(0xFF232323)
    val Accent = Color(0xFFE50914)
    val Muted = Color(0xFFB3B3B3)
    val Faint = Color(0xFF6E6E6E)
    val Rating = Color(0xFFF5C518)
}

@Composable
fun KloudTheme(content: @Composable () -> Unit) {
    CloudStreamTheme(mode = CloudStreamThemeMode.Amoled, primaryColor = CloudStreamPrimaryColor.WHITE, content = content)
}
