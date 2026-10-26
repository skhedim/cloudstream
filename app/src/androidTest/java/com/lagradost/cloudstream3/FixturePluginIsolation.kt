package com.lagradost.cloudstream3

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import org.json.JSONArray
import org.json.JSONObject

/** Keep unrelated extension constructors and update jobs out of in-process UI fixtures. */
internal class FixturePluginIsolation(context: Context) : AutoCloseable {
    private val data = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val savedOnline = data.getString(PLUGINS_KEY, null)
    private val savedLocal = data.getString(PLUGINS_KEY_LOCAL, null)
    val online: List<PluginData> = JSONArray(savedOnline ?: "[]").let { list -> (0 until list.length()).map { index ->
        list.getJSONObject(index).let { p -> PluginData(p.getString("internalName"),
            if (p.isNull("url")) null else p.getString("url"), p.getBoolean("isOnline"), p.getString("filePath"), p.getInt("version")) }
    } }
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val updateKey = context.getString(R.string.auto_update_plugins_key)
    private val update = prefs.getBoolean(updateKey, true)
    private val hadUpdate = prefs.contains(updateKey)
    init {
        // An inert record avoids the native empty-installation wizard without executing code.
        val placeholder = JSONObject().put("internalName", "Fixture placeholder").put("url", JSONObject.NULL)
            .put("isOnline", true).put("filePath", java.io.File(context.cacheDir, "fixture-unavailable.cs3").absolutePath).put("version", 0)
        check(data.edit().putString(PLUGINS_KEY, JSONArray().put(placeholder).toString()).putString(PLUGINS_KEY_LOCAL, "[]").commit())
        check(prefs.edit().putBoolean(updateKey, false).commit())
    }
    override fun close() {
        check(data.edit().apply {
            if (savedOnline == null) remove(PLUGINS_KEY) else putString(PLUGINS_KEY, savedOnline)
            if (savedLocal == null) remove(PLUGINS_KEY_LOCAL) else putString(PLUGINS_KEY_LOCAL, savedLocal)
        }.commit())
        check(prefs.edit().apply { if (hadUpdate) putBoolean(updateKey, update) else remove(updateKey) }.commit())
    }
}
