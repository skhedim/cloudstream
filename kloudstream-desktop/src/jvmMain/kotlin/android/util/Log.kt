package android.util

import com.kloudstream.desktop.core.AppLog

/** Extension logs go to the desktop log file. */
class Log {
    companion object {
        @JvmStatic fun v(tag: String?, msg: String?): Int = write("V", tag, msg, null)
        @JvmStatic fun d(tag: String?, msg: String?): Int = write("D", tag, msg, null)
        @JvmStatic fun i(tag: String?, msg: String?): Int = write("I", tag, msg, null)
        @JvmStatic fun w(tag: String?, msg: String?): Int = write("W", tag, msg, null)
        @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write("W", tag, msg, tr)
        @JvmStatic fun e(tag: String?, msg: String?): Int = write("E", tag, msg, null)
        @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write("E", tag, msg, tr)
        @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

        private fun write(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
            AppLog.extension(level, tag ?: "", msg ?: "", tr)
            return 0
        }
    }
}

class DisplayMetrics {
    @JvmField var density: Float = 1f
    @JvmField var densityDpi: Int = 160
    @JvmField var widthPixels: Int = 1920
    @JvmField var heightPixels: Int = 1080
    @JvmField var scaledDensity: Float = 1f
}
