package com.kloudstream.desktop.platform

import android.content.InMemorySharedPreferences
import android.content.SharedPreferences
import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.JsonFile
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream4.aggregation.KeyValueStore
import java.io.File

/** The shared engine's persistent store (canonical contexts, provider health), in one JSON file. */
class JsonKeyValueStore(file: File) : KeyValueStore {
    private val json = JsonFile.open(file)
    override fun <T : Any> get(key: String, type: Class<T>): T? =
        json.get(key)?.let { runCatching { mapper.treeToValue(it, type) }.getOrNull() }
    override fun set(key: String, value: Any) = json.set(key, mapper.valueToTree(value))
}

/** Extension preferences (Android SharedPreferences), one JSON file per preference name. */
object DesktopPreferences {
    private val opened = mutableMapOf<String, SharedPreferences>()

    fun open(name: String): SharedPreferences = synchronized(opened) {
        opened.getOrPut(name) {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val json = JsonFile.open(File(File(AppDirs.data, "preferences"), "$safe.json"))
            val initial = json.keys().associateWith { key -> json.get(key)?.let { mapper.treeToValue(it, Any::class.java) } }
            InMemorySharedPreferences(initial) { values ->
                val current = json.keys().toSet()
                values.forEach { (key, value) -> json.set(key, mapper.valueToTree(value)) }
                (current - values.keys).forEach { json.set(it, null) }
            }
        }
    }
}
