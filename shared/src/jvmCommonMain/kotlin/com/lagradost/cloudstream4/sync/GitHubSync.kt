package com.lagradost.cloudstream4.sync

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.KeyValueStore
import com.lagradost.cloudstream4.aggregation.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.io.encoding.Base64

/** A device's access to the repository the KloudStream Sync GitHub App was installed on. */
data class SyncAccount(
    val token: String,
    val repository: String,
    val login: String,
    /** Only when the app's user tokens expire (8 hours); see [GitHubLogin.refresh]. */
    val refreshToken: String? = null,
)

enum class SyncProblem {
    /** The access was revoked or expired: connect the device again. */
    Unauthorized,
    /** KloudStream Sync is installed on no repository of the account. */
    NoRepository,
    /** The code was refused or expired before being entered. */
    LoginDenied,
    Network,
}

class SyncException(val problem: SyncProblem, message: String? = null) : IOException(message ?: problem.name)

/**
 * GitHub's device flow, made for TVs: the device shows a code, the user enters it at
 * github.com/login/device on a phone or computer. It needs no secret, so the app can be public.
 */
object GitHubLogin {
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String,
        val intervalSeconds: Int, val expiresAt: Long)

    private val json = mapOf("Accept" to "application/json")

    suspend fun start(clientId: String): DeviceCode {
        val node = post("https://github.com/login/device/code", mapOf("client_id" to clientId))
        val code = node.path("device_code").asText("").ifEmpty { throw SyncException(SyncProblem.Network, node.toString()) }
        return DeviceCode(code, node.path("user_code").asText(), node.path("verification_uri").asText("https://github.com/login/device"),
            node.path("interval").asInt(5), System.currentTimeMillis() + node.path("expires_in").asLong(900) * 1000)
    }

    /** Waits for the user to enter [code], then finds the repository the app may write to. */
    suspend fun await(clientId: String, code: DeviceCode): SyncAccount {
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval * 1000L)
            val node = post("https://github.com/login/oauth/access_token", mapOf("client_id" to clientId,
                "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            val token = node.path("access_token").asText("")
            if (token.isNotEmpty()) return account(token, node.path("refresh_token").asText("").ifEmpty { null })
            when (node.path("error").asText()) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = node.path("interval").asInt(interval + 5)
                else -> throw SyncException(SyncProblem.LoginDenied, node.path("error").asText())
            }
        }
        throw SyncException(SyncProblem.LoginDenied, "expired_token")
    }

    /** A new token for an app whose user tokens expire; null when GitHub refuses it. */
    suspend fun refresh(clientId: String, account: SyncAccount): SyncAccount? {
        val refreshToken = account.refreshToken ?: return null
        val node = runCatching {
            post("https://github.com/login/oauth/access_token", mapOf("client_id" to clientId,
                "grant_type" to "refresh_token", "refresh_token" to refreshToken))
        }.getOrNull() ?: return null
        val token = node.path("access_token").asText("").ifEmpty { return null }
        return account.copy(token = token, refreshToken = node.path("refresh_token").asText("").ifEmpty { refreshToken })
    }

    private suspend fun account(token: String, refreshToken: String?): SyncAccount {
        val api = GitHubApi(token)
        val repositories = api.get("/user/installations").path("installations").flatMap { installation ->
            api.get("/user/installations/${installation.path("id").asLong()}/repositories").path("repositories")
                .map { it.path("full_name").asText() }
        }
        val repository = repositories.firstOrNull { it.substringAfter('/') == "kloudstream-sync" } ?: repositories.firstOrNull()
            ?: throw SyncException(SyncProblem.NoRepository)
        return SyncAccount(token, repository, api.get("/user").path("login").asText(), refreshToken)
    }

    private suspend fun post(url: String, form: Map<String, String>): JsonNode = try {
        mapper.readTree(app.post(url, headers = json, data = form, timeout = 20L).text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(SyncProblem.Network, e.message)
    }
}

/** The few REST calls the sync makes, with the device's token. */
class GitHubApi(private val token: String) {
    data class Response(val code: Int, val body: JsonNode)

    private fun headers() = mapOf("Accept" to "application/vnd.github+json", "Authorization" to "Bearer $token",
        "X-GitHub-Api-Version" to "2022-11-28", "Cache-Control" to "no-cache")

    suspend fun call(method: String, path: String, body: Map<String, String>? = null): Response = try {
        val url = "https://api.github.com$path"
        val response = when (method) {
            "PUT" -> app.put(url, headers = headers(), json = body?.let(mapper::writeValueAsString), timeout = 30L)
            else -> app.get(url, headers = headers(), cacheTime = 0, timeout = 30L)
        }
        if (response.code == 401) throw SyncException(SyncProblem.Unauthorized)
        Response(response.code, runCatching { mapper.readTree(response.text) }.getOrNull() ?: mapper.createObjectNode())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(SyncProblem.Network, e.message)
    }

    suspend fun get(path: String): JsonNode = call("GET", path).also {
        if (it.code !in 200..299) throw SyncException(SyncProblem.Network, "GitHub ${it.code} for $path")
    }.body
}

/** The shared history as `history.json` in the account's sync repository; each write is a commit. */
class GitHubHistoryRemote(private val account: SyncAccount, private val device: String) : SyncRemote {
    private val api = GitHubApi(account.token)
    private val path = "/repos/${account.repository}/contents/$FILE"

    override suspend fun read(): Pair<SyncedHistory, String?> {
        val response = api.call("GET", path)
        if (response.code == 404) return SyncedHistory() to null
        if (response.code !in 200..299) throw SyncException(SyncProblem.Network, "GitHub ${response.code}")
        val content = response.body.path("content").asText("").filterNot { it.isWhitespace() }
        val history = if (content.isEmpty()) SyncedHistory()
            else mapper.readValue(Base64.decode(content).decodeToString(), SyncedHistory::class.java)
        return history to response.body.path("sha").asText("").ifEmpty { null }
    }

    override suspend fun write(history: SyncedHistory, version: String?): Boolean {
        val content = Base64.encode(mapper.writeValueAsString(history).encodeToByteArray())
        val body = buildMap {
            put("message", "History from $device")
            put("content", content)
            version?.let { put("sha", it) }
        }
        val response = api.call("PUT", path, body)
        return when (response.code) {
            in 200..299 -> true
            // Another device wrote first (or created the file): read again and merge.
            409, 422 -> false
            else -> throw SyncException(SyncProblem.Network, "GitHub ${response.code}")
        }
    }

    companion object {
        const val FILE = "history.json"
    }
}

/**
 * The device's connection to the shared history. Platforms install their [SyncedHistoryStore] and
 * call [sync] when they start and every few minutes; [connect] runs GitHub's device flow.
 */
object HistorySyncService {
    /** KloudStream Sync, the GitHub App a device connects through. Not a secret (device flow). */
    const val CLIENT_ID = "Iv23likE7xMZd52OOxQx"

    data class Settings(val account: SyncAccount? = null, val lastSync: Long? = null, val problem: SyncProblem? = null)

    data class Status(val available: Boolean, val account: SyncAccount?, val lastSync: Long?,
        val problem: SyncProblem?, val busy: Boolean = false)

    private const val SETTINGS_KEY = "history_sync/settings"
    private var local: SyncedHistoryStore? = null
    private var device = "KloudStream"
    private var onApplied: () -> Unit = {}
    private val store: KeyValueStore get() = AggregationPlatform.store
    private val _status = MutableStateFlow(Status(CLIENT_ID.isNotEmpty(), null, null, null))
    val status: StateFlow<Status> get() = _status

    private fun settings() = store.get<Settings>(SETTINGS_KEY) ?: Settings()

    private fun save(settings: Settings, busy: Boolean = false) {
        store.set(SETTINGS_KEY, settings)
        _status.value = Status(CLIENT_ID.isNotEmpty(), settings.account, settings.lastSync, settings.problem, busy)
    }

    /** [onApplied] runs after works from other devices were applied here, to refresh the screens. */
    fun install(local: SyncedHistoryStore, device: String, onApplied: () -> Unit) {
        this.local = local
        this.device = device
        this.onApplied = onApplied
        val settings = settings()
        _status.value = Status(CLIENT_ID.isNotEmpty(), settings.account, settings.lastSync, settings.problem)
    }

    private val running = Mutex()

    /** Brings this device and the shared history up to date; false when not connected or failed. */
    suspend fun sync(): Boolean = running.withLock {
        val local = local ?: return false
        var settings = settings()
        var account = settings.account ?: return false
        save(settings, busy = true)
        repeat(2) { attempt ->
            try {
                val result = HistorySync(local, GitHubHistoryRemote(account, device), device).sync()
                save(Settings(account, System.currentTimeMillis(), null))
                if (result.applied > 0) onApplied()
                return true
            } catch (e: CancellationException) {
                save(settings)
                throw e
            } catch (e: SyncException) {
                if (e.problem == SyncProblem.Unauthorized && attempt == 0) {
                    GitHubLogin.refresh(CLIENT_ID, account)?.let {
                        account = it
                        settings = settings.copy(account = it)
                        return@repeat
                    }
                }
                val lost = e.problem == SyncProblem.Unauthorized
                save(settings.copy(account = if (lost) null else account, problem = e.problem))
                return false
            } catch (e: Exception) {
                save(settings.copy(problem = SyncProblem.Network))
                return false
            }
        }
        return false
    }

    /** Starts connecting this device: show [GitHubLogin.DeviceCode.userCode], then call [finishConnect]. */
    suspend fun startConnect(): GitHubLogin.DeviceCode = GitHubLogin.start(CLIENT_ID)

    /** Waits for the code to be entered, saves the access and syncs; the problem when it failed. */
    suspend fun finishConnect(code: GitHubLogin.DeviceCode): SyncProblem? = try {
        val account = GitHubLogin.await(CLIENT_ID, code)
        HistorySync.forget(store)
        save(Settings(account))
        sync()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncException) {
        e.problem
    }

    /** Forgets the access here (GitHub keeps it until revoked in the account's settings). */
    fun disconnect() {
        HistorySync.forget(store)
        save(Settings())
    }
}
