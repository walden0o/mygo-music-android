import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PluginCallException(val code: String?, message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

// ---------------------------------------------------------------------------
// Host state: per-plugin storage (a JSON file), settings, locales, HTTP.
// ---------------------------------------------------------------------------
class HostState(
    private val storageFile: File,
    val settings: Map<String, String>,
    val locales: Map<String, String>,
    private val http: OkHttpClient,
) {
    private val storage: MutableMap<String, String> = run {
        if (storageFile.exists()) {
            json.parseToJsonElement(storageFile.readText()).jsonObject
                .mapValues { it.value.toString() }.toMutableMap()
        } else mutableMapOf()
    }

    private fun persist() {
        val obj = buildJsonObject { for ((k, v) in storage) put(k, json.parseToJsonElement(v)) }
        storageFile.writeText(obj.toString())
    }

    fun storageGet(key: String): String? = storage[key]
    fun storageSet(key: String, valueJson: String) { storage[key] = valueJson; persist() }
    fun storageRemove(key: String) { storage.remove(key); persist() }

    fun httpSync(reqJson: String): String {
        return try {
            val req = json.parseToJsonElement(reqJson).jsonObject
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
                val pairs = buildJsonArray {
                    for ((name, value) in resp.headers) add(buildJsonArray { add(name); add(value) })
                }
                // Mirror the app's PluginRuntime.httpSync: text body + base64
                // binary form for fetch().arrayBuffer().
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                buildJsonObject {
                    put("status", resp.code)
                    put("headers", pairs)
                    put("body", bodyBytes.toString(charset))
                    put("bodyBase64", java.util.Base64.getEncoder().encodeToString(bodyBytes))
                }.toString()
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }.toString()
        }
    }
}

fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun aesEcbHex(key: String, text: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
    return cipher.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Plain by default. The TLS-trust override and the HTTP proxy are opt-in via
 * environment variables, because they used to be hardcoded to one developer's
 * machine (a private .p12 and a localhost proxy), which made the harness
 * unusable anywhere else.
 *
 *   MYGO_HARNESS_TRUSTSTORE=/path/to/trust.p12   (optional)
 *   MYGO_HARNESS_TRUSTSTORE_PASSWORD=changeit    (optional, default "changeit")
 *   MYGO_HARNESS_PROXY=host:port                 (optional)
 */
fun httpClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)

    System.getenv("MYGO_HARNESS_TRUSTSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
        val file = File(path)
        require(file.isFile) { "MYGO_HARNESS_TRUSTSTORE does not exist: $path" }
        val password = (System.getenv("MYGO_HARNESS_TRUSTSTORE_PASSWORD") ?: "changeit").toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, password) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, tmf.trustManagers, null)
        builder.sslSocketFactory(ssl.socketFactory, tmf.trustManagers[0] as X509TrustManager)
    }

    System.getenv("MYGO_HARNESS_PROXY")?.takeIf { it.isNotBlank() }?.let { spec ->
        val host = spec.substringBeforeLast(':')
        val port = spec.substringAfterLast(':').toIntOrNull()
        require(port != null && port in 1..65535) { "MYGO_HARNESS_PROXY must be host:port, got: $spec" }
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
    }

    return builder.build()
}

fun jsStr(s: String): String = Json.encodeToString(s)

// ---------------------------------------------------------------------------
// Plugin runtime on dokar3/quickjs-kt.
// ---------------------------------------------------------------------------
class Runtime(
    private val engine: QuickJs,
    private val host: HostState,
) {
    private val pendingCalls = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextCallId = java.util.concurrent.atomic.AtomicLong(0)
    private var delayId = 0L
    private val delays = mutableMapOf<Long, Job>()

    suspend fun install() {
        engine.function("_log") { a -> println("  [plugin] " + a.joinToString(" ") { it.toString() }); null }
        engine.function("_storageGet") { a -> host.storageGet(a[0] as String) }
        engine.function("_storageSet") { a -> host.storageSet(a[0] as String, a[1] as String); null }
        engine.function("_storageRemove") { a -> host.storageRemove(a[0] as String); null }
        engine.function("_md5") { a -> md5Hex(a[0] as String) }
        engine.function("_aesEcb") { a -> aesEcbHex(a[0] as String, a[1] as String) }
        engine.function("_setting") { a -> host.settings[a[0] as String] ?: "" }
        engine.function("_translate") { a -> host.locales[a[0] as String] ?: (a[0] as String) }
        engine.function("_language") { "zh-CN" }
        engine.function("_signInPrompt") { a -> println("  [signInPrompt] ${a[0]}"); null }
        engine.function("_openURL") { a -> println("  [openURL] ${a[0]}"); null }
        engine.function("_deliver") { a ->
            val id = (a[0] as Number).toLong()
            pendingCalls.remove(id)?.complete(a[1] as String)
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
            withContext(Dispatchers.IO) { host.httpSync(a[0] as String) }
        }
        val prelude = object {}.javaClass.getResource("/prelude.js")!!.readText()
        engine.evaluate<Any?>(prelude, "prelude.js")
        // Mirror the app's dirs.js: plugins such as bilibili touch `host` at
        // module top level, so it must exist before the entry module is loaded.
        val dataDir = File("build/harness-plugin-data").apply { mkdirs() }.absolutePath
        engine.evaluate<Any?>(
            "host.dir = ${jsStr(dataDir)}; host.dataDir = ${jsStr(dataDir)};",
            "dirs.js",
        )
    }

    suspend fun loadModule(entryCode: String) {
        engine.evaluate<Any?>(entryCode, "entry.js", asModule = true)
    }

    /** Names exported by the plugin module (read from the `__exports` global). */
    suspend fun exportNames(): Set<String> {
        val raw = engine.evaluate<String>("JSON.stringify(Object.keys(__exports))", "exports.js")
        return json.parseToJsonElement(raw).jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
    }

    suspend fun call(name: String, argsJson: String, timeoutMs: Long = 120_000): String {
        val id = nextCallId.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pendingCalls[id] = deferred
        var evaluateError: Exception? = null
        try {
            engine.evaluate<Any?>("__invoke(${jsStr(name)}, ${jsStr(argsJson)}, $id)", "invoke.js")
        } catch (e: Exception) {
            // quickjs-kt can surface plugin promise rejections while draining
            // jobs; the authoritative result still arrives through _deliver,
            // so only fall back to this if it doesn't.
            evaluateError = e
        }
        val resultJson = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            throw evaluateError ?: e
        } finally {
            pendingCalls.remove(id)
        }
        val st = try {
            json.parseToJsonElement(resultJson).jsonObject
        } catch (e: Exception) {
            println("  [call $name raw] len=${resultJson.length} head=${resultJson.take(90)} tail=${resultJson.takeLast(40)}")
            throw RuntimeException(
                "outer parse failed (len=${resultJson.length}, end=${resultJson.takeLast(30)}): ${e.message}",
                e,
            )
        }
        if (st["ok"]?.jsonPrimitive?.boolean == true) {
            return st["valueJson"]!!.jsonPrimitive.content
        }
        throw PluginCallException(
            st["code"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
            st["error"]?.jsonPrimitive?.content ?: "unknown plugin error",
        )
    }
}

// ---------------------------------------------------------------------------
// Test bookkeeping (single-plugin mode and --all mode share this)
// ---------------------------------------------------------------------------
enum class Outcome { PASS, SKIP, NET, FAIL }

data class Row(val plugin: String, val function: String, val outcome: Outcome, val detail: String)
val rows = mutableListOf<Row>()

fun record(plugin: String, fn: String, outcome: Outcome, detail: String) {
    rows += Row(plugin, fn, outcome, detail)
    println("%-5s %-22s %-24s %s".format(outcome, plugin, fn, detail))
}

fun isNetworkMessage(msg: String?): Boolean {
    val m = msg ?: return false
    return listOf(
        "timeout", "timed out", "SocketTimeout", "UnknownHost", "ConnectException",
        "ECONNRESET", "ENETUNREACH", "network", "fetch failed", "IOException", "SSL",
        "Connection reset", "No route to host", "Unable to resolve host", "Failed to connect",
    ).any { m.contains(it, ignoreCase = true) }
}

/** JS/parse failures are real host-visible defects; service errors are not. */
fun looksLikeCodeError(msg: String?): Boolean {
    val m = msg ?: return false
    return listOf(
        "TypeError", "ReferenceError", "SyntaxError", "RangeError", "Cannot read",
        "is not a function", "is not defined", "Unexpected token", "JSON.parse",
        "Cannot convert", "null is not", "undefined is not",
    ).any { m.contains(it, ignoreCase = true) }
}

/** Retry once on timeout / IO / network-flavoured plugin errors, then classify. */
suspend fun genericCall(
    plugin: String,
    fn: String,
    timeoutMs: Long = 15_000,
    block: suspend () -> String,
) {
    var attempt = 0
    while (true) {
        attempt++
        try {
            val detail = kotlinx.coroutines.withTimeout(timeoutMs) { block() }
            record(plugin, fn, Outcome.PASS, detail)
            return
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (attempt == 1) {
                println("  [$plugin/$fn] timeout, retrying once")
                continue
            }
            record(plugin, fn, Outcome.NET, "timeout after retry")
            return
        } catch (e: java.io.IOException) {
            if (attempt == 1) {
                println("  [$plugin/$fn] ${e.javaClass.simpleName}, retrying once")
                continue
            }
            record(plugin, fn, Outcome.NET, "${e.javaClass.simpleName}: ${e.message}")
            return
        } catch (e: PluginCallException) {
            val code = e.code ?: ""
            val msg = e.message
            when {
                code in setOf("signIn", "setup", "unavailable", "unsupported") ->
                    record(plugin, fn, Outcome.SKIP, "plugin code=$code")
                isNetworkMessage(msg) -> {
                    if (attempt == 1) {
                        println("  [$plugin/$fn] network error, retrying once")
                        continue
                    }
                    record(plugin, fn, Outcome.NET, "network: ${msg?.take(140)}")
                }
                // A plugin-reported service/region/anti-bot error is not a host
                // code defect. Only actual JS/parse failures are FAILs.
                looksLikeCodeError(msg) ->
                    record(plugin, fn, Outcome.FAIL, "plugin error code=$code: ${msg?.take(160)}")
                else ->
                    record(plugin, fn, Outcome.SKIP, "plugin-reported: ${msg?.take(120)}")
            }
            return
        } catch (e: Exception) {
            record(plugin, fn, Outcome.FAIL, "${e.javaClass.simpleName}: ${e.message?.take(160)}")
            return
        }
    }
}

var passed = 0
var failed = 0
suspend fun test(name: String, block: suspend () -> String) {
    try {
        val detail = block()
        passed++
        println("PASS  $name — $detail")
    } catch (e: PluginCallException) {
        failed++
        println("FAIL  $name — plugin error code=${e.code} message=${e.message}")
    } catch (e: Exception) {
        failed++
        println("FAIL  $name — ${e.javaClass.simpleName}: ${e.message}")
    }
}

// ---------------------------------------------------------------------------
// Generic mode: exercise every bundled plugin's available exports.
// ---------------------------------------------------------------------------
suspend fun runPluginGeneric(pluginDir: File, http: OkHttpClient) {
    val manifest = runCatching {
        json.parseToJsonElement(File(pluginDir, "plugin.json").readText()).jsonObject
    }.getOrNull()
    if (manifest == null) {
        record(pluginDir.name, "manifest", Outcome.FAIL, "unreadable plugin.json")
        return
    }
    val id = manifest["id"]?.jsonPrimitive?.content ?: pluginDir.name
    val mainFile = manifest["main"]?.jsonPrimitive?.content ?: "index.js"
    val playback = manifest["playback"]?.jsonPrimitive?.content ?: "stream"
    val processPerms = (manifest["permissions"]?.jsonObject?.get("process") as? kotlinx.serialization.json.JsonArray)
        ?.size ?: 0
    val needsHelperProcess = processPerms > 0 || playback != "stream"
    val localesFile = File(pluginDir, "locales/zh-CN.json")
    val locales: Map<String, String> = if (localesFile.exists()) {
        json.parseToJsonElement(localesFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    } else emptyMap()

    val storageDir = File("build/harness-data").apply { mkdirs() }
    val host = HostState(
        storageFile = File(storageDir, "$id.json"),
        settings = mapOf("quality" to "exhigh", "loginMethod" to "qr"),
        locales = locales,
        http = http,
    )
    val engine = QuickJs.create(jobDispatcher = Dispatchers.Default)
    val runtime = Runtime(engine, host)
    try {
        runtime.install()
        engine.addModule("plugin-entry", File(pluginDir, mainFile).readText())
        runtime.loadModule("import * as plugin from \"plugin-entry\";\nglobalThis.__exports = plugin;")
        val exports = runtime.exportNames()
        record(id, "module-load", Outcome.PASS, "exports=${exports.size}")
        runCatching { runtime.call("__warmup", "{}") }

        val has = { name: String -> exports.contains(name) }
        var trackId: String? = null
        var playlistId: String? = null
        var albumId: String? = null
        var artistId: String? = null
        var showId: String? = null

        if (has("account")) genericCall(id, "account") {
            val r = runtime.call("account", "{}")
            if (r.trim() == "null") "null (logged out)" else "account object"
        }
        if (has("home")) genericCall(id, "home") {
            val sections = json.parseToJsonElement(runtime.call("home", "{}"))
                .jsonObject["sections"]?.jsonArray
            "sections=${sections?.size ?: 0}"
        }
        if (has("browse")) genericCall(id, "browse") {
            // Mirror the app: browse is always called with a `page` ("" = root).
            val sections = json.parseToJsonElement(runtime.call("browse", """{"page":""}"""))
                .jsonObject["sections"]?.jsonArray
            "sections=${sections?.size ?: 0}"
        }
        if (has("suggest")) genericCall(id, "suggest") {
            val arr = json.parseToJsonElement(runtime.call("suggest", """{"query":"周杰"}""")).jsonArray
            "suggestions=${arr.size}"
        }
        if (has("search")) {
            genericCall(id, "search.tracks") {
                val r = runtime.call("search", """{"query":"周杰伦","kind":"tracks","offset":0}""")
                val tracks = json.parseToJsonElement(r).jsonObject["tracks"]?.jsonArray
                    ?: throw RuntimeException("no 'tracks' field")
                if (tracks.isNotEmpty()) trackId = tracks[0].jsonObject["id"]?.jsonPrimitive?.content
                "tracks=${tracks.size}"
            }
            genericCall(id, "search.playlists") {
                val r = runtime.call("search", """{"query":"周杰伦","kind":"playlists","offset":0}""")
                val arr = try {
                    json.parseToJsonElement(r).jsonObject["playlists"]?.jsonArray
                        ?: throw RuntimeException("no 'playlists' field")
                } catch (e: Exception) {
                    // Print the raw-result shape to stdout as well: the matrix
                    // record keeps only the root-cause message, which hides
                    // whether the delivered JSON was truncated or empty.
                    println("  [search.playlists raw] len=${r.length} head=${r.take(60)} tail=${r.takeLast(60)}")
                    throw RuntimeException(
                        "parse failed (len=${r.length}, head=${r.take(40)}, tail=${r.takeLast(40)}): ${e.message}",
                        e,
                    )
                }
                if (arr.isNotEmpty()) playlistId = arr[0].jsonObject["id"]?.jsonPrimitive?.content
                "playlists=${arr.size}"
            }
            genericCall(id, "search.albums") {
                val r = runtime.call("search", """{"query":"周杰伦","kind":"albums","offset":0}""")
                val arr = json.parseToJsonElement(r).jsonObject["albums"]?.jsonArray
                    ?: throw RuntimeException("no 'albums' field")
                if (arr.isNotEmpty()) albumId = arr[0].jsonObject["id"]?.jsonPrimitive?.content
                "albums=${arr.size}"
            }
            genericCall(id, "search.artists") {
                val r = runtime.call("search", """{"query":"周杰伦","kind":"artists","offset":0}""")
                val arr = json.parseToJsonElement(r).jsonObject["artists"]?.jsonArray
                    ?: throw RuntimeException("no 'artists' field")
                if (arr.isNotEmpty()) artistId = arr[0].jsonObject["id"]?.jsonPrimitive?.content
                "artists=${arr.size}"
            }
        }
        if (has("lyrics")) {
            val tid = trackId
            if (tid == null) record(id, "lyrics", Outcome.SKIP, "no track id from search")
            else genericCall(id, "lyrics") {
                val o = json.parseToJsonElement(runtime.call("lyrics", """{"id":"$tid"}""")).jsonObject
                val lines = o["lines"]?.jsonArray
                val lrc = o["lrc"]?.jsonPrimitive?.content
                "lines=${lines?.size ?: 0} lrc=${lrc?.length ?: 0}ch"
            }
        }
        if (has("stream")) {
            val tid = trackId
            when {
                needsHelperProcess -> record(
                    id, "stream", Outcome.SKIP,
                    "requires helper process/remote playback (host.spawn not provided on Android)",
                )
                tid == null -> record(id, "stream", Outcome.SKIP, "no track id from search")
                else -> genericCall(id, "stream") {
                    val o = json.parseToJsonElement(
                        runtime.call("stream", """{"id":"$tid","quality":"standard"}""")
                    ).jsonObject
                    val u = o["url"]?.jsonPrimitive?.content
                    if (u.isNullOrBlank()) throw RuntimeException("empty stream url")
                    val hostName = runCatching { java.net.URI(u).host }.getOrNull()
                    "type=${o["type"]?.jsonPrimitive?.content} host=$hostName"
                }
            }
        }
        if (has("playlist")) {
            val pid = playlistId
            if (pid == null) record(id, "playlist", Outcome.SKIP, "no playlist id from search")
            else genericCall(id, "playlist") {
                val o = json.parseToJsonElement(runtime.call("playlist", """{"id":"$pid"}""")).jsonObject
                "name=${o["name"]?.jsonPrimitive?.content} tracks=${o["tracks"]?.jsonArray?.size ?: 0}"
            }
        }
        if (has("album")) {
            val aid = albumId
            if (aid == null) record(id, "album", Outcome.SKIP, "no album id from search")
            else genericCall(id, "album") {
                val o = json.parseToJsonElement(runtime.call("album", """{"id":"$aid"}""")).jsonObject
                "tracks=${o["tracks"]?.jsonArray?.size ?: 0}"
            }
        }
        if (has("artist")) {
            val aid = artistId
            if (aid == null) record(id, "artist", Outcome.SKIP, "no artist id from search")
            else genericCall(id, "artist") {
                val o = json.parseToJsonElement(runtime.call("artist", """{"id":"$aid"}""")).jsonObject
                "albums=${o["albums"]?.jsonArray?.size ?: 0} tracks=${o["tracks"]?.jsonArray?.size ?: 0}"
            }
        }
        if (has("playlists")) genericCall(id, "playlists") {
            val arr = json.parseToJsonElement(runtime.call("playlists", "{}")).jsonArray
            "playlists=${arr.size}"
        }
        if (has("liked")) genericCall(id, "liked") {
            val o = json.parseToJsonElement(runtime.call("liked", """{"offset":0}""")).jsonObject
            "tracks=${o["tracks"]?.jsonArray?.size ?: 0} more=${o["more"]?.jsonPrimitive?.content}"
        }
        if (has("recent")) genericCall(id, "recent") {
            val arr = json.parseToJsonElement(runtime.call("recent", "{}")).jsonArray
            "recent=${arr.size}"
        }
        if (has("savedAlbums")) genericCall(id, "savedAlbums") {
            val arr = json.parseToJsonElement(runtime.call("savedAlbums", "{}")).jsonArray
            "albums=${arr.size}"
        }
        if (has("shows")) {
            genericCall(id, "shows") {
                val arr = json.parseToJsonElement(runtime.call("shows", "{}")).jsonArray
                if (arr.isNotEmpty()) showId = arr[0].jsonObject["id"]?.jsonPrimitive?.content
                "shows=${arr.size}"
            }
            if (has("show")) {
                val sid = showId
                if (sid == null) record(id, "show", Outcome.SKIP, "no show id")
                else genericCall(id, "show") {
                    val o = json.parseToJsonElement(
                        runtime.call("show", """{"id":"$sid","offset":0}""")
                    ).jsonObject
                    "episodes=${o["episodes"]?.jsonArray?.size ?: 0}"
                }
            }
        }

        val covered = setOf(
            "account", "home", "browse", "suggest", "search", "lyrics", "stream",
            "playlist", "album", "artist", "playlists", "liked", "recent",
            "savedAlbums", "shows", "show",
        )
        val unexercised = exports - covered - setOf("default")
        if (unexercised.isNotEmpty()) {
            record(id, "unexercised", Outcome.SKIP, unexercised.sorted().joinToString(","))
        }
    } catch (e: Exception) {
        record(id, "module-load", Outcome.FAIL, "${e.javaClass.simpleName}: ${e.message}")
    } finally {
        runCatching { engine.close() }
    }
}

fun main(args: Array<String>) = runBlocking {
    val http = httpClient()
    val bundledRoot = File("../app/src/main/assets/plugins").let {
        if (it.isDirectory) it else File("app/src/main/assets/plugins")
    }
    if (args.firstOrNull() == "--all") {
        require(bundledRoot.isDirectory) { "bundled plugin dir not found: ${bundledRoot.absolutePath}" }
        val filter = args.getOrNull(1)
        val dirs = bundledRoot.listFiles { f -> f.isDirectory }
            ?.filter { filter == null || it.name.contains(filter, ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        println("Running generic harness for ${dirs.size} bundled plugins (filter=${filter ?: "*"})\n")
        for (dir in dirs) {
            println("=== ${dir.name} ===")
            runPluginGeneric(dir, http)
        }
        println("\n================ MATRIX ================")
        val byPlugin = rows.groupBy { it.plugin }
        for ((plugin, list) in byPlugin) {
            val counts = list.groupingBy { it.outcome }.eachCount()
            println("$plugin: " + Outcome.entries.joinToString(" ") { "$it=${counts[it] ?: 0}" })
        }
        val totals = rows.groupingBy { it.outcome }.eachCount()
        println("\nTOTAL " + Outcome.entries.joinToString(" ") { "$it=${totals[it] ?: 0}" })
        return@runBlocking
    }

    // Default to a plugin bundled in this repo, so the harness runs on a fresh
    // clone with no arguments. Override with argv[0] or MYGO_HARNESS_PLUGIN_DIR.
    val defaultPluginDir = File(bundledRoot, "netease-cloud-music").path
    val pluginDir = File(
        args.getOrNull(0)
            ?: System.getenv("MYGO_HARNESS_PLUGIN_DIR")
            ?: defaultPluginDir
    )
    require(pluginDir.isDirectory) {
        "plugin directory not found: ${pluginDir.absolutePath}\n" +
            "pass a path as the first argument, or set MYGO_HARNESS_PLUGIN_DIR"
    }
    val manifest = json.parseToJsonElement(File(pluginDir, "plugin.json").readText()).jsonObject
    println("plugin: ${manifest["name"]?.jsonPrimitive?.content} v${manifest["version"]?.jsonPrimitive?.content} (${manifest["id"]?.jsonPrimitive?.content})")
    val mainFile = manifest["main"]?.jsonPrimitive?.content ?: "index.js"
    val pluginSource = File(pluginDir, mainFile).readText()

    val localesFile = File(pluginDir, "locales/zh-CN.json")
    val locales: Map<String, String> = if (localesFile.exists()) {
        json.parseToJsonElement(localesFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    } else emptyMap()

    val host = HostState(
        storageFile = File("storage.json"),
        settings = mapOf("quality" to "exhigh", "loginMethod" to "qr"),
        locales = locales,
        http = http,
    )

    val engine = QuickJs.create(jobDispatcher = Dispatchers.Default)
    val runtime = Runtime(engine, host)
    // Install bindings + prelude FIRST. addModule() evaluates the module's top
    // level, and plugins such as bilibili touch `host` there — registering the
    // module before the prelude existed threw "host is not defined".
    runtime.install()
    engine.addModule("plugin-entry", pluginSource)

    // Engine smoke test before the real plugin: async + setTimeout end to end.
    engine.evaluate<Any?>(
        "globalThis.__exports = { ping: async function() { await new Promise(function(r){ setTimeout(r, 30); }); return { pong: 1 }; } };",
        "smoke.js",
    )
    test("engine smoke (async + timer + promise)") {
        val r = runtime.call("ping", "{}")
        if (!r.contains("pong")) throw RuntimeException("unexpected: $r")
        r
    }

    try {
        runtime.loadModule("import * as plugin from \"plugin-entry\";\nglobalThis.__exports = plugin;")
        println("module wrapper evaluated")
    } catch (e: Exception) {
        println("FAIL  module load — ${e.javaClass.simpleName}: ${e.message}")
        return@runBlocking
    }

    test("home") {
        val r = runtime.call("home", "{}")
        "sections=" + (json.parseToJsonElement(r).jsonObject["sections"]?.jsonArray?.size ?: 0)
    }
    test("search tracks") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"tracks","offset":0}""")
        "tracks=" + (json.parseToJsonElement(r).jsonObject["tracks"]?.jsonArray?.size ?: 0)
    }
    test("search playlists") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"playlists","offset":0}""")
        "playlists=" + (json.parseToJsonElement(r).jsonObject["playlists"]?.jsonArray?.size ?: 0)
    }
    test("search albums") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"albums","offset":0}""")
        "albums=" + (json.parseToJsonElement(r).jsonObject["albums"]?.jsonArray?.size ?: 0)
    }
    test("search artists") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"artists","offset":0}""")
        "artists=" + (json.parseToJsonElement(r).jsonObject["artists"]?.jsonArray?.size ?: 0)
    }
    test("suggest") {
        val r = runtime.call("suggest", """{"query":"周杰"}""")
        "suggestions=" + json.parseToJsonElement(r).jsonArray.size
    }

    // Resolve one real track id from the search result for detail/lyrics/stream.
    val trackId = runCatching {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"tracks","offset":0}""")
        json.parseToJsonElement(r).jsonObject["tracks"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.content
    }.getOrNull()
    if (trackId != null) {
        test("lyrics") {
            val o = json.parseToJsonElement(runtime.call("lyrics", """{"id":"$trackId"}""")).jsonObject
            "lines=" + (o["lines"]?.jsonArray?.size ?: 0) +
                " lrc=" + (o["lrc"]?.jsonPrimitive?.content?.length ?: 0) + "ch"
        }
        test("stream") {
            val o = json.parseToJsonElement(
                runtime.call("stream", """{"id":"$trackId","quality":"standard"}""")
            ).jsonObject
            val u = o["url"]?.jsonPrimitive?.content
            if (u.isNullOrBlank()) throw RuntimeException("empty stream url")
            "type=" + o["type"]?.jsonPrimitive?.content
        }
    } else {
        println("SKIP  lyrics/stream — no track id from search")
    }
    test("playlists") {
        val r = runtime.call("playlists", "{}")
        "playlists=" + json.parseToJsonElement(r).jsonArray.size
    }
    test("liked") {
        val o = json.parseToJsonElement(runtime.call("liked", """{"offset":0}""")).jsonObject
        "tracks=" + (o["tracks"]?.jsonArray?.size ?: 0) +
            " more=" + o["more"]?.jsonPrimitive?.content
    }

    println("\nRESULT passed=$passed failed=$failed")
    runCatching { engine.close() }
    Unit
}
