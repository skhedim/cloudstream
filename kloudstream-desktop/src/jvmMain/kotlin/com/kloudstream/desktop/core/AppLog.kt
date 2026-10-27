package com.kloudstream.desktop.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Plain text log in [AppDirs.logs] (rotated at 5 MB), mirrored to stderr. */
object AppLog {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val file: File by lazy { File(AppDirs.logs, "kloudstream.log") }
    private const val MAX_BYTES = 5L * 1024 * 1024

    fun info(message: String) = write("I", "app", message, null)
    fun warn(message: String, error: Throwable? = null) = write("W", "app", message, error)
    fun error(message: String, error: Throwable? = null) = write("E", "app", message, error)
    fun extension(level: String, tag: String, message: String, error: Throwable?) {
        // Extension debug output is very chatty; keep it out of the log unless it reports a problem.
        if (level == "V" || level == "D") return
        write(level, tag, message, error)
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append(" [").append(tag).append("] ").append(message)
            if (error != null) append('\n').append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
        }
        System.err.println(line)
        runCatching {
            if (file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, "kloudstream.1.log"))
            file.appendText(line + "\n")
        }
    }
}
