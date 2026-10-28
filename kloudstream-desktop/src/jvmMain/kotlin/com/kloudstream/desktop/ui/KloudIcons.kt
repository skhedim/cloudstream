package com.kloudstream.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons missing from Material's core set (paths from Material Symbols, 24 × 24). */
object KloudIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Download: ImageVector by lazy { icon("Download", "M5,20h14v-2H5V20z M19,9h-4V3H9v6H5l7,7L19,9z") }
    val DownloadDone: ImageVector by lazy { icon("DownloadDone", "M5,18h14v2H5V18z M9.6,15.4L4.6,10.4l1.4-1.4l3.6,3.6l8.4-8.4l1.4,1.4L9.6,15.4z") }
    val Trailer: ImageVector by lazy { icon("Trailer", "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4z M9.5,16.5v-9l7,4.5L9.5,16.5z") }
    val Folder: ImageVector by lazy { icon("Folder", "M10,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8L10,4z") }
}
