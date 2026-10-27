package android.content.res

import android.util.DisplayMetrics
import java.util.Locale

/** Extensions on desktop never ship Android resources: lookups find nothing. */
open class Resources {
    private val configuration = Configuration()
    private val metrics = DisplayMetrics()
    open fun getConfiguration(): Configuration = configuration
    open fun getDisplayMetrics(): DisplayMetrics = metrics
    open fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
    open fun getString(id: Int): String = throw NotFoundException("No resources on desktop")
    open fun getLayout(id: Int): XmlResourceParser = throw NotFoundException("No layouts on desktop")

    class NotFoundException(message: String?) : RuntimeException(message)

    companion object {
        @JvmStatic val system = Resources()
    }
}

open class Configuration {
    @JvmField var locale: Locale = Locale.getDefault()
    open fun getLocales(): android.os.LocaleList = android.os.LocaleList(locale)
}

open class ColorStateList(states: Array<IntArray>?, colors: IntArray?)

interface XmlResourceParser : org.xmlpull.v1.XmlPullParser, AutoCloseable
