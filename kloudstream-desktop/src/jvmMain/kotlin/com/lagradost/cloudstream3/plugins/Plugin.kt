package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Desktop counterpart of the Android app's Plugin: most extensions extend it and override
 * `load(context)`, so it keeps the same JVM signatures. Settings screens are not shown on desktop.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /** Extensions that need Android resources cannot use them on desktop. */
    var resources: Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null
}
