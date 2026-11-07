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
    private val engine: QuickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
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

    private var pending: CompletableDeferred<String>? = null
    private var delayId = 0L
    private val delays = mutableMapOf<Long, Job>()

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
        engine.function("_log") { null }
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
        engine.function("_deliver") { a -> pending?.complete(a[0] as String); null }
        engine.function("_exportsReady") { a ->
            exports = try {
                pluginJson.parseToJsonElement(a[0] as String).let { e ->
                    (e as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }.toSet()
                }
            } catch (e: Exception) { emptySet() }
            null
        }
        engine.function("_nextDelayId") { delayId++ }
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
        // Warm-up: dokar alpha13's evaluate can trip an internal result-box
        // error on the first call after a module load. Absorb it here on a
        // side-effect-free export so the first real user call is clean.
        if (can("account")) {
            runCatching { call("account", "{}", timeoutMs = 30_000) }
        }
    }

    private fun stripNullArrayElements(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(
                element.filter { it !is JsonNull }.map { stripNullArrayElements(it) }
            )
            is JsonObject -> JsonObject(element.mapValues { stripNullArrayElements(it.value) })
            else -> element
        }

    suspend fun call(name: String, argsJson: String = "{}", timeoutMs: Long = 90_000): String {
        // dokar alpha13's evaluate also surfaces plugin promise rejections it
        // observes while draining jobs, and can occasionally abort the invoke
        // evaluate itself. The authoritative result arrives through _deliver;
        // if the evaluate threw, give delivery a short grace, then retry the
        // invoke once (same call, same args).
        var evaluateError: Exception? = null
        var attempt = 0
        while (true) {
            attempt++
            val deferred = CompletableDeferred<String>()
            pending = deferred
            evaluateError = null
            try {
                engine.evaluate<Any?>("__invoke(${jsStr(name)}, ${jsStr(argsJson)})", "invoke.js")
            } catch (e: Exception) {
                evaluateError = e
            }
            val resultJson = try {
                if (evaluateError != null && attempt == 1) {
                    withTimeout(2_000) { deferred.await() }
                } else {
                    withTimeout(timeoutMs) { deferred.await() }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (evaluateError != null && attempt == 1) continue
                throw evaluateError ?: e
            }
            val st = pluginJson.parseToJsonElement(resultJson).jsonObject
            if (st["ok"]?.jsonPrimitive?.boolean == true) {
                val raw = st["valueJson"]!!.jsonPrimitive.content
                if (raw == "null") return raw
                // Plugins can emit null placeholders inside arrays when an
                // upstream subrequest failed; drop them so model decoding
                // never crashes on a partial result.
                return stripNullArrayElements(pluginJson.parseToJsonElement(raw)).toString()
            }
            throw PluginCallException(
                st["code"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                st["error"]?.jsonPrimitive?.content ?: "unknown plugin error",
            )
        }
    }

    fun close() {
        scope.cancel()
        runCatching { engine.close() }
    }
}
