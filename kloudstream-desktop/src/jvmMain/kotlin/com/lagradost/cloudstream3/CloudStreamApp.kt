package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore

/** The parts of the Android Application object that extensions call. */
class CloudStreamApp {
    companion object {
        @Volatile var context: Context? = null

        fun <T> setKey(path: String, value: T) = DataStore.setKey(path, value)
        fun <T> setKey(folder: String, path: String, value: T) = DataStore.setKey("$folder/$path", value)
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = DataStore.getKey(path, valueType)
        fun <T : Any> setKeyClass(path: String, value: T) = DataStore.setKey(path, value)
        fun removeKey(path: String) = DataStore.removeKey(path)
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = getKeyClass(path, T::class.java) ?: defVal
        inline fun <reified T : Any> getKey(path: String): T? = getKeyClass(path, T::class.java)
    }
}
