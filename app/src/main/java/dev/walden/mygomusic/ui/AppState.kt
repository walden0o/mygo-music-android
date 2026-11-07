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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    /** Plugins that work out of the box come first in the switcher. */
    private fun preferredRank(id: String): Int {
        val order = listOf(
            "netease-music", "kugou", "bilibili", "youtube-music",
            "netease", "netease-cloud-music", "qqmusic", "lizhi",
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
        catalog = PluginCatalog.listBundled(app).sortedBy { preferredRank(it.id) }
        val saved = prefs.getString("plugin", null)
        val initial = catalog.firstOrNull { it.id == saved } ?: catalog.firstOrNull()
        if (initial != null) activate(initial)
    }

    // -- plugin lifecycle -------------------------------------------------

    fun activate(plugin: PluginManifest) {
        if (manifest?.id == plugin.id && runtime != null) {
            sheet = null
            return
        }
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

        scope.launch {
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
                val rt = PluginRuntime(app, plugin, source.first, source.second)
                rt.onOpenURL = { url ->
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
                rt.load()
                runtime?.close()
                runtime = rt
                Player.runtime = rt
                manifest = plugin
                prefs.edit().putString("plugin", plugin.id).apply()
                pluginReady = true
                refreshAccount()
                loadHome()
                loadLibrary()
            } catch (e: Exception) {
                android.util.Log.e("MyGoState", "plugin load failed: ${plugin.id}", e)
                pluginError = e.message ?: "插件加载失败"
                pluginReady = false
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

    private fun errorText(e: Throwable): String = when (e) {
        is PluginCallException -> e.message ?: "调用失败"
        else -> e.message ?: "出错了"
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
        scope.launch {
            account = runCatching {
                pluginJson.decodeFromString(Account.serializer(), call("account", "{}"))
            }.getOrNull()
        }
    }

    fun signOut() {
        if (!can("signOut")) return
        scope.launch {
            runCatching { call("signOut", "{}") }
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
        signIn = SignInState()
        rt.onSignInPrompt = { qr, text ->
            main.post {
                val current = signIn ?: return@post
                signIn = current.copy(
                    qr = qr?.takeIf { it.isNotBlank() }?.let { qrBitmap(it, 720) } ?: current.qr,
                    text = text?.takeIf { it.isNotBlank() } ?: current.text,
                )
            }
        }
        signInJob = scope.launch {
            try {
                val acc = pluginJson.decodeFromString(Account.serializer(), call("signIn", "{}", 300_000))
                account = acc
                signIn = null
                toast("登录成功：${acc.name}")
                loadHome(force = true)
                loadLibrary()
            } catch (e: Exception) {
                if (signIn != null) signIn = SignInState(error = errorText(e), working = false)
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
        home = Load.Loading
        scope.launch {
            home = try {
                Load.Ready(pluginJson.decodeFromString(BrowsePage.serializer(), call("home", "{}")))
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
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
        pages[path] = Load.Loading
        scope.launch {
            pages[path] = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        BrowsePage.serializer(),
                        call(
                            "browse",
                            buildJsonObject { if (path.isNotBlank()) put("page", path) }.toString(),
                        ),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
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
        library = Load.Loading
        scope.launch {
            library = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        ListSerializer(Playlist.serializer()),
                        call("playlists", "{}"),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    fun loadLiked(reset: Boolean = false) {
        if (!can("liked")) {
            liked = Load.Failed("此插件没有喜欢列表")
            return
        }
        val existing = liked.readyOrNull()
        val offset = if (reset || existing == null) 0 else existing.tracks.size
        if (reset || existing == null) liked = Load.Loading else likedLoadingMore = true
        scope.launch {
            try {
                val page = pluginJson.decodeFromString(
                    TrackPage.serializer(),
                    call("liked", buildJsonObject { put("offset", offset) }.toString()),
                )
                val base = if (reset) null else liked.readyOrNull()
                liked = Load.Ready(
                    if (base == null) page else TrackPage(base.tracks + page.tracks, page.more)
                )
                refreshLikes(page.tracks.map { it.id })
            } catch (e: Exception) {
                if (liked.readyOrNull() == null) liked = Load.Failed(errorText(e)) else toast(errorText(e))
            } finally {
                likedLoadingMore = false
            }
        }
    }

    fun loadRecent() {
        if (!can("recent")) {
            recent = Load.Failed("此插件没有最近播放")
            return
        }
        recent = Load.Loading
        scope.launch {
            recent = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        ListSerializer(Track.serializer()),
                        call("recent", "{}"),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    fun loadShowList() {
        if (!can("shows")) {
            showList = Load.Failed("此插件没有播客功能")
            return
        }
        showList = Load.Loading
        scope.launch {
            showList = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        ListSerializer(ShowItem.serializer()),
                        call("shows", "{}"),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    // -- details ----------------------------------------------------------

    fun loadPlaylist(id: String) {
        if (playlistDetails[id] is Load.Ready) return
        playlistDetails[id] = Load.Loading
        scope.launch {
            playlistDetails[id] = try {
                Load.Ready(
                    pluginJson.decodeFromString(Playlist.serializer(), call("playlist", args("id" to id)))
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    fun loadAlbum(id: String) {
        if (albumDetails[id] is Load.Ready) return
        albumDetails[id] = Load.Loading
        scope.launch {
            albumDetails[id] = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        AlbumPageResult.serializer(),
                        call("album", args("id" to id)),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    fun loadArtist(id: String) {
        if (artistDetails[id] is Load.Ready) return
        artistDetails[id] = Load.Loading
        scope.launch {
            artistDetails[id] = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        ArtistPageResult.serializer(),
                        call("artist", args("id" to id)),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
        }
    }

    fun loadShow(id: String, reset: Boolean = false) {
        val existing = showDetails[id].readyOrNull()
        if (!reset && existing != null) return
        val offset = if (reset || existing == null) 0 else existing.episodes.size
        if (reset || existing == null) showDetails[id] = Load.Loading
        scope.launch {
            try {
                val page = pluginJson.decodeFromString(
                    ShowPageResult.serializer(),
                    call("show", buildJsonObject { put("id", id); put("offset", offset) }.toString()),
                )
                val base = if (reset) null else showDetails[id].readyOrNull()
                showDetails[id] = Load.Ready(
                    if (base == null) page else page.copy(episodes = base.episodes + page.episodes)
                )
            } catch (e: Exception) {
                if (showDetails[id].readyOrNull() == null) showDetails[id] = Load.Failed(errorText(e))
                else toast(errorText(e))
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
        suggestJob = scope.launch {
            delay(220)
            runCatching {
                pluginJson.decodeFromString(
                    ListSerializer(String.serializer()),
                    call("suggest", args("query" to text)),
                )
            }.onSuccess { if (searchQuery == text) suggestions = it }
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
        val offset = if (reset) 0 else searchOffset
        if (reset) search = Load.Loading else searchLoadingMore = true
        searchJob = scope.launch {
            try {
                val result = pluginJson.decodeFromString(
                    SearchResult.serializer(),
                    call(
                        "search",
                        buildJsonObject {
                            put("query", query)
                            put("kind", searchKind.api)
                            put("offset", offset)
                        }.toString(),
                    ),
                )
                searchOffset = offset + result.countFor(searchKind)
                val base = if (reset) null else search.readyOrNull()
                search = Load.Ready(if (base == null) result else base.merge(result))
                refreshLikes(result.tracks.map { it.id })
            } catch (e: Exception) {
                if (reset) search = Load.Failed(errorText(e)) else toast(errorText(e))
            } finally {
                searchLoadingMore = false
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
        scope.launch {
            runCatching {
                val json = call(
                    "isLiked",
                    buildJsonObject {
                        put("ids", buildJsonArray { ids.forEach { add(it) } })
                    }.toString(),
                )
                pluginJson.parseToJsonElement(json).jsonObject.forEach { (id, value) ->
                    likedIds[id] = value.jsonPrimitive.content.lowercase() == "true"
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
        scope.launch {
            runCatching {
                call("like", args("id" to track.id, "on" to target))
            }.onSuccess {
                likedIds[track.id] = target
                if (currentTrack?.id == track.id) currentLiked = target
                toast(if (target) "已添加到喜欢的音乐" else "已取消喜欢")
            }.onFailure { toast(errorText(it)) }
        }
    }

    // -- library mutations ------------------------------------------------

    fun saveAlbum(album: Album) {
        if (!can("saveAlbum")) return
        val target = !album.saved
        scope.launch {
            runCatching { call("saveAlbum", args("id" to album.id, "on" to target)) }
                .onSuccess {
                    albumDetails.keys.toList().forEach { key ->
                        val page = albumDetails[key]?.readyOrNull() ?: return@forEach
                        if (page.album.id == album.id) {
                            albumDetails[key] = Load.Ready(page.copy(album = page.album.copy(saved = target)))
                        }
                    }
                    toast(if (target) "已收藏专辑" else "已取消收藏")
                }
                .onFailure { toast(errorText(it)) }
        }
    }

    fun subscribeShow(show: ShowItem) {
        if (!can("subscribeShow")) return
        val target = !show.subscribed
        scope.launch {
            runCatching { call("subscribeShow", args("id" to show.id, "on" to target)) }
                .onSuccess {
                    showDetails.keys.toList().forEach { key ->
                        val page = showDetails[key]?.readyOrNull() ?: return@forEach
                        if (page.show.id == show.id) {
                            showDetails[key] = Load.Ready(page.copy(show = page.show.copy(subscribed = target)))
                        }
                    }
                    toast(if (target) "已订阅" else "已取消订阅")
                }
                .onFailure { toast(errorText(it)) }
        }
    }

    fun followArtist(artist: Artist) {
        if (!can("followArtist")) return
        val target = !artist.following
        scope.launch {
            runCatching { call("followArtist", args("id" to artist.id, "on" to target)) }
                .onSuccess { toast(if (target) "已关注" else "已取消关注") }
                .onFailure { toast(errorText(it)) }
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
        scope.launch {
            runCatching {
                pluginJson.decodeFromString(
                    ListSerializer(Track.serializer()),
                    call("stationNext", args("id" to id)),
                )
            }.onSuccess { tracks ->
                if (tracks.isEmpty()) toast("电台暂时没有歌")
                else {
                    playQueue(tracks, 0)
                    toast("正在播放：$name")
                }
            }.onFailure { toast(errorText(it)) }
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
                if (can("played")) scope.launch { runCatching { call("played", args("id" to track.id)) } }
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
        lyrics = Load.Loading
        scope.launch {
            lyrics = try {
                Load.Ready(
                    pluginJson.decodeFromString(
                        Lyrics.serializer(),
                        call("lyrics", args("id" to track.id), 15_000),
                    )
                )
            } catch (e: Exception) {
                Load.Failed(errorText(e))
            }
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
