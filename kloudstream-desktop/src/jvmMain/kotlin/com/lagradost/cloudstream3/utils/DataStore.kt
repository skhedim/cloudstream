package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.mapper

const val PREFERENCES_NAME = "rebuild_preference"

/** The Android app's key/value helpers, on the desktop preferences (JSON values, like Android). */
object DataStore {
    private fun prefs(): SharedPreferences = android.content.SharedPreferencesProvider.open(PREFERENCES_NAME)

    fun Context.getSharedPrefs(): SharedPreferences = prefs()
    fun Context.getDefaultSharedPrefs(): SharedPreferences = android.content.SharedPreferencesProvider.open("default")

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.getKeys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return prefs().getAll().keys.filter { it.startsWith(prefix) }
    }

    fun Context.containsKey(path: String): Boolean = prefs().contains(path)
    fun Context.removeKey(path: String) = DataStore.removeKey(path)
    fun <T> Context.setKey(path: String, value: T) = DataStore.setKey(path, value)
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? = DataStore.getKey(path, valueType)

    fun <T> setKey(path: String, value: T) {
        prefs().edit().putString(path, mapper.writeValueAsString(value)).apply()
    }

    fun <T : Any> getKey(path: String, valueType: Class<T>): T? = try {
        prefs().getString(path, null)?.let { mapper.readValue(it, valueType) }
    } catch (_: Exception) {
        null
    }

    fun removeKey(path: String) {
        prefs().edit().remove(path).apply()
    }
}
