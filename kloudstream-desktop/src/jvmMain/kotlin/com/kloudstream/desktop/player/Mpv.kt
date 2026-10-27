package com.kloudstream.desktop.player

import com.fasterxml.jackson.databind.JsonNode
import com.kloudstream.desktop.core.AppDirs
import com.kloudstream.desktop.core.AppLog
import com.lagradost.cloudstream3.mapper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * A running mpv window driven through its JSON IPC (a named pipe on Windows, a unix socket
 * elsewhere). One connection only reads events; each command uses its own short connection,
 * because a synchronous Windows pipe cannot read and write at the same time.
 */
class Mpv private constructor(private val process: Process, private val ipcPath: String) {
    val isAlive: Boolean get() = process.isAlive

    private class Connection(val input: InputStream, val output: OutputStream, val closer: AutoCloseable)

    private fun connect(): Connection = if (AppDirs.isWindows) {
        val pipe = RandomAccessFile(ipcPath, "rw")
        Connection(Channels.newInputStream(pipe.channel), Channels.newOutputStream(pipe.channel), pipe)
    } else {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(ipcPath))
        Connection(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel)
    }

    /** mpv creates its IPC endpoint a moment after starting. */
    private fun connectWhenReady(timeoutMs: Long = 20_000): Connection? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            try { return connect() } catch (_: Exception) { Thread.sleep(150) }
        }
        return null
    }

    private fun line(args: List<Any?>): ByteArray {
        val node = mapper.createObjectNode()
        node.set<JsonNode>("command", mapper.valueToTree(args))
        return (mapper.writeValueAsString(node) + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Sends commands in order on one short-lived connection; false if mpv is gone. */
    fun commands(vararg commands: List<Any?>): Boolean {
        val connection = connectWhenReady(5_000) ?: return false
        return try {
            commands.forEach { connection.output.write(line(it)) }
            connection.output.flush()
            true
        } catch (e: Exception) {
            AppLog.warn("mpv command failed: ${commands.joinToString { it.firstOrNull().toString() }}", e)
            false
        } finally {
            // Give mpv time to read before the pipe closes (Windows drops unread data on close).
            Thread.sleep(50)
            runCatching { connection.closer.close() }
        }
    }

    fun command(vararg args: Any?): Boolean = commands(args.toList())

    /**
     * Blocks until mpv exits: [onProperty] receives changes of [properties], [onEvent] every
     * other event (end-file, file-loaded, playback-restart…).
     */
    fun observe(properties: List<String>, onProperty: (String, JsonNode?) -> Unit, onEvent: (String, JsonNode) -> Unit) {
        val connection = connectWhenReady() ?: run { AppLog.warn("mpv IPC unavailable"); return }
        try {
            properties.forEachIndexed { index, name -> connection.output.write(line(listOf("observe_property", index + 1, name))) }
            connection.output.flush()
            connection.input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (text in lines) {
                    val node = runCatching { mapper.readTree(text) }.getOrNull() ?: continue
                    val event = node.path("event").asText()
                    if (event.isEmpty()) continue
                    runCatching {
                        if (event == "property-change") onProperty(node.path("name").asText(), node.get("data"))
                        else onEvent(event, node)
                    }.onFailure { AppLog.warn("mpv event $event", it) }
                }
            }
        } catch (_: Exception) {
            // mpv closed the connection when quitting.
        } finally {
            runCatching { connection.closer.close() }
            if (!AppDirs.isWindows) File(ipcPath).delete()
        }
    }

    fun quit() {
        if (!command("quit")) process.destroy()
    }

    companion object {
        /** Bundled next to the app (Windows), then `kloudstream.mpv`, then the PATH. */
        fun executable(): File? {
            System.getProperty("kloudstream.mpv")?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
            val names = if (AppDirs.isWindows) listOf("mpv.exe", "mpv.com") else listOf("mpv")
            val appDir = runCatching { File(Mpv::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
            listOfNotNull(appDir?.let { File(it, "mpv") }, System.getProperty("jpackage.app-path")?.let { File(File(it).parentFile, "app/mpv") })
                .flatMap { dir -> names.map { File(dir, it) } }
                .firstOrNull { it.isFile }?.let { return it }
            return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
                .flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile && it.canExecute() }
        }

        fun start(title: String, fullscreen: Boolean, extra: List<String> = emptyList()): Mpv {
            val exe = executable() ?: throw IllegalStateException("mpv introuvable")
            val id = UUID.randomUUID().toString().take(8)
            val ipc = if (AppDirs.isWindows) "\\\\.\\pipe\\kloudstream-mpv-$id"
                else File(System.getProperty("java.io.tmpdir"), "kloudstream-mpv-$id.sock").path
            val args = listOf(
                exe.path,
                "--input-ipc-server=$ipc",
                "--idle=yes", "--force-window=immediate", "--keep-open=no",
                "--fullscreen=" + (if (fullscreen) "yes" else "no"),
                "--title=$title",
                "--alang=fr,fre,fra,french", "--slang=fr,fre,fra,french", "--sub-auto=fuzzy",
                "--hwdec=auto-safe",
                "--ytdl=no",
                "--cache=yes", "--demuxer-max-bytes=200MiB", "--demuxer-max-back-bytes=50MiB",
                "--demuxer-readahead-secs=120", "--network-timeout=30",
                "--input-default-bindings=yes", "--input-vo-keyboard=yes",
                "--osd-duration=2500",
                "--msg-level=all=warn", "--log-file=" + File(AppDirs.logs, "mpv.log").path,
            ) + extra + System.getProperty("kloudstream.mpvArgs").orEmpty().split(' ').filter { it.isNotBlank() }
            AppLog.info("Starting ${exe.path}")
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            // Drain the output so mpv never blocks on a full pipe.
            Thread({ runCatching { process.inputStream.copyTo(OutputStream.nullOutputStream()) } }, "mpv-output").apply { isDaemon = true }.start()
            return Mpv(process, ipc)
        }
    }
}
