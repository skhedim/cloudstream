package android.content

import android.content.res.Resources

/**
 * Desktop stand-in for Android's Context: extensions receive one in `Plugin.load(context)`.
 * Preferences are persisted by the desktop app ([SharedPreferencesProvider]); views and
 * dialogs are not available, so extension settings screens are not offered on desktop.
 */
open class Context {
    open fun getPackageName(): String = "com.lagradost.cloudstream3"
    open fun getResources(): Resources = Resources.system
    open fun getApplicationContext(): Context = this
    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        SharedPreferencesProvider.open(name ?: "default")
    open fun getString(id: Int): String = ""

    companion object {
        const val MODE_PRIVATE = 0
    }
}

/** Where desktop preferences live; installed by the app before any extension loads. */
object SharedPreferencesProvider {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
    /** One instance per name, like Android; in memory until the app installs persistent ones. */
    @Volatile var open: (String) -> SharedPreferences = { name -> memory.getOrPut(name) { InMemorySharedPreferences() } }
}
