package android.os

class Build {
    class VERSION {
        companion object {
            /** Android 11: the newest APIs extensions commonly branch on are available. */
            @JvmField val SDK_INT: Int = 30
            @JvmField val RELEASE: String = "11"
        }
    }

    class VERSION_CODES {
        companion object {
            const val N = 24
            const val O = 26
            const val P = 28
            const val Q = 29
            const val R = 30
            const val S = 31
            const val TIRAMISU = 33
        }
    }

    companion object {
        @JvmField val MANUFACTURER: String = "KloudStream"
        @JvmField val MODEL: String = "Desktop"
    }
}

class LocaleList(private vararg val locales: java.util.Locale) {
    fun get(index: Int): java.util.Locale = locales[index]
    fun size(): Int = locales.size
}
