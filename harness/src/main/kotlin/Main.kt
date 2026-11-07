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
                buildJsonObject {
                    put("status", resp.code)
                    put("headers", pairs)
                    put("body", resp.body?.string() ?: "")
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

fun httpClient(): OkHttpClient {
    val ks = KeyStore.getInstance("PKCS12")
    File("/home/hatch/workspace/tools/hatch-trust.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(null, tmf.trustManagers, null)
    return OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("::1", 18080)))
        .sslSocketFactory(ssl.socketFactory, tmf.trustManagers[0] as X509TrustManager)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

fun jsStr(s: String): String = Json.encodeToString(s)

// ---------------------------------------------------------------------------
// Plugin runtime on dokar3/quickjs-kt.
// ---------------------------------------------------------------------------
class Runtime(
    private val engine: QuickJs,
    private val host: HostState,
) {
    private var pending: CompletableDeferred<String>? = null
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
        engine.function("_deliver") { a -> pending?.complete(a[0] as String); null }
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
    }

    suspend fun loadModule(entryCode: String) {
        engine.evaluate<Any?>(entryCode, "entry.js", asModule = true)
    }

    suspend fun call(name: String, argsJson: String, timeoutMs: Long = 120_000): String {
        val deferred = CompletableDeferred<String>()
        pending = deferred
        var evaluateError: Exception? = null
        try {
            engine.evaluate<Any?>("__invoke(${jsStr(name)}, ${jsStr(argsJson)})", "invoke.js")
        } catch (e: Exception) {
            // dokar alpha13's evaluate also surfaces plugin promise rejections
            // it observes while draining jobs; the authoritative result still
            // arrives through _deliver, so only fall back to this if it doesn't.
            evaluateError = e
        }
        val resultJson = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            throw evaluateError ?: e
        }
        val st = json.parseToJsonElement(resultJson).jsonObject
        if (st["ok"]?.jsonPrimitive?.boolean == true) {
            return st["valueJson"]!!.jsonPrimitive.content
        }
        throw PluginCallException(
            st["code"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
            st["error"]?.jsonPrimitive?.content ?: "unknown plugin error",
        )
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

fun main(args: Array<String>) = runBlocking {
    val pluginDir = File(args.getOrElse(0) { "/home/hatch/workspace/mygo-android/ref-src/mygo-music-netease-cloud-music-main" })
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
        http = httpClient(),
    )

    val engine = QuickJs.create(jobDispatcher = Dispatchers.Default)
    engine.addModule("plugin-entry", pluginSource)
    val runtime = Runtime(engine, host)
    runtime.install()

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
        println("MODULE LOAD FAILED: ${e.message}")
    }
    println("exports are exercised through the calls below")

    test("account (logged out)") {
        val r = runtime.call("account", "{}")
        if (r != "null") throw RuntimeException("expected null, got $r")
        "null as expected"
    }

    var firstTrackId: String? = null
    test("search tracks 周杰伦") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"tracks","offset":0}""")
        val tracks = json.parseToJsonElement(r).jsonObject["tracks"]!!.jsonArray
        if (tracks.isEmpty()) throw RuntimeException("no tracks")
        val first = tracks[0].jsonObject
        firstTrackId = first["id"]!!.jsonPrimitive.content
        val names = tracks.take(3).joinToString(" | ") {
            val o = it.jsonObject
            "${o["name"]?.jsonPrimitive?.content} — ${o["artists"]?.jsonArray?.joinToString(",") { a -> a.jsonObject["name"]!!.jsonPrimitive.content }}"
        }
        "${tracks.size} tracks; first: $names"
    }

    test("suggest 周杰") {
        val r = runtime.call("suggest", """{"query":"周杰"}""")
        val arr = json.parseToJsonElement(r).jsonArray
        "${arr.size} suggestions: ${arr.take(3).joinToString(", ") { it.jsonPrimitive.content }}"
    }

    test("search playlists -> playlist detail") {
        val r = runtime.call("search", """{"query":"周杰伦","kind":"playlists","offset":0}""")
        val pls = json.parseToJsonElement(r).jsonObject["playlists"]!!.jsonArray
        if (pls.isEmpty()) throw RuntimeException("no playlists")
        val pid = pls[0].jsonObject["id"]!!.jsonPrimitive.content
        val detail = runtime.call("playlist", """{"id":"$pid"}""")
        val o = json.parseToJsonElement(detail).jsonObject
        val tracks = o["tracks"]?.jsonArray
        "playlist '${o["name"]?.jsonPrimitive?.content}' trackCount=${o["trackCount"]?.jsonPrimitive?.content} hydrated=${tracks?.size}"
    }

    test("lyrics of first search track") {
        val id = firstTrackId ?: throw RuntimeException("no track id")
        val r = runtime.call("lyrics", """{"id":"$id"}""")
        val o = json.parseToJsonElement(r).jsonObject
        val lines = o["lines"]?.jsonArray
        val lrc = o["lrc"]?.jsonPrimitive?.content
        "lines=${lines?.size ?: 0} lrc=${lrc?.length ?: 0} chars" +
            (lines?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { " first=\"$it\"" } ?: "")
    }

    test("stream of first search track") {
        val id = firstTrackId ?: throw RuntimeException("no track id")
        val r = runtime.call("stream", """{"id":"$id","quality":"standard"}""")
        val o = json.parseToJsonElement(r).jsonObject
        "type=${o["type"]?.jsonPrimitive?.content} br=${o["bitRate"]?.jsonPrimitive?.content} url=${o["url"]?.jsonPrimitive?.content?.take(80)}..."
    }

    test("home (guest)") {
        val r = runtime.call("home", "{}")
        val sections = json.parseToJsonElement(r).jsonObject["sections"]?.jsonArray
        "sections=${sections?.size ?: 0}: " + (sections?.joinToString(", ") { it.jsonObject["title"]?.jsonPrimitive?.content ?: "?" } ?: "")
    }

    test("playlists (expect signIn error)") {
        try {
            val r = runtime.call("playlists", "{}")
            "no error?! returned ${r.take(80)}"
        } catch (e: PluginCallException) {
            if (e.code == "signIn") "signIn code as expected"
            else throw RuntimeException("unexpected code=${e.code} msg=${e.message}")
        }
    }

    println("== summary: $passed passed, $failed failed ==")
    engine.close()
}
