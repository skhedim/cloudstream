package com.kloudstream.desktop.extensions

import com.kloudstream.desktop.core.AppLog
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What extensions try to put in front of the viewer (donation requests, notices, toasts). The
 * desktop never shows them: they are logged, and the provider survey counts them per extension.
 */
object ExtensionPrompts {
    /** [origin] is the extension's translated class directory, e.g. `streamplay-1a2b3c4d5e6f7a8b`. */
    data class Prompt(val kind: String, val text: String, val origin: String?)

    private val recorded = ConcurrentLinkedQueue<Prompt>()
    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val appLoader = ExtensionPrompts::class.java.classLoader

    fun record(kind: String, text: CharSequence?) {
        val prompt = Prompt(kind, text?.toString().orEmpty().replace('\n', ' ').take(240), origin())
        recorded += prompt
        AppLog.warn("Extension ${prompt.origin ?: "?"} tried to show a $kind: ${prompt.text}")
    }

    /** Everything recorded since the last call. */
    fun drain(): List<Prompt> = generateSequence { recorded.poll() }.toList()

    /** The first caller loaded by an extension's class loader (a child of the app's). */
    private fun origin(): String? = walker.walk { frames ->
        frames.map { it.declaringClass.classLoader }
            .filter { it is URLClassLoader && it !== appLoader && it.parent === appLoader }
            .findFirst().orElse(null)
    }?.let { loader -> (loader as URLClassLoader).urLs.firstOrNull()?.path?.trimEnd('/')?.substringAfterLast('/') }
}
