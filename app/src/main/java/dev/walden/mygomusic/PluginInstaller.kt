package dev.walden.mygomusic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Where an installed plugin came from. Bundled plugins have no origin. */
data class PluginOrigin(
    val type: String,
    val repo: String? = null,
    val ref: String? = null,
    val version: String? = null,
    val label: String? = null,
)

/** A validated plugin package waiting for the user to confirm installation. */
data class PluginPackage(
    val manifest: PluginManifest,
    val dir: File,
    val origin: PluginOrigin?,
    val tmpRoot: File,
)

data class PendingPluginInstall(
    val pkg: PluginPackage,
    val replacing: Boolean,
)

/**
 * Installs MyGo Music plugins into app-private storage. Code lives in
 * filesDir/plugin-code/<id>; plugin data/storage stays in filesDir/plugins/<id>
 * so uninstalling a plugin does not delete its login or saved state.
 */
object PluginInstaller {
    private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 128L * 1024 * 1024
    private const val MAX_FILES = 3000
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fromGitHub(context: Context, input: String): PluginPackage = withContext(Dispatchers.IO) {
        val repo = parseGitHubRepo(input) ?: throw IllegalArgumentException("无法识别 GitHub 仓库：$input")
        val bytes = downloadGitHubZip(repo)
        packageFromZip(context, bytes, PluginOrigin(type = "github", repo = repo))
    }

    suspend fun fromZipUri(context: Context, uri: Uri): PluginPackage = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_ZIP_BYTES)
        } ?: throw IOException("无法读取所选文件")
        if (bytes.size.toLong() > MAX_ZIP_BYTES) throw IOException("插件包过大")
        val label = uri.lastPathSegment ?: "zip"
        packageFromZip(context, bytes, PluginOrigin(type = "zip", label = label))
    }

    /** Moves a confirmed package into plugin-code/<id>, replacing any installed copy. */
    fun commit(context: Context, pkg: PluginPackage): PluginManifest {
        val codeRoot = File(context.filesDir, "plugin-code").apply { mkdirs() }
        val finalDir = File(codeRoot, pkg.manifest.id)
        if (finalDir.exists()) finalDir.deleteRecursively()
        val moved = pkg.dir.renameTo(finalDir)
        if (!moved) {
            copyDir(pkg.dir, finalDir)
            pkg.dir.deleteRecursively()
        }
        pkg.origin?.let { writeOrigin(finalDir, it) }
        pkg.tmpRoot.deleteRecursively()
        return pkg.manifest.copy(dir = finalDir, origin = pkg.origin)
    }

    fun discard(pkg: PluginPackage) {
        pkg.tmpRoot.deleteRecursively()
    }

    /** Removes only the plugin code; filesDir/plugins/<id> data is kept. */
    fun uninstall(context: Context, manifest: PluginManifest) {
        manifest.dir?.deleteRecursively()
    }

    fun isNewer(latest: String, installed: String): Boolean {
        if (latest == installed) return false
        val a = versionParts(latest)
        val b = versionParts(installed)
        if (a.isEmpty()) return false
        if (b.isEmpty()) return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(version: String): List<Int> {
        val match = Regex("\\d+(?:\\.\\d+)*").find(version) ?: return emptyList()
        return match.value.split('.').map { it.toIntOrNull() ?: 0 }
    }

    private fun parseGitHubRepo(input: String): String? {
        var s = input.trim().removeSuffix(".git").substringBefore('?').substringBefore('#')
        if (s.isEmpty()) return null
        if (s.contains("github.com/")) {
            s = s.substringAfter("github.com/")
        }
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (!owner.matches(Regex("[A-Za-z0-9_.-]+")) || !repo.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        return owner + "/" + repo
    }

    private fun getJson(url: String): kotlinx.serialization.json.JsonObject? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyGoMusicAndroid")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val text = res.body?.string() ?: return null
            return pluginJson.parseToJsonElement(text).jsonObject
        }
    }

    private fun getBytes(url: String, maxBytes: Long): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "MyGoMusicAndroid")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("下载失败：HTTP " + res.code)
            res.body?.let { body ->
                if (body.contentLength() > maxBytes) throw IOException("插件包过大")
                val bytes = body.byteStream().use { readLimited(it, maxBytes) }
                if (bytes.size.toLong() > maxBytes) throw IOException("插件包过大")
                return bytes
            }
        }
        throw IOException("空响应")
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val limit = (maxBytes + 1).toInt()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size, remaining))
            if (read < 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun downloadGitHubZip(repo: String): ByteArray {
        val release = runCatching {
            getJson("https://api.github.com/repos/" + repo + "/releases/latest")
        }.getOrNull()
        val zipUrl = release?.get("zipball_url")?.jsonPrimitive?.content
        return getBytes(zipUrl ?: "https://api.github.com/repos/" + repo + "/zipball", MAX_ZIP_BYTES)
    }

    private fun packageFromZip(context: Context, bytes: ByteArray, origin: PluginOrigin): PluginPackage {
        val tmpRoot = File(context.filesDir, "plugin-install-tmp/" + UUID.randomUUID()).apply { mkdirs() }
        try {
            unzip(bytes, tmpRoot)
            val root = findPluginRoot(tmpRoot) ?: throw IllegalArgumentException("压缩包里没有 plugin.json")
            val manifestFile = File(root, "plugin.json")
            val manifestText = manifestFile.readText()
            val parsed = pluginJson.parseToJsonElement(manifestText).jsonObject
            val main = parsed["main"]?.jsonPrimitive?.content ?: "index.js"
            val id = parsed["id"]?.jsonPrimitive?.content ?: root.name
            if (unsafePath(id) || unsafePath(main)) throw IllegalArgumentException("插件 id/main 不合法")
            val mainFile = File(root, main)
            if (!mainFile.isFile) throw IllegalArgumentException("插件入口文件缺失：" + main)
            val source = mainFile.readText()
            val manifest = PluginCatalog.parseManifest(root.name, manifestText, source, root, origin)
            return PluginPackage(manifest, root, origin, tmpRoot)
        } catch (e: Exception) {
            tmpRoot.deleteRecursively()
            throw e
        }
    }

    private fun findPluginRoot(tmp: File): File? {
        if (File(tmp, "plugin.json").isFile) return tmp
        val dirs = tmp.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return null
        val candidates = dirs.filter { File(it, "plugin.json").isFile }
        return if (candidates.size == 1) candidates[0] else null
    }

    private fun unsafePath(s: String): Boolean =
        s.isBlank() || s.contains('/') || s.contains('\\') || s.contains("..")

    private fun unzip(bytes: ByteArray, dest: File) {
        var total = 0L
        var files = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val parts = name.split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) { zis.closeEntry(); continue }
                if (parts.any { it == ".." || it.startsWith(".") || it.contains(':') }) {
                    zis.closeEntry(); continue
                }
                val out = parts.fold(dest) { acc, part -> File(acc, part) }
                if (entry.isDirectory || name.endsWith("/")) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    files++
                    if (files > MAX_FILES) throw IOException("插件文件过多")
                    var remaining = MAX_FILE_BYTES
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val read = zis.read(buf)
                            if (read < 0) break
                            if (read.toLong() > remaining) throw IOException("插件文件过大")
                            output.write(buf, 0, read)
                            remaining -= read
                            total += read
                            if (total > MAX_UNPACKED_BYTES) throw IOException("插件解压后过大")
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun copyDir(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            val target = File(to, child.name)
            if (child.isDirectory) copyDir(child, target) else child.copyTo(target, overwrite = true)
        }
    }

    private fun writeOrigin(dir: File, origin: PluginOrigin) {
        val obj = buildJsonObject {
            put("type", origin.type)
            origin.repo?.let { put("repo", it) }
            origin.ref?.let { put("ref", it) }
            origin.version?.let { put("version", it) }
            origin.label?.let { put("label", it) }
        }
        File(dir, ".origin.json").writeText(obj.toString())
    }
}

