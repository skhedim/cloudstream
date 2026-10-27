package com.kloudstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.kloudstream.desktop.core.AppDirs
import okio.Path.Companion.toOkioPath
import java.io.File

/** Posters and backdrops: memory cache plus 400 MB on disk, so the home reopens instantly. */
@Composable
fun InstallImageLoader() {
    setSingletonImageLoaderFactory { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(AppDirs.cache, "images").toOkioPath()).maxSizeBytes(400L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, placeholder: Color = Kloud.SurfaceHigh) {
    Box(modifier.background(placeholder)) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
    }
}
