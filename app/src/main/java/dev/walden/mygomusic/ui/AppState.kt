package dev.walden.mygomusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.walden.mygomusic.Account
import dev.walden.mygomusic.Album
import dev.walden.mygomusic.AlbumPageResult
import dev.walden.mygomusic.Artist
import dev.walden.mygomusic.ArtistPageResult
import dev.walden.mygomusic.BrowsePage
import dev.walden.mygomusic.LinkItem
import dev.walden.mygomusic.Lyrics
import dev.walden.mygomusic.Player
import dev.walden.mygomusic.Playlist
import dev.walden.mygomusic.PluginCallException
import dev.walden.mygomusic.PluginCatalog
import dev.walden.mygomusic.PluginManifest
import dev.walden.mygomusic.PluginRuntime
import dev.walden.mygomusic.SearchResult
import dev.walden.mygomusic.ShowItem
import dev.walden.mygomusic.ShowPageResult
import dev.walden.mygomusic.Track
import dev.walden.mygomusic.TrackPage
import dev.walden.mygomusic.pluginJson
import dev.walden.mygomusic.ui.theme.ThemeMode
import dev.walden.mygomusic.util.qrBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ------------------------------------------------------------------ models

enum class Tab(val label: String) {
    Home("首页"),
    Browse("浏览"),
    Search("搜索"),
    Library("音乐库"),
}

/** Everything that can sit on the page stack above a tab. */
sealed interface Route {
    val title: String

    data class PlaylistDetail(val id: String, private val name: String) : Route {
        override val title get() = name
    }

    data class AlbumDetail(val id: String, private val name: String, val imageUrl: String?) : Route {
        override val title get() = name
    }

    data class ArtistDetail(val id: String, private val name: String, val imageUrl: String?) : Route {
        override val title get() = name
    }

    data class ShowDetail(val id: String, private val name: String) : Route {
        override val title get() = name
    }

    data class Page(val path: String, override val title: String) : Route

    data object Playlists : Route {
        override val title = "我的歌单"
    }

    data object Liked : Route {
        override val title = "喜欢的音乐"
    }

    data object Recent : Route {
        override val title = "最近播放"
    }

    data object Shows : Route {
        override val title = "播客"
    }
}

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun <T> Load<T>?.readyOrNull(): T? = (this as? Load.Ready<T>)?.value
fun Load<*>?.failedMessage(): String? = (this as? Load.Failed)?.message

enum class SearchKind(val label: String, val api: String) {
    Tracks("单曲", "tracks"),
    Albums("专辑", "albums"),
    Artists("歌手", "artists"),
    Playlists("歌单", "playlists"),
    Shows("播客", "shows"),
}

sealed interface Sheet {
    data object Plugins : Sheet
    data object Settings : Sheet
    data object Account : Sheet
    data object Queue : Sheet
    data class PluginSettings(val manifest: PluginManifest) : Sheet
}

data class SignInState(
    val qr: Bitmap? = null,
    val text: String? = null,
    val error: String? = null,
    val working: Boolean = true,
)

// ------------------------------------------------------------------- state

/**
 * The whole app's state: the active plugin session, every page's load state,
 * the page stack, a playback mirror and the UI sheets. It is process-scoped
 * (like [Player]) so a configuration change never tears down the QuickJS
 * runtime or the queue.
 */
class MyGoState private constructor(private val app: Context) {

    companion object {
        @Volatile
        private var instance: MyGoState? = null

        fun get(context: Context): MyGoState =
            instance ?: synchronized(this) {
                instance ?: MyGoState(context.applicationContext).also { instance = it }
            }
    }

    // -- plumbing ---------------------------------------------------------

    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var runtime: PluginRuntime? = null

    /**
     * Bumped whenever the active plugin changes. Every loader captures the
     * generation it started under and drops its result if it moved on, so a
     * slow answer from the old plugin can never land in the new session
     * (BUGS.md M18). Main-thread confined, like the rest of the state.
     */
    private var generation = 0

    /** In-flight page loaders, cancelled wholesale on plugin switch. */
    private val loaderJobs = mutableSetOf<Job>()

    /** The in-flight plugin activation, so a newer switch supersedes it. */
    private var activateJob: Job? = null

    private fun launchLoader(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(block = block)
        loaderJobs += job
        job.invokeOnCompletion { loaderJobs -= job }
        return job
    }

    /** Plugins that work out of the box come first in the switcher. */
    /**
     * Order the switcher by *verified API health*, not just "needs no setup".
     *
     * Checked end-to-end against the live services with the JVM harness:
     *  - `netease` / `netease-cloud-music`: search, suggest, playlist hydration,
     *    lyrics and home all pass (lyrics use `/api/song/lyric/v1`).
     *  - `netease-music` v0.4.0: search AND lyrics fail — it still calls the
     *    retired `/api/song/lyric` and `/api/cloudsearch/pc`, which answer
     *    "wrong parmas" / "参数错误". It must not be the default.
     */
    private fun preferredRank(id: String): Int {
        val order = listOf(
            "netease", "netease-cloud-music", "kugou", "bilibili",
            "youtube-music", "qqmusic", "lizhi", "netease-music",
        )
        val index = order.indexOf(id)
        return if (index >= 0) index else order.size
    }
    private var signInJob: Job? = null

    // -- chrome -----------------------------------------------------------

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "System") ?: "System") }
            .getOrDefault(ThemeMode.System)
    )
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamicColor", false))
        private set

    /**
     * POST_NOTIFICATIONS was denied at least once. Persisted so we ask only
     * once ever; the Settings sheet offers a recovery entry instead of nagging
     * on every launch (BUGS.md L16a).
     */
    var notificationPermissionDenied by mutableStateOf(prefs.getBoolean("notificationDenied", false))
        private set

    fun markNotificationPermissionDenied() {
        notificationPermissionDenied = true
        prefs.edit().putBoolean("notificationDenied", true).apply()
    }

    /** Called when the permission is observed granted (e.g. via system settings). */
    fun clearNotificationPermissionDenied() {
        if (!notificationPermissionDenied) return
        notificationPermissionDenied = false
        prefs.edit().remove("notificationDenied").apply()
    }

    var catalog by mutableStateOf<List<PluginManifest>>(emptyList())
        private set
    var manifest by mutableStateOf<PluginManifest?>(null)
        private set
    var pluginReady by mutableStateOf(false)
        private set
    var pluginError by mutableStateOf<String?>(null)
        private set
    var account by mutableStateOf<Account?>(null)
        private set

    var tab by mutableStateOf(Tab.Home)
        private set
    var stack by mutableStateOf<List<Route>>(emptyList())
        private set
    var sheet by mutableStateOf<Sheet?>(null)
        private set
    var playerOpen by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var signIn by mutableStateOf<SignInState?>(null)
        private set

    // -- page data --------------------------------------------------------

    var home by mutableStateOf<Load<BrowsePage>>(Load.Idle)
        private set
    val pages = mutableStateMapOf<String, Load<BrowsePage>>()
    val playlistDetails = mutableStateMapOf<String, Load<Playlist>>()
    val albumDetails = mutableStateMapOf<String, Load<AlbumPageResult>>()
    val artistDetails = mutableStateMapOf<String, Load<ArtistPageResult>>()
    val showDetails = mutableStateMapOf<String, Load<ShowPageResult>>()
    var library by mutableStateOf<Load<List<Playlist>>>(Load.Idle)
        private set
    var liked by mutableStateOf<Load<TrackPage>>(Load.Idle)
        private set
    var likedLoadingMore by mutableStateOf(false)
        private set
    // M14/C5: consumed offsets must come from the RAW (pre-null-strip) arrays.
    private var likedOffset = 0
    private val showOffsets = mutableMapOf<String, Int>()
    var showLoadingMore by mutableStateOf(false)
        private set
    var recent by mutableStateOf<Load<List<Track>>>(Load.Idle)
        private set
    var showList by mutableStateOf<Load<List<ShowItem>>>(Load.Idle)
        private set

    private val likedIds = mutableStateMapOf<String, Boolean>()

    // -- search -----------------------------------------------------------

    var searchQuery by mutableStateOf("")
        private set
    var searchKind by mutableStateOf(SearchKind.Tracks)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var search by mutableStateOf<Load<SearchResult>>(Load.Idle)
        private set
    var searchLoadingMore by mutableStateOf(false)
        private set
    private var searchOffset = 0
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    // -- playback mirror --------------------------------------------------

    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var currentLiked by mutableStateOf(false)
    var lyrics by mutableStateOf<Load<Lyrics>>(Load.Idle)
        private set
    var playerTab by mutableStateOf(0)
        private set
    private var lastSeenTrackId: String? = null

    init {
        Player.onUiUpdate = { main.post { refreshPlayer() } }
        Player.onError = { text -> main.post { message = text } }
        // C3: reading every plugin's JS (~1.1 MB of assets) just to classify
        // partial support must not run on the main thread during onCreate.
        scope.launch {
            catalog = withContext(Dispatchers.IO) {
                PluginCatalog.listBundled(app).sortedBy { preferredRank(it.id) }
            }
            // One-time migration: earlier builds defaulted to `netease-music`, whose
            // search/lyrics endpoints are retired. Users never chose it deliberately,
            // so move them once to a working source. Runs only once, so a later
            // explicit switch back is respected.
            val saved = if (!prefs.getBoolean("defaultSourceMigrated", false)) {
                prefs.edit().putBoolean("defaultSourceMigrated", true).apply()
                val previous = prefs.getString("plugin", null)
                if (previous == "netease-music") {
                    prefs.edit().putString("plugin", "netease").apply()
                    "netease"
                } else previous
            } else {
                prefs.getString("plugin", null)
            }
            val initial = catalog.firstOrNull { it.id == saved } ?: catalog.firstOrNull()
            if (initial != null) {
                activate(initial)
            } else {
                // C14: never leave the UI on an endless loading screen.
                pluginError = "没有可用的插件"
            }
        }
    }

    // -- plugin lifecycle -------------------------------------------------

    fun activate(plugin: PluginManifest) {
        if (manifest?.id == plugin.id && runtime != null) {
            sheet = null
            return
        }
        // M18: a new session begins. Invalidate every in-flight coroutine from
        // the old plugin so its late results can't be written into this one.
        generation++
        searchJob?.cancel()
        suggestJob?.cancel()
        dismissSignIn()
        activateJob?.cancel()
        loaderJobs.toList().forEach { it.cancel() }
        loaderJobs.clear()
        likedLoadingMore = false
        showLoadingMore = false
        likedOffset = 0
        showOffsets.clear()
        searchLoadingMore = false
        currentLiked = false
        lastSeenTrackId = null
        pluginReady = false
        pluginError = null
        account = null
        home = Load.Idle
        pages.clear()
        playlistDetails.clear()
        albumDetails.clear()
        artistDetails.clear()
        showDetails.clear()
        library = Load.Idle
        liked = Load.Idle
        recent = Load.Idle
        showList = Load.Idle
        search = Load.Idle
        searchQuery = ""
        suggestions = emptyList()
        likedIds.clear()
        stack = emptyList()
        tab = Tab.Home
        sheet = null
        lyrics = Load.Idle

        val gen = generation
        activateJob = scope.launch {
            var rt: PluginRuntime? = null
            try {
                val source = withContext(Dispatchers.IO) {
                    val code = try {
                        app.assets.open("plugins/${plugin.id}/${plugin.main}").bufferedReader().readText()
                    } catch (e: Exception) {
                        throw IllegalStateException("插件文件缺失：${e.message}")
                    }
                    val locales = runCatching {
                        val text = app.assets.open("plugins/${plugin.id}/locales/zh-CN.json")
                            .bufferedReader().readText()
                        pluginJson.parseToJsonElement(text).jsonObject
                            .mapValues { it.value.jsonPrimitive.content }
                    }.getOrDefault(emptyMap())
                    code to locales
                }
                val fresh = PluginRuntime(app, plugin, source.first, source.second)
                rt = fresh
                fresh.onOpenURL = { url ->
                    // S3: plugins may only open web pages. Forwarding arbitrary
                    // schemes let a plugin launch any installed app's deep link
                    // (tel:, sms:, custom schemes) or a file/content URI.
                    val scheme = runCatching {
                        android.net.Uri.parse(url).scheme?.lowercase()
                    }.getOrNull()
                    if (scheme == "http" || scheme == "https") {
                        main.post {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                app.startActivity(intent)
                            }
                        }
                    }
                }
                fresh.load()
                if (gen != generation) {
                    // A newer switch superseded this load while it was in
                    // flight; drop the runtime instead of installing it.
                    runCatching { fresh.close() }
                    return@launch
                }
                runtime?.close()
                runtime = fresh
                Player.runtime = fresh
                // C2: the old plugin's queue must not be resolved by the new one.
                Player.reset()
                manifest = plugin
                prefs.edit().putString("plugin", plugin.id).apply()
                pluginReady = true
                refreshAccount()
                loadHome()
                loadLibrary()
            } catch (e: CancellationException) {
                if (runtime !== rt) runCatching { rt?.close() }
                throw e
            } catch (e: Exception) {
                if (runtime !== rt) runCatching { rt?.close() }
                if (gen == generation) {
                    android.util.Log.e("MyGoState", "plugin load failed: ${plugin.id}", e)
                    pluginError = e.message ?: "插件加载失败"
                    pluginReady = false
                }
            }
        }
    }

    fun can(name: String): Boolean = runtime?.can(name) == true

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun updateDynamicColor(on: Boolean) {
        dynamicColor = on
        prefs.edit().putBoolean("dynamicColor", on).apply()
    }

    private suspend fun call(name: String, args: String = "{}", timeout: Long = 60_000): String {
        val rt = runtime ?: throw IllegalStateException("插件尚未就绪")
        return withContext(Dispatchers.IO) { rt.call(name, args, timeout) }
    }

    /** Like [call], but keeps the pre-null-strip JSON for pagination counts (M14). */
    private suspend fun callRaw(
        name: String,
        args: String = "{}",
        timeout: Long = 60_000,
    ): PluginRuntime.RawCallResult {
        val rt = runtime ?: throw IllegalStateException("插件尚未就绪")
        return withContext(Dispatchers.IO) { rt.callWithRaw(name, args, timeout) }
    }

    /**
     * Plugins may resolve `null` (signed-out account, missing lyrics, …); the
     * bridge delivers that as the literal string "null", which no model
     * serializer accepts. Treat it as "no result" instead of leaking a raw
     * English decode error into the UI (BUGS.md M16).
     */
    private fun <T> decodeOrNull(json: String, serializer: DeserializationStrategy<T>): T? =
        if (json.trim() == "null") null else pluginJson.decodeFromString(serializer, json)

    private fun errorText(e: Throwable): String = when {
        e is PluginCallException && e.message?.contains("not exported") == true -> "此插件不支持该功能"
        // Some bundled variants still call retired service endpoints and get
        // back "wrong parmas" / "参数错误". That is the plugin being out of date,
        // not the user's input — point them at a working source.
        e is PluginCallException && isStaleEndpointError(e.message) ->
            "该插件的接口已失效（${e.message}），请切换到其他音源"
        e is PluginCallException -> e.message ?: "调用失败"
        // Decode failures used to surface the raw English serializer message
        // (M16); give the user something actionable instead.
        e is SerializationException -> "插件返回的数据无法解析，请重试或切换插件"
        e.message?.contains("Timed out") == true -> "加载超时，请检查网络后重试"
        e.message?.contains("插件已关闭") == true || e is CancellationException -> "插件已切换"
        else -> e.message ?: "出错了"
    }

    private fun isStaleEndpointError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("wrong parmas") || m.contains("wrong params") ||
            m.contains("参数错误") || m.contains("illegal params")
    }

    fun toast(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    // -- account ----------------------------------------------------------

    private fun refreshAccount() {
        if (!can("account")) return
        val gen = generation
        launchLoader {
            val acc = try {
                decodeOrNull(call("account", "{}"), Account.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen == generation) account = acc
        }
    }

    fun signOut() {
        if (!can("signOut")) return
        val gen = generation
        launchLoader {
            runCatching { call("signOut", "{}") }
            if (gen != generation) return@launchLoader
            account = null
            tab = Tab.Home
            stack = emptyList()
            loadHome(force = true)
            toast("已退出登录")
        }
    }

    /** Starts the plugin's sign-in flow, showing whatever QR code it asks for. */
    fun startSignIn() {
        val rt = runtime ?: return
        if (!rt.can("signIn")) {
            toast("此插件不支持在应用内登录")
            return
        }
        if (signIn != null) return
        // L20: the sign-in sheet is its own ModalBottomSheet; leaving the
        // account sheet open stacked two sheets on top of each other.
        sheet = null
        val gen = generation
        signIn = SignInState()
        rt.onSignInPrompt = { qr, text ->
            // L16c: QR encoding is CPU work — do it off the main thread and
            // only publish the finished Bitmap back on main.
            scope.launch {
                val bitmap = qr?.takeIf { it.isNotBlank() }?.let {
                    withContext(Dispatchers.Default) { qrBitmap(it, 720) }
                }
                if (gen != generation) return@launch
                val current = signIn ?: return@launch
                signIn = current.copy(
                    qr = bitmap ?: current.qr,
                    text = text?.takeIf { it.isNotBlank() } ?: current.text,
                )
            }
        }
        signInJob = launchLoader {
            try {
                val acc = decodeOrNull(call("signIn", "{}", 300_000), Account.serializer())
                if (gen != generation) return@launchLoader
                if (acc == null) {
                    if (signIn != null) signIn = SignInState(error = "登录未返回账号信息", working = false)
                    return@launchLoader
                }
                account = acc
                signIn = null
                toast("登录成功：${acc.name}")
                loadHome(force = true)
                loadLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation && signIn != null) {
                    signIn = SignInState(error = errorText(e), working = false)
                }
            } finally {
                rt.onSignInPrompt = null
            }
        }
    }

    fun dismissSignIn() {
        signInJob?.cancel()
        signInJob = null
        runtime?.onSignInPrompt = null
        signIn = null
    }

    // -- navigation -------------------------------------------------------

    fun selectTab(next: Tab) {
        if (tab == next) {
            stack = emptyList()
            return
        }
        tab = next
        stack = emptyList()
        when (next) {
            Tab.Home -> loadHome()
            Tab.Browse -> loadBrowseRoot()
            Tab.Library -> loadLibrary()
            Tab.Search -> Unit
        }
    }

    fun push(route: Route) {
        stack = stack + route
        when (route) {
            is Route.PlaylistDetail -> loadPlaylist(route.id)
            is Route.AlbumDetail -> loadAlbum(route.id)
            is Route.ArtistDetail -> loadArtist(route.id)
            is Route.ShowDetail -> loadShow(route.id, reset = true)
            is Route.Page -> loadPage(route.path)
            Route.Liked -> loadLiked(reset = true)
            Route.Recent -> loadRecent()
            Route.Playlists -> loadLibrary(force = true)
            Route.Shows -> loadShowList()
        }
    }

    fun back(): Boolean {
        if (playerOpen) {
            playerOpen = false
            return true
        }
        if (stack.isNotEmpty()) {
            stack = stack.dropLast(1)
            return true
        }
        if (tab != Tab.Home) {
            selectTab(Tab.Home)
            return true
        }
        return false
    }

    fun openPlaylist(playlist: Playlist) =
        push(Route.PlaylistDetail(playlist.id, playlist.name))

    fun openAlbum(album: Album) =
        push(Route.AlbumDetail(album.id, album.name, album.imageUrl))

    fun openArtist(artist: Artist) =
        push(Route.ArtistDetail(artist.id, artist.name, artist.imageUrl))

    fun openShow(show: ShowItem) =
        push(Route.ShowDetail(show.id, show.name))

    fun openPage(link: LinkItem) =
        push(Route.Page(link.page, link.title))

    fun openPluginSettings(m: PluginManifest) {
        sheet = Sheet.PluginSettings(m)
    }

    fun openPlayer() {
        if (currentTrack != null) {
            playerOpen = true
            playerTab = 0
        }
    }

    fun closePlayer() {
        playerOpen = false
    }

    fun selectPlayerTab(index: Int) {
        playerTab = index
    }

    fun closeSheet() {
        sheet = null
    }

    fun openPlugins() {
        sheet = Sheet.Plugins
    }

    fun openSettings() {
        sheet = Sheet.Settings
    }

    fun openAccount() {
        sheet = Sheet.Account
    }

    fun openQueue() {
        sheet = Sheet.Queue
    }

    // -- home / browse ----------------------------------------------------

    fun loadHome(force: Boolean = false) {
        if (home is Load.Ready && !force) return
        if (!can("home")) {
            home = Load.Ready(BrowsePage())
            return
        }
        val gen = generation
        home = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(decodeOrNull(call("home", "{}"), BrowsePage.serializer()) ?: BrowsePage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) home = next
        }
    }

    /** The Browse tab: the plugin's root browse page, or its home as fallback. */
    private fun loadBrowseRoot() {
        if (!can("browse")) {
            loadHome()
            return
        }
        loadPage("")
    }

    fun loadPage(path: String) {
        if (!can("browse")) {
            pages[path] = Load.Failed("此插件没有浏览功能")
            return
        }
        if (pages[path] is Load.Ready) return
        val gen = generation
        pages[path] = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(
                        call(
                            "browse",
                            // Always send `page` ("" for the root): the contract is
                            // browse({page}) and plugins destructure it and call
                            // page.startsWith(...) — omitting the key crashed the
                            // Kugou/Navidrome browse root with "startsWith of undefined".
                            buildJsonObject { put("page", path) }.toString(),
                        ),
                        BrowsePage.serializer(),
                    ) ?: BrowsePage()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) pages[path] = next
        }
    }

    fun browseRootState(): Load<BrowsePage> = pages[""] ?: Load.Idle

    // -- library ----------------------------------------------------------

    fun loadLibrary(force: Boolean = false) {
        if (!can("playlists")) {
            library = Load.Ready(emptyList())
            return
        }
        if (library is Load.Ready && !force) return
        val gen = generation
        library = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("playlists", "{}"), ListSerializer(Playlist.serializer()))
                        ?: emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) library = next
        }
    }

    fun loadLiked(reset: Boolean = false) {
        if (!can("liked")) {
            liked = Load.Failed("此插件没有喜欢列表")
            return
        }
        if (!reset && likedLoadingMore) return
        val gen = generation
        val existing = liked.readyOrNull()
        val offset = if (reset || existing == null) 0 else likedOffset
        if (reset || existing == null) {
            likedOffset = 0
            liked = Load.Loading
        } else {
            likedLoadingMore = true
        }
        launchLoader {
            try {
                // M14/C5: page by the raw array length, not the null-stripped one.
                val raw = callRaw("liked", buildJsonObject { put("offset", offset) }.toString())
                val page = decodeOrNull(raw.stripped, TrackPage.serializer()) ?: TrackPage()
                val consumed = runCatching {
                    pluginJson.parseToJsonElement(raw.raw).jsonObject["tracks"]?.jsonArray?.size
                }.getOrNull() ?: page.tracks.size
                if (gen == generation) {
                    likedOffset = offset + consumed
                    val base = if (reset) null else liked.readyOrNull()
                    liked = Load.Ready(
                        if (base == null) page else TrackPage(base.tracks + page.tracks, page.more)
                    )
                    refreshLikes(page.tracks.map { it.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    if (liked.readyOrNull() == null) liked = Load.Failed(errorText(e)) else toast(errorText(e))
                }
            } finally {
                if (gen == generation) likedLoadingMore = false
            }
        }
    }

    fun loadRecent() {
        if (!can("recent")) {
            recent = Load.Failed("此插件没有最近播放")
            return
        }
        val gen = generation
        recent = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("recent", "{}"), ListSerializer(Track.serializer())) ?: emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) recent = next
        }
    }

    fun loadShowList() {
        if (!can("shows")) {
            showList = Load.Failed("此插件没有播客功能")
            return
        }
        val gen = generation
        showList = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("shows", "{}"), ListSerializer(ShowItem.serializer())) ?: emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) showList = next
        }
    }

    // -- details ----------------------------------------------------------

    fun loadPlaylist(id: String) {
        if (playlistDetails[id] is Load.Ready) return
        val gen = generation
        playlistDetails[id] = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("playlist", args("id" to id)), Playlist.serializer())
                        ?: Playlist(id = id)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) playlistDetails[id] = next
        }
    }

    fun loadAlbum(id: String) {
        if (albumDetails[id] is Load.Ready) return
        val gen = generation
        albumDetails[id] = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("album", args("id" to id)), AlbumPageResult.serializer())
                        ?: AlbumPageResult(album = Album(id = id))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) albumDetails[id] = next
        }
    }

    fun loadArtist(id: String) {
        if (artistDetails[id] is Load.Ready) return
        val gen = generation
        artistDetails[id] = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("artist", args("id" to id)), ArtistPageResult.serializer())
                        ?: ArtistPageResult(artist = Artist(id = id))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            if (gen == generation) artistDetails[id] = next
        }
    }

    fun loadShow(id: String, reset: Boolean = false) {
        // H7: the old `if (!reset && existing != null) return` guard made the
        // append path unreachable, so 加载更多 did nothing. Only block a second
        // concurrent append now (mirrors loadLiked); the first page still loads
        // when there is nothing cached yet.
        if (!reset && showLoadingMore) return
        val gen = generation
        val existing = showDetails[id].readyOrNull()
        val offset = if (reset || existing == null) 0 else (showOffsets[id] ?: existing.episodes.size)
        if (reset || existing == null) {
            showOffsets[id] = 0
            showDetails[id] = Load.Loading
        } else {
            showLoadingMore = true
        }
        launchLoader {
            try {
                // M14/C5: page by the raw episode count, not the stripped one.
                val raw = callRaw(
                    "show",
                    buildJsonObject { put("id", id); put("offset", offset) }.toString(),
                )
                val page = decodeOrNull(raw.stripped, ShowPageResult.serializer()) ?: ShowPageResult()
                val consumed = runCatching {
                    pluginJson.parseToJsonElement(raw.raw).jsonObject["episodes"]?.jsonArray?.size
                }.getOrNull() ?: page.episodes.size
                if (gen == generation) {
                    showOffsets[id] = offset + consumed
                    val base = if (reset) null else showDetails[id].readyOrNull()
                    showDetails[id] = Load.Ready(
                        if (base == null) page else page.copy(episodes = base.episodes + page.episodes)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    if (showDetails[id].readyOrNull() == null) showDetails[id] = Load.Failed(errorText(e))
                    else toast(errorText(e))
                }
            } finally {
                if (gen == generation) showLoadingMore = false
            }
        }
    }

    // -- search -----------------------------------------------------------

    fun onSearchQuery(text: String) {
        searchQuery = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        if (!can("suggest")) return
        val gen = generation
        suggestJob = scope.launch {
            delay(220)
            val next = runCatching {
                decodeOrNull(call("suggest", args("query" to text)), ListSerializer(String.serializer()))
                    ?: emptyList()
            }.getOrNull()
            if (gen == generation && next != null && searchQuery == text) suggestions = next
        }
    }

    fun changeSearchKind(kind: SearchKind) {
        if (searchKind == kind) return
        searchKind = kind
        runSearch(reset = true)
    }

    fun clearSearch() {
        searchQuery = ""
        suggestions = emptyList()
        search = Load.Idle
        searchOffset = 0
        searchJob?.cancel()
    }

    fun runSearch(reset: Boolean = true) {
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        if (!can("search")) {
            search = Load.Failed("此插件没有搜索功能")
            return
        }
        searchJob?.cancel()
        val gen = generation
        val kind = searchKind
        val offset = if (reset) 0 else searchOffset
        if (reset) search = Load.Loading else searchLoadingMore = true
        searchJob = scope.launch {
            try {
                val raw = callRaw(
                    "search",
                    buildJsonObject {
                        put("query", query)
                        put("kind", kind.api)
                        put("offset", offset)
                    }.toString(),
                )
                val result = decodeOrNull(raw.stripped, SearchResult.serializer()) ?: SearchResult()
                // M14: the bridge strips null placeholders, shrinking the arrays.
                // Paging by the stripped count re-fetches rows on 加载更多, so the
                // consumed offset must come from the RAW (pre-strip) array length.
                val consumed = runCatching {
                    pluginJson.parseToJsonElement(raw.raw).jsonObject[kind.api]?.jsonArray?.size
                }.getOrNull() ?: result.countFor(kind)
                if (gen == generation) {
                    searchOffset = offset + consumed
                    val base = if (reset) null else search.readyOrNull()
                    search = Load.Ready(if (base == null) result else base.merge(result))
                    refreshLikes(result.tracks.map { it.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    if (reset) search = Load.Failed(errorText(e)) else toast(errorText(e))
                }
            } finally {
                if (gen == generation) searchLoadingMore = false
            }
        }
    }

    fun searchMore() {
        if (searchLoadingMore) return
        runSearch(reset = false)
    }

    // -- likes ------------------------------------------------------------

    fun isLiked(id: String): Boolean = likedIds[id] == true

    fun refreshLikes(ids: List<String>) {
        if (ids.isEmpty() || !can("isLiked")) return
        val gen = generation
        launchLoader {
            runCatching {
                val json = call(
                    "isLiked",
                    buildJsonObject {
                        put("ids", buildJsonArray { ids.forEach { add(it) } })
                    }.toString(),
                )
                if (json.trim() == "null") return@runCatching
                val values = pluginJson.parseToJsonElement(json).jsonObject
                if (gen != generation) return@runCatching
                values.forEach { (id, value) ->
                    likedIds[id] = value.jsonPrimitive.content.lowercase() == "true"
                }
                // M17: keep the player heart in sync. Previously only likedIds
                // was written, so currentLiked stayed stale and a tap could
                // accidentally UNLIKE. Update it from the authoritative result
                // whenever the current track is part of the answer.
                currentTrack?.let { track ->
                    values[track.id]?.let { value ->
                        currentLiked = value.jsonPrimitive.content.lowercase() == "true"
                    }
                }
            }
        }
    }

    fun toggleLike(track: Track) {
        if (!can("like")) {
            toast("此插件不支持喜欢")
            return
        }
        val target = !isLiked(track.id)
        val gen = generation
        launchLoader {
            runCatching {
                call("like", args("id" to track.id, "on" to target))
            }.onSuccess {
                if (gen == generation) {
                    likedIds[track.id] = target
                    if (currentTrack?.id == track.id) currentLiked = target
                    toast(if (target) "已添加到喜欢的音乐" else "已取消喜欢")
                }
            }.onFailure {
                if (gen == generation && it !is CancellationException) toast(errorText(it))
            }
        }
    }

    // -- library mutations ------------------------------------------------

    fun saveAlbum(album: Album) {
        if (!can("saveAlbum")) return
        val target = !album.saved
        val gen = generation
        launchLoader {
            runCatching { call("saveAlbum", args("id" to album.id, "on" to target)) }
                .onSuccess {
                    if (gen != generation) return@onSuccess
                    albumDetails.keys.toList().forEach { key ->
                        val page = albumDetails[key]?.readyOrNull() ?: return@forEach
                        if (page.album.id == album.id) {
                            albumDetails[key] = Load.Ready(page.copy(album = page.album.copy(saved = target)))
                        }
                    }
                    toast(if (target) "已收藏专辑" else "已取消收藏")
                }
                .onFailure { if (gen == generation && it !is CancellationException) toast(errorText(it)) }
        }
    }

    fun subscribeShow(show: ShowItem) {
        if (!can("subscribeShow")) return
        val target = !show.subscribed
        val gen = generation
        launchLoader {
            runCatching { call("subscribeShow", args("id" to show.id, "on" to target)) }
                .onSuccess {
                    if (gen != generation) return@onSuccess
                    showDetails.keys.toList().forEach { key ->
                        val page = showDetails[key]?.readyOrNull() ?: return@forEach
                        if (page.show.id == show.id) {
                            showDetails[key] = Load.Ready(page.copy(show = page.show.copy(subscribed = target)))
                        }
                    }
                    toast(if (target) "已订阅" else "已取消订阅")
                }
                .onFailure { if (gen == generation && it !is CancellationException) toast(errorText(it)) }
        }
    }

    fun followArtist(artist: Artist) {
        if (!can("followArtist")) return
        val target = !artist.following
        val gen = generation
        launchLoader {
            runCatching { call("followArtist", args("id" to artist.id, "on" to target)) }
                .onSuccess { if (gen == generation) toast(if (target) "已关注" else "已取消关注") }
                .onFailure { if (gen == generation && it !is CancellationException) toast(errorText(it)) }
        }
    }

    // -- playback ---------------------------------------------------------

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        Player.playQueue(app, tracks, startIndex)
        refreshPlayer()
    }

    fun playTrack(track: Track, context: List<Track> = listOf(track)) {
        val index = context.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playQueue(context, index)
    }

    fun playStation(id: String, name: String) {
        if (!can("stationNext")) return
        val gen = generation
        launchLoader {
            runCatching {
                decodeOrNull(call("stationNext", args("id" to id)), ListSerializer(Track.serializer()))
                    ?: emptyList()
            }.onSuccess { tracks ->
                if (gen == generation) {
                    if (tracks.isEmpty()) toast("电台暂时没有歌")
                    else {
                        playQueue(tracks, 0)
                        toast("正在播放：$name")
                    }
                }
            }.onFailure {
                if (gen == generation && it !is CancellationException) toast(errorText(it))
            }
        }
    }

    fun togglePlay() {
        Player.toggle(app)
        refreshPlayer()
    }

    fun next() {
        Player.next(app)
        refreshPlayer()
    }

    fun previous() {
        Player.previous(app)
        refreshPlayer()
    }

    fun seekTo(ms: Long) {
        Player.seekTo(ms)
        positionMs = ms
    }

    fun playAt(index: Int) {
        Player.playAt(app, index)
        refreshPlayer()
    }

    fun refreshPlayer() {
        val track = Player.current
        currentTrack = track
        isPlaying = Player.isPlaying()
        positionMs = Player.position()
        durationMs = if (Player.duration() > 0) Player.duration() else (track?.duration ?: 0L)
        queue = Player.queue
        if (track?.id != lastSeenTrackId) {
            lastSeenTrackId = track?.id
            currentLiked = track?.let { isLiked(it.id) } ?: false
            lyrics = Load.Idle
            if (track != null) {
                // `played` is reported by Player after a successful stream
                // resolve (actual playback). Reporting it here too double-fired
                // the non-idempotent call and added bridge concurrency.
                refreshLikes(listOf(track.id))
            }
        }
    }

    fun retryLyrics() {
        lyrics = Load.Idle
        loadLyrics()
    }

    /** Loads lyrics for the current track when the player page asks for them. */
    fun loadLyrics() {
        val track = currentTrack ?: return
        if (lyrics is Load.Ready || lyrics is Load.Loading) return
        if (!can("lyrics")) {
            lyrics = Load.Failed("此插件没有歌词")
            return
        }
        val gen = generation
        val trackId = track.id
        lyrics = Load.Loading
        launchLoader {
            val next = try {
                Load.Ready(
                    decodeOrNull(call("lyrics", args("id" to trackId), 15_000), Lyrics.serializer())
                        ?: Lyrics()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
            // H6: only publish if we're still on the same track and session. A
            // slow answer for the previous song must not overwrite the new
            // song's lyrics (or its reset Idle/Loading state).
            if (gen == generation && currentTrack?.id == trackId) lyrics = next
        }
    }

    // -- plugin settings --------------------------------------------------

    fun pluginSettings(m: PluginManifest): Map<String, String> {
        val p = app.getSharedPreferences("plugin-${m.id}", Context.MODE_PRIVATE)
        return m.settings.associate { def -> def.key to (p.getString("setting:${def.key}", "") ?: "") }
    }

    fun savePluginSettings(m: PluginManifest, values: Map<String, String>) {
        val p = app.getSharedPreferences("plugin-${m.id}", Context.MODE_PRIVATE)
        p.edit().apply { values.forEach { (key, value) -> putString("setting:$key", value) } }.apply()
        scope.launch {
            if (manifest?.id == m.id && runtime != null) {
                values.forEach { (key, value) ->
                    runCatching { runtime?.setSetting(key, value) }
                }
                toast("设置已保存")
                loadHome(force = true)
                loadLibrary(force = true)
            } else {
                toast("设置已保存，切换到该插件后生效")
            }
            sheet = null
        }
    }
}

// ------------------------------------------------------- search paging bits

private fun SearchResult.countFor(kind: SearchKind): Int = when (kind) {
    SearchKind.Tracks -> tracks.size
    SearchKind.Albums -> albums.size
    SearchKind.Artists -> artists.size
    SearchKind.Playlists -> playlists.size
    SearchKind.Shows -> shows.size
}

private fun SearchResult.merge(next: SearchResult): SearchResult = SearchResult(
    tracks = (tracks + next.tracks).distinctBy { it.id },
    albums = (albums + next.albums).distinctBy { it.id },
    artists = (artists + next.artists).distinctBy { it.id },
    playlists = (playlists + next.playlists).distinctBy { it.id },
    shows = (shows + next.shows).distinctBy { it.id },
    more = next.more,
)

/** JSON object for a single plugin call argument. */
internal fun args(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
    for ((key, value) in pairs) put(key, value)
}.toString()

private fun JsonObjectBuilder.put(key: String, value: Any?) {
    when (value) {
        null -> put(key, JsonNull)
        is String -> put(key, value)
        is Boolean -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Double -> put(key, value)
        is JsonElement -> put(key, value)
        is List<*> -> put(
            key,
            buildJsonArray { value.forEach { item -> if (item != null) add(item.toString()) } },
        )

        else -> put(key, value.toString())
    }
}

@Suppress("unused")
private fun JsonPrimitive.asBool(): Boolean? =
    when (content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
