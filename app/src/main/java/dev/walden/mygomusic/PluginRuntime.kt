package dev.walden.mygomusic

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class PluginCallException(val code: String?, message: String) : Exception(message)

val pluginJson = Json { ignoreUnknownKeys = true }

data class SettingDef(
    val key: String,
    val label: String,
    val help: String?,
    val type: String?,
    val options: List<Pair<String, String>>,
    val link: String?,
)

data class NavDef(
    val builtin: String?,
    val page: String?,
    val title: String?,
    val icon: String?,
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val playback: String,
    val networkHosts: List<String>,
    val processPrograms: List<String>,
    val settings: List<SettingDef>,
    val navigation: List<NavDef>,
    val partial: Boolean,
    val partialReasons: List<String>,
)

object PluginCatalog {
    fun listBundled(context: Context): List<PluginManifest> {
        val am = context.assets
        val dirs = am.list("plugins") ?: return emptyList()
        val out = mutableListOf<PluginManifest>()
        for (dir in dirs.sorted()) {
            try {
                val text = am.open("plugins/$dir/plugin.json").bufferedReader().readText()
                val m = pluginJson.parseToJsonElement(text).jsonObject
                val main = m["main"]?.jsonPrimitive?.content ?: "index.js"
                val source = am.open("plugins/$dir/$main").bufferedReader().readText()
                val perms = m["permissions"]?.jsonObject
                val network = (perms?.get("network") as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                val process = (perms?.get("process") as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                val settings = (m["settings"] as? kotlinx.serialization.json.JsonArray)?.map { s ->
                    val o = s.jsonObject
                    val options = (o["options"] as? kotlinx.serialization.json.JsonArray)?.map { op ->
                        val oo = op.jsonObject
                        (oo["value"]?.jsonPrimitive?.content ?: "") to (oo["label"]?.jsonPrimitive?.content ?: "")
                    } ?: emptyList()
                    SettingDef(
                        key = o["key"]?.jsonPrimitive?.content ?: "",
                        label = o["label"]?.jsonPrimitive?.content ?: "",
                        help = o["help"]?.jsonPrimitive?.content,
                        type = o["type"]?.jsonPrimitive?.content,
                        options = options,
                        link = o["link"]?.jsonPrimitive?.content,
                    )
                } ?: emptyList()
                val navigation = (m["navigation"] as? kotlinx.serialization.json.JsonArray)?.map { n ->
                    val o = n.jsonObject
                    NavDef(
                        builtin = o["builtin"]?.jsonPrimitive?.content,
                        page = o["page"]?.jsonPrimitive?.content,
                        title = o["title"]?.jsonPrimitive?.content,
                        icon = o["icon"]?.jsonPrimitive?.content,
                    )
                } ?: emptyList()
                val playback = m["playback"]?.jsonPrimitive?.content ?: "stream"
                val reasons = mutableListOf<String>()
                if (playback != "stream") reasons += "remote playback"
                if (process.isNotEmpty()) reasons += "needs helper programs"
                if (source.contains("host.webSignIn")) reasons += "web sign-in"
                if (source.contains("host.waitForRedirect")) reasons += "browser sign-in"
                out += PluginManifest(
                    id = m["id"]?.jsonPrimitive?.content ?: dir,
                    name = m["name"]?.jsonPrimitive?.content ?: dir,
                    version = m["version"]?.jsonPrimitive?.content ?: "",
                    main = main,
                    playback = playback,
                    networkHosts = network,
                    processPrograms = process,
                    settings = settings,
                    navigation = navigation,
                    partial = reasons.isNotEmpty(),
                    partialReasons = reasons,
                )
            } catch (e: Exception) {
                // A malformed bundled plugin is skipped, never fatal.
            }
        }
        return out
    }
}

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun aesEcbHex(key: String, text: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
    return cipher.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun jsStr(s: String): String = Json.encodeToString(s)

/**
 * One loaded plugin: a QuickJS runtime, the host bindings from HOST_SPEC.md,
 * per-plugin storage (filesDir/plugins/<id>/storage.json) and settings
 * (SharedPreferences). All plugin functions go through [call]; results come
 * back as JSON strings for the caller to decode.
 */
class PluginRuntime(
    private val context: Context,
    val manifest: PluginManifest,
    private val sourceCode: String,
    private val locales: Map<String, String>,
) {
    private val engine: QuickJs = QuickJs.create(Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("plugin-${manifest.id}", Context.MODE_PRIVATE)
    private val dataDir = File(context.filesDir, "plugins/${manifest.id}").apply { mkdirs() }
    private val storageFile = File(dataDir, "storage.json")
    private val storage: MutableMap<String, String> = run {
        if (storageFile.exists()) {
            try {
                pluginJson.parseToJsonElement(storageFile.readText()).jsonObject
                    .mapValues { it.value.toString() }.toMutableMap()
            } catch (e: Exception) { mutableMapOf() }
        } else mutableMapOf()
    }
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * In-flight calls keyed by a monotonically increasing id.
     *
     * A single shared slot used to be overwritten by every concurrent caller,
     * so `_deliver` handed a result to whichever call had registered last:
     * results were cross-wired and the displaced caller hung until timeout.
     * The JS side now echoes the id back, so delivery is exact.
     */
    private val pendingCalls = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextCallId = java.util.concurrent.atomic.AtomicLong(0)

    /** Serialises native evaluation (and engine teardown) — QuickJS is single-threaded. */
    private val engineMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var closed = false

    private val delayId = java.util.concurrent.atomic.AtomicLong(0)
    private val delays = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    @Volatile
    var exports: Set<String> = emptySet()
        private set

    var onSignInPrompt: ((qr: String?, text: String?) -> Unit)? = null
    var onOpenURL: ((String) -> Unit)? = null

    fun can(name: String): Boolean = exports.contains(name)
    fun setting(key: String): String = prefs.getString("setting:$key", "") ?: ""

    suspend fun setSetting(key: String, value: String) {
        prefs.edit().putString("setting:$key", value).apply()
        if (can("setSetting")) {
            runCatching {
                call("setSetting", pluginJson.encodeToString(mapOf("key" to key, "value" to value)))
            }
        }
    }

    private fun persistStorage() {
        val obj = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in storage) put(k, pluginJson.parseToJsonElement(v))
        }
        storageFile.writeText(obj.toString())
    }

    private fun httpSync(reqJson: String): String {
        return try {
            val req = pluginJson.parseToJsonElement(reqJson).jsonObject
            val method = req["method"]!!.jsonPrimitive.content
            val url = req["url"]!!.jsonPrimitive.content
            val headers = req["headers"]?.jsonObject ?: JsonObject(emptyMap())
            val body = req["body"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val builder = Request.Builder().url(url)
            for ((k, v) in headers) builder.header(k, v.jsonPrimitive.content)
            val media = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
                ?.value?.jsonPrimitive?.content?.toMediaType()
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post((body ?: "").toRequestBody(media))
                else -> builder.method(method, body?.toRequestBody(media))
            }
            http.newCall(builder.build()).execute().use { resp ->
                val pairs = kotlinx.serialization.json.buildJsonArray {
                    for ((name, value) in resp.headers) add(kotlinx.serialization.json.buildJsonArray { add(name); add(value) })
                }
                kotlinx.serialization.json.buildJsonObject {
                    put("status", resp.code)
                    put("headers", pairs)
                    put("body", resp.body?.string() ?: "")
                }.toString()
            }
        } catch (e: Exception) {
            kotlinx.serialization.json.buildJsonObject {
                put("error", "${e.javaClass.simpleName}: ${e.message}")
            }.toString()
        }
    }

    suspend fun load() {
        // Bindings + module evaluation must not race engine teardown: a plugin
        // switch can close this runtime while it is still loading. The warm-up
        // call below is deliberately OUTSIDE the lock — Mutex is not reentrant
        // and call() takes the same lock.
        engineMutex.withLock {
            check(!closed) { "插件已关闭" }
            loadLocked()
        }
        // Warm-up: the engine can trip an internal result-box error on the
        // first call after a module load. Absorb it here on a side-effect-free
        // export so the first real user call is clean.
        if (can("account")) {
            runCatching { call("account", "{}", timeoutMs = 30_000) }
        }
    }

    private suspend fun loadLocked() {
        engine.function("_log") { a ->
            // Plugin console output used to be dropped on the floor, which made
            // diagnosing plugin-side failures guesswork.
            android.util.Log.d("MyGoPlugin", "[${manifest.id}] ${a.getOrNull(0)}")
            null
        }
        engine.function("_storageGet") { a -> synchronized(storage) { storage[a[0] as String] } }
        engine.function("_storageSet") { a ->
            synchronized(storage) { storage[a[0] as String] = a[1] as String; persistStorage() }
            null
        }
        engine.function("_storageRemove") { a ->
            synchronized(storage) { storage.remove(a[0] as String); persistStorage() }
            null
        }
        engine.function("_md5") { a -> md5Hex(a[0] as String) }
        engine.function("_aesEcb") { a -> aesEcbHex(a[0] as String, a[1] as String) }
        engine.function("_setting") { a -> setting(a[0] as String) }
        engine.function("_translate") { a -> locales[a[0] as String] ?: (a[0] as String) }
        engine.function("_language") { "zh-CN" }
        engine.function("_signInPrompt") { a ->
            val p = try { pluginJson.parseToJsonElement(a[0] as String).jsonObject } catch (e: Exception) { null }
            onSignInPrompt?.invoke(
                p?.get("qr")?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                p?.get("text")?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
            )
            null
        }
        engine.function("_openURL") { a -> onOpenURL?.invoke(a[0] as String); null }
        engine.function("_deliver") { a ->
            val id = (a[0] as Number).toLong()
            pendingCalls.remove(id)?.complete(a[1] as String)
            null
        }
        engine.function("_exportsReady") { a ->
            exports = try {
                pluginJson.parseToJsonElement(a[0] as String).let { e ->
                    (e as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }.toSet()
                }
            } catch (e: Exception) { emptySet() }
            null
        }
        engine.function("_nextDelayId") { delayId.incrementAndGet() }
        engine.function("_cancelDelayInternal") { a ->
            delays.remove((a[0] as Number).toLong())?.cancel(); null
        }
        engine.asyncFunction("_delayInternal") { a ->
            val millis = (a[0] as Number).toLong()
            val id = (a[1] as Number).toLong()
            coroutineScope {
                val job = async { delay(millis) }
                delays[id] = job
                job.await()
            }
        }
        engine.asyncFunction("_httpInternal") { a ->
            withContext(Dispatchers.IO) { httpSync(a[0] as String) }
        }
        val prelude = context.assets.open("prelude.js").bufferedReader().readText()
        engine.evaluate<Any?>(prelude, "prelude.js")
        engine.evaluate<Any?>(
            "host.dir = ${jsStr(dataDir.absolutePath)}; host.dataDir = ${jsStr(dataDir.absolutePath)};",
            "dirs.js",
        )
        engine.addModule("plugin-entry", sourceCode)
        engine.evaluate<Any?>(
            "import * as p from \"plugin-entry\";\nglobalThis.__exports = p;\n_exportsReady(JSON.stringify(Object.keys(p)));",
            "entry.js",
            asModule = true,
        )
    }

    private fun stripNullArrayElements(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(
                element.filter { it !is JsonNull }.map { stripNullArrayElements(it) }
            )
            is JsonObject -> JsonObject(element.mapValues { stripNullArrayElements(it.value) })
            else -> element
        }

    /**
     * A plugin result in both shapes: [stripped] has null array placeholders
     * removed so model decoding survives partial upstream failures, while
     * [raw] preserves the original array lengths — callers that page by
     * element count must use [raw], or their offset drifts (see BUGS.md M14).
     */
    data class RawCallResult(val stripped: String, val raw: String)

    /**
     * Exports that are safe to re-invoke after an engine hiccup. Anything that
     * mutates server state (`like`, `played`, `createPlaylist`, …) is absent on
     * purpose: retrying those double-applied the user's action (BUGS.md M15).
     */
    private val retryableExports = setOf(
        "account", "home", "browse", "search", "suggest",
        "playlists", "playlist", "liked", "isLiked", "recent",
        "album", "artist", "savedAlbums", "shows", "show",
        "stations", "comments", "lyrics", "stream",
    )

    suspend fun call(name: String, argsJson: String = "{}", timeoutMs: Long = 90_000): String =
        callWithRaw(name, argsJson, timeoutMs).stripped

    suspend fun callWithRaw(
        name: String,
        argsJson: String = "{}",
        timeoutMs: Long = 90_000,
    ): RawCallResult {
        check(!closed) { "插件已关闭" }
        // quickjs-kt can surface a plugin promise rejection while draining jobs
        // and abort the invoke evaluate itself. The authoritative result still
        // arrives via _deliver, so give delivery a short grace, then retry once
        // — but only for read-only exports.
        val mayRetry = name in retryableExports
        var attempt = 0
        while (true) {
            attempt++
            val id = nextCallId.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pendingCalls[id] = deferred
            var evaluateError: Exception? = null
            try {
                engineMutex.withLock {
                    check(!closed) { "插件已关闭" }
                    engine.evaluate<Any?>(
                        "__invoke(${jsStr(name)}, ${jsStr(argsJson)}, $id)",
                        "invoke.js",
                    )
                }
            } catch (e: Exception) {
                evaluateError = e
            }
            val resultJson = try {
                if (evaluateError != null && mayRetry && attempt == 1) {
                    withTimeout(2_000) { deferred.await() }
                } else {
                    withTimeout(timeoutMs) { deferred.await() }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (evaluateError != null && mayRetry && attempt == 1) {
                    pendingCalls.remove(id)
                    continue
                }
                throw evaluateError ?: e
            } finally {
                pendingCalls.remove(id)
            }
            val st = pluginJson.parseToJsonElement(resultJson).jsonObject
            if (st["ok"]?.jsonPrimitive?.boolean == true) {
                val raw = st["valueJson"]!!.jsonPrimitive.content
                if (raw == "null") return RawCallResult(raw, raw)
                return RawCallResult(
                    stripped = stripNullArrayElements(pluginJson.parseToJsonElement(raw)).toString(),
                    raw = raw,
                )
            }
            throw PluginCallException(
                st["code"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                st["error"]?.jsonPrimitive?.content ?: "unknown plugin error",
            )
        }
    }

    /**
     * Fail every in-flight call immediately, then tear the engine down behind
     * the same mutex the evaluators hold, so `engine.close()` can never run
     * underneath a live native evaluate.
     */
    fun close() {
        if (closed) return
        closed = true
        val cancelled = kotlinx.coroutines.CancellationException("插件已关闭")
        pendingCalls.values.forEach { it.completeExceptionally(cancelled) }
        pendingCalls.clear()
        delays.values.forEach { it.cancel() }
        delays.clear()
        scope.cancel()
        // Deliberately not the cancelled `scope`: teardown must still run.
        CoroutineScope(Dispatchers.Default).launch {
            engineMutex.withLock { runCatching { engine.close() } }
        }
    }
}
