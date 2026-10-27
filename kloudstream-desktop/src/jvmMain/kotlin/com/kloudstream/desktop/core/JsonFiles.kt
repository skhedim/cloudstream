package com.kloudstream.desktop.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.mapper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A JSON object kept in memory and written to disk shortly after each change (atomic replace),
 * so frequent small updates (playback progress, provider health) cost one write per second at most.
 */
class JsonFile(private val file: File) {
    private val lock = Any()
    private var root: ObjectNode = load()
    private var pending: ScheduledFuture<*>? = null

    private fun load(): ObjectNode = runCatching {
        if (file.exists()) mapper.readTree(file) as? ObjectNode else null
    }.getOrNull() ?: mapper.createObjectNode()

    fun get(key: String): JsonNode? = synchronized(lock) { root.get(key)?.deepCopy() }
    fun keys(): List<String> = synchronized(lock) { root.fieldNames().asSequence().toList() }

    fun set(key: String, value: JsonNode?) {
        synchronized(lock) {
            if (value == null || value.isNull) root.remove(key) else root.set<JsonNode>(key, value)
            schedule()
        }
    }

    private fun schedule() {
        if (pending?.isDone == false) return
        pending = writer.schedule({ flush() }, 1, TimeUnit.SECONDS)
    }

    fun flush() {
        val text = synchronized(lock) { mapper.writeValueAsString(root) }
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { AppLog.warn("Could not save ${file.name}", it) }
    }

    companion object {
        private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "json-writer").apply { isDaemon = true } }
        private val open = mutableListOf<JsonFile>()

        fun open(file: File): JsonFile = synchronized(open) { JsonFile(file).also { open += it } }

        /** Called on exit so the last changes are not lost. */
        fun flushAll() = synchronized(open) { open.forEach { it.flush() } }
    }
}
