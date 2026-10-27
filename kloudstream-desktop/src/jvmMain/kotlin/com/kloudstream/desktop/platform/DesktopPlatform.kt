package com.kloudstream.desktop.platform

import android.content.SharedPreferencesProvider
import com.kloudstream.desktop.core.AppDirs
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.ImageIoArtworkSampler
import java.io.File
import java.util.Locale

/** Wires the shared aggregation engine and the extension runtime to desktop storage. */
object DesktopPlatform {
    fun install() {
        SharedPreferencesProvider.open = DesktopPreferences::open
        AggregationPlatform.store = JsonKeyValueStore(File(AppDirs.data, "aggregation.json"))
        AggregationPlatform.artworkSampler = ImageIoArtworkSampler
        // French catalogues only for now: metadata, ranking and audio preference follow French.
        AggregationPlatform.metadataLanguage = { Locale.FRANCE.toLanguageTag() }
        // Some streaming hosts omit intermediate certificates; Android fetches them, so must the JVM.
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true")
    }
}
