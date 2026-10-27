package com.kloudstream.desktop.extensions

import com.googlecode.d2j.dex.Dex2jar
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Translates an extension's Android DEX code into JVM classes once, cached by the archive's hash.
 * Kotlin's mangled names (`constructor-impl`, `getX-abc123`) are valid on the JVM and must survive
 * the translation, hence `dontSanitizeNames`.
 */
object DexTranslator {
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    /** Returns a directory of `.class` files and the archive's other entries (manifest, assets). */
    fun translate(archive: File, outputRoot: File): File {
        val hash = sha256(archive)
        val dir = File(outputRoot, archive.nameWithoutExtension + "-" + hash.take(16))
        val marker = File(dir, ".complete")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            val dexes = entries.filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            require(dexes.isNotEmpty()) { "${archive.name} contains no classes.dex" }
            for (entry in dexes) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                Dex2jar.from(bytes)
                    .reUseReg(false)
                    .topoLogicalSort()
                    .skipDebug(false)
                    .optimizeSynchronized(true)
                    .skipExceptions(false)
                    .dontSanitizeNames(true)
                    .to(dir.toPath())
            }
            entries.filter { !it.isDirectory && !it.name.endsWith(".dex") }.forEach { entry ->
                val out = File(dir, entry.name)
                require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Bad entry ${entry.name}" }
                out.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        removeOlder(archive, outputRoot, dir)
        marker.writeText(hash)
        return dir
    }

    /** Older translations of [archive] are no longer needed; `frembed-…` is not `frembed-fr-…`. */
    fun removeOlder(archive: File, outputRoot: File, current: File) {
        val older = Regex(Regex.escape(archive.nameWithoutExtension) + "-[0-9a-f]{16}")
        outputRoot.listFiles { f -> f.isDirectory && f != current && older.matches(f.name) }?.forEach { it.deleteRecursively() }
    }
}
