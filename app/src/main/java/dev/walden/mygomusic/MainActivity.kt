package dev.walden.mygomusic

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {

    private lateinit var contentFrame: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView
    private lateinit var pluginButton: TextView
    private lateinit var accountButton: TextView
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniBar: LinearLayout

    private var catalog: List<PluginManifest> = emptyList()
    private var runtime: PluginRuntime? = null
    private var manifest: PluginManifest? = null
    private var account: Account? = null

    private data class Page(val title: String, val view: View)
    private val pageStack = mutableListOf<Page>()

    private val ticker = Handler(Looper.getMainLooper())
    private var playerPageState: PlayerPageState? = null

    inner class PlayerPageState(
        val cover: ImageView,
        val title: TextView,
        val artist: TextView,
        val seek: SeekBar,
        val posText: TextView,
        val durText: TextView,
        val playButton: TextView,
        val likeButton: TextView,
        val lyricsList: RecyclerView,
        val lyricsAdapter: LyricsAdapter,
    ) {
        var trackId: String? = null
        var lines: List<LyricLine> = emptyList()
        var currentLine: Int = -1
        var liked: Boolean = false
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshMiniBar()
            playerPageState?.let { updatePlayerPage(it) }
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        catalog = PluginCatalog.listBundled(this)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val activeId = prefs.getString("activePlugin", "netease-cloud-music")
        val initial = catalog.firstOrNull { it.id == activeId } ?: catalog.firstOrNull()
        if (initial == null) {
            toast("没有可用的插件")
            return
        }
        activatePlugin(initial)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ root

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6))
            setBackgroundColor(Ui.SURFACE)
        }
        backButton = Ui.text(this, "‹", 26f).apply {
            setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        toolbar.addView(backButton)
        titleText = Ui.text(this, "MyGo Music", 17f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(titleText)
        pluginButton = Ui.text(this, "插件", 14f, Ui.ACCENT).apply {
            setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6))
            setOnClickListener { showPluginsPage() }
        }
        toolbar.addView(pluginButton)
        val searchButton = Ui.text(this, "搜索", 14f).apply {
            setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6))
            setOnClickListener { showSearchPage() }
        }
        toolbar.addView(searchButton)
        accountButton = Ui.text(this, "登录", 14f).apply {
            setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6))
            setOnClickListener { onAccountTap() }
        }
        toolbar.addView(accountButton)
        root.addView(toolbar)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(contentFrame)

        miniBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 6))
            setBackgroundColor(Ui.SURFACE)
            visibility = View.GONE
            setOnClickListener { showPlayerPage() }
        }
        miniCover = Ui.cover(this, null, 44)
        miniBar.addView(miniCover)
        val miniTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 6), 0)
        }
        miniTitle = Ui.text(this, "", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniSub = Ui.text(this, "", 12f, Ui.SUBTEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniTexts.addView(miniTitle)
        miniTexts.addView(miniSub)
        miniBar.addView(miniTexts)
        miniPlay = Ui.text(this, "▶", 24f).apply {
            setPadding(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 10), 0)
            setOnClickListener { Player.toggle(this@MainActivity) }
        }
        miniBar.addView(miniPlay)
        val miniNext = Ui.text(this, "⏭", 22f).apply {
            setPadding(Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 4), 0)
            setOnClickListener { Player.next(this@MainActivity) }
        }
        miniBar.addView(miniNext)
        root.addView(miniBar)

        Player.onUiUpdate = { runOnUiThread { refreshMiniBar() } }
        Player.onError = { msg -> runOnUiThread { toast(msg) } }
        return root
    }

    // ------------------------------------------------------------ navigation

    private fun showPage(title: String, view: View, root: Boolean = false) {
        if (root) pageStack.clear()
        pageStack.add(Page(title, view))
        renderTop()
    }

    private fun goBack(): Boolean {
        if (pageStack.size <= 1) return false
        pageStack.removeAt(pageStack.size - 1)
        renderTop()
        return true
    }

    private fun renderTop() {
        val page = pageStack.lastOrNull() ?: return
        contentFrame.removeAllViews()
        contentFrame.addView(page.view)
        titleText.text = page.title
        backButton.visibility = if (pageStack.size > 1) View.VISIBLE else View.GONE
        playerPageState = page.view.tag as? PlayerPageState
    }

    // --------------------------------------------------------------- plugins

    private fun activatePlugin(m: PluginManifest) {
        runtime?.close()
        runtime = null
        Player.runtime = null
        manifest = m
        pluginButton.text = m.name
        val src = try {
            assets.open("plugins/${m.id}/${m.main}").bufferedReader().readText()
        } catch (e: Exception) {
            toast("插件文件缺失：${e.message}")
            return
        }
        val locales = try {
            val text = assets.open("plugins/${m.id}/locales/zh-CN.json").bufferedReader().readText()
            pluginJson.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
        val rt = PluginRuntime(this, m, src, locales)
        rt.onOpenURL = { url ->
            runOnUiThread {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        lifecycleScope.launch {
            try {
                rt.load()
                runtime = rt
                Player.runtime = rt
                Player.player(this@MainActivity)
                getSharedPreferences("app", MODE_PRIVATE).edit().putString("activePlugin", m.id).apply()
                refreshAccount()
                showHomePage(root = true)
            } catch (e: Exception) {
                toast("插件加载失败：${e.message}")
            }
        }
    }

    private fun showPluginsPage() {
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        for (m in catalog) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
                setOnClickListener {
                    if (m.id != manifest?.id) activatePlugin(m) else goBack()
                }
            }
            val active = if (m.id == manifest?.id) " ●" else ""
            row.addView(Ui.text(this, "${m.name}$active", 16f, bold = true))
            val note = buildString {
                append("v${m.version}")
                if (m.partial) append(" · 部分支持（${m.partialReasons.joinToString("、")}）")
            }
            row.addView(Ui.text(this, note, 12f, Ui.SUBTEXT))
            list.addView(row)
            if (m.settings.isNotEmpty()) {
                val settingsBtn = Button(this).apply {
                    text = "${m.name} 设置"
                    setOnClickListener { showSettingsPage(m) }
                }
                list.addView(settingsBtn)
            }
        }
        val hint = Ui.text(
            this,
            "部分支持的插件：浏览/搜索可用，播放或登录方式受限（需要桌面端程序或网页登录）。汽水音乐（依赖桌面 Node 进程）与 Apple Music（macOS 专用）未打包。",
            12f, Ui.SUBTEXT,
        ).apply { setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), 0) }
        list.addView(hint)
        showPage("插件", scroll)
    }

    private fun showSettingsPage(m: PluginManifest) {
        val rt = if (m.id == manifest?.id) runtime else null
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
        }
        scroll.addView(form)
        val prefs = getSharedPreferences("plugin-${m.id}", MODE_PRIVATE)
        val editors = mutableListOf<Pair<String, () -> String>>()
        for (def in m.settings) {
            form.addView(Ui.text(this, def.label.ifEmpty { def.key }, 15f, bold = true))
            if (!def.help.isNullOrBlank()) form.addView(Ui.text(this, def.help, 12f, Ui.SUBTEXT))
            val current = prefs.getString("setting:${def.key}", "") ?: ""
            if (def.type == "select" && def.options.isNotEmpty()) {
                val spinner = Spinner(this)
                spinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item,
                    def.options.map { it.second.ifEmpty { it.first } },
                )
                val idx = def.options.indexOfFirst { it.first == current }
                if (idx >= 0) spinner.setSelection(idx)
                form.addView(spinner)
                editors += def.key to { def.options.getOrNull(spinner.selectedItemPosition)?.first ?: "" }
            } else {
                val edit = EditText(this).apply {
                    setText(current)
                    setTextColor(Ui.TEXT)
                    setHintTextColor(Ui.SUBTEXT)
                }
                form.addView(edit)
                editors += def.key to { edit.text.toString() }
            }
        }
        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                lifecycleScope.launch {
                    for ((key, read) in editors) {
                        val value = read()
                        prefs.edit().putString("setting:$key", value).apply()
                        if (rt != null && rt.can("setSetting")) {
                            runCatching {
                                rt.call("setSetting", pluginJson.encodeToString(mapOf("key" to key, "value" to value)))
                            }
                        }
                    }
                    toast("已保存")
                    goBack()
                }
            }
        }
        form.addView(save)
        showPage("${m.name} 设置", scroll)
    }

    // --------------------------------------------------------------- account

    private suspend fun fetchAccount(): Account? {
        val rt = runtime ?: return null
        if (!rt.can("account")) return null
        val result = rt.call("account", "{}")
        if (result == "null") return null
        return pluginJson.decodeFromString(Account.serializer(), result)
    }

    private fun refreshAccount() {
        lifecycleScope.launch {
            account = try { fetchAccount() } catch (e: Exception) { null }
            accountButton.text = account?.name ?: "登录"
        }
    }

    private fun onAccountTap() {
        val rt = runtime ?: return
        val acc = account
        if (acc == null) {
            startLogin()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(acc.name)
            .setItems(arrayOf("我的歌单", "喜欢的音乐", "退出登录")) { _, which ->
                when (which) {
                    0 -> showPlaylistsPage()
                    1 -> showLikedPage()
                    2 -> lifecycleScope.launch {
                        if (rt.can("signOut")) runCatching { rt.call("signOut", "{}") }
                        account = null
                        accountButton.text = "登录"
                        toast("已退出")
                        showHomePage(root = true)
                    }
                }
            }
            .show()
    }

    private fun startLogin() {
        val rt = runtime ?: return
        if (!rt.can("signIn")) {
            toast("此插件不支持在应用内登录")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 16))
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 240), Ui.dp(this@MainActivity, 240))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        box.addView(image)
        val status = Ui.text(this, "正在获取二维码…", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0)
        }
        box.addView(status)
        val dialog = AlertDialog.Builder(this).setView(box).create()
        var loginJob: Job? = null
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }
        box.addView(cancel)
        dialog.setOnDismissListener {
            rt.onSignInPrompt = null
            loginJob?.cancel()
        }
        rt.onSignInPrompt = { qr, text ->
            runOnUiThread {
                if (!qr.isNullOrBlank()) {
                    Ui.qrBitmap(qr, 720)?.let { image.setImageBitmap(it) }
                }
                if (!text.isNullOrBlank()) status.text = text
            }
        }
        dialog.show()
        loginJob = lifecycleScope.launch {
            try {
                val result = rt.call("signIn", "{}", timeoutMs = 300_000)
                val acc = pluginJson.decodeFromString(Account.serializer(), result)
                account = acc
                accountButton.text = acc.name
                toast("登录成功：${acc.name}")
                dialog.dismiss()
                showHomePage(root = true)
            } catch (e: PluginCallException) {
                status.text = e.message ?: "登录失败"
                if (e.code == "setup") toast("插件需要先完成设置")
            } catch (e: Exception) {
                status.text = "登录失败：${e.message}"
            }
        }
    }

    // ------------------------------------------------------------ load helper

    private fun <T> loadOrToast(block: suspend () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            try {
                done(block())
            } catch (e: PluginCallException) {
                when (e.code) {
                    "signIn" -> {
                        toast("需要先登录")
                        startLogin()
                    }
                    "setup" -> toast("请先到插件设置里填写必要信息")
                    "unavailable" -> toast(e.message ?: "不可用")
                    else -> toast(e.message ?: "加载失败")
                }
            } catch (e: Exception) {
                toast("加载失败：${e.message}")
            }
        }
    }

    private fun argsJson(vararg pairs: Pair<String, Any>): String {
        return buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is Boolean -> put(k, v)
                }
            }
        }.toString()
    }

    // ------------------------------------------------------------------ home

    private fun pageScrollContainer(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        return scroll to container
    }

    private fun showHomePage(root: Boolean = false) {
        val rt = runtime ?: return
        val (scroll, container) = pageScrollContainer()
        container.addView(Ui.text(this, "加载中…", 14f, Ui.SUBTEXT).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), 0, 0)
        })
        showPage(manifest?.name ?: "首页", scroll, root)
        if (rt.can("home")) {
            loadOrToast({
                pluginJson.decodeFromString(BrowsePage.serializer(), rt.call("home", "{}"))
            }) { browse ->
                container.removeAllViews()
                renderBrowseInto(container, browse)
            }
        } else {
            container.removeAllViews()
            if (rt.can("playlists")) {
                container.addView(shortcutRow("我的歌单") { showPlaylistsPage() })
            }
            if (rt.can("liked")) {
                container.addView(shortcutRow("喜欢的音乐") { showLikedPage() })
            }
            if (rt.can("recent")) {
                container.addView(shortcutRow("最近播放") { showRecentPage() })
            }
        }
    }

    private fun shortcutRow(label: String, onTap: () -> Unit): View {
        return Ui.text(this, label, 16f).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14))
            setOnClickListener { onTap() }
        }
    }

    private fun renderBrowseInto(container: LinearLayout, browse: BrowsePage) {
        if (!browse.imageUrl.isNullOrBlank()) {
            val img = Ui.cover(this, browse.imageUrl, 120).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 120), Ui.dp(this@MainActivity, 120)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = Ui.dp(this@MainActivity, 16)
                }
            }
            container.addView(img)
        }
        if (!browse.title.isNullOrBlank()) {
            container.addView(Ui.text(this, browse.title, 20f, bold = true).apply {
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), 0)
            })
        }
        if (!browse.subtitle.isNullOrBlank()) {
            container.addView(Ui.text(this, browse.subtitle, 13f, Ui.SUBTEXT).apply {
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 16), 0)
            })
        }
        if (!browse.description.isNullOrBlank()) {
            container.addView(Ui.text(this, browse.description, 13f, Ui.SUBTEXT).apply {
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 16), 0)
            })
        }
        for (section in browse.sections) {
            val isEmpty = section.tracks.isEmpty() && section.playlists.isEmpty() &&
                section.albums.isEmpty() && section.artists.isEmpty() &&
                section.shows.isEmpty() && section.stations.isEmpty() && section.links.isEmpty()
            if (!section.title.isNullOrBlank()) {
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 6))
                }
                header.addView(Ui.text(this, section.title, 16f, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (!section.morePage.isNullOrBlank()) {
                    header.addView(Ui.text(this, "更多 ›", 13f, Ui.SUBTEXT).apply {
                        setOnClickListener { showBrowsePage(section.morePage) }
                    })
                }
                container.addView(header)
            }
            if (!section.subtitle.isNullOrBlank()) {
                container.addView(Ui.text(this, section.subtitle, 12f, Ui.SUBTEXT).apply {
                    setPadding(Ui.dp(this@MainActivity, 16), 0, Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4))
                })
            }
            if (isEmpty) {
                if (!section.empty.isNullOrBlank()) {
                    container.addView(Ui.text(this, section.empty, 13f, Ui.SUBTEXT).apply {
                        setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 16), 0)
                    })
                }
                continue
            }
            if (section.tracks.isNotEmpty()) {
                val tracks = section.tracks
                for ((i, track) in tracks.take(20).withIndex()) {
                    container.addView(Ui.trackRow(this, track) { Player.playQueue(this, tracks, i) })
                }
            }
            if (section.playlists.isNotEmpty()) {
                container.addView(horizontalCards(CardAdapter(
                    section.playlists,
                    { it.name },
                    { it.imageUrl },
                    { if (it.trackCount > 0) "${it.trackCount} 首" else "" },
                    { showPlaylistDetail(it) },
                )))
            }
            if (section.albums.isNotEmpty()) {
                container.addView(horizontalCards(CardAdapter(
                    section.albums,
                    { it.name },
                    { it.imageUrl },
                    { it.artists.joinToString(", ") { a -> a.name } },
                    { showAlbumPage(it.id) },
                )))
            }
            if (section.artists.isNotEmpty()) {
                container.addView(horizontalCards(CardAdapter(
                    section.artists,
                    { it.name },
                    { it.imageUrl },
                    { "" },
                    { showArtistPage(it.id) },
                )))
            }
            if (section.shows.isNotEmpty()) {
                container.addView(horizontalCards(CardAdapter(
                    section.shows,
                    { it.name },
                    { it.imageUrl },
                    { it.publisher ?: "" },
                    { showShowPage(it.id) },
                )))
            }
            for (station in section.stations) {
                container.addView(Ui.trackRow(this, Track(
                    id = station.id, name = station.name,
                    album = Album(name = station.description ?: "", imageUrl = station.imageUrl),
                )) {
                    val rt = runtime ?: return@trackRow
                    if (!rt.can("stationNext")) return@trackRow
                    loadOrToast({
                        pluginJson.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(Track.serializer()),
                            rt.call("stationNext", argsJson("id" to station.id)),
                        )
                    }) { tracks -> if (tracks.isNotEmpty()) Player.playQueue(this, tracks, 0) }
                })
            }
            for (link in section.links) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10))
                    setOnClickListener { showBrowsePage(link.page) }
                }
                row.addView(Ui.text(this, link.title, 15f))
                if (!link.subtitle.isNullOrBlank()) row.addView(Ui.text(this, link.subtitle, 12f, Ui.SUBTEXT))
                container.addView(row)
            }
        }
    }

    private fun horizontalCards(adapter: RecyclerView.Adapter<*>): RecyclerView {
        return RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showBrowsePage(page: String) {
        val rt = runtime ?: return
        if (!rt.can("browse")) {
            toast("此插件没有更多页面")
            return
        }
        val (scroll, container) = pageScrollContainer()
        container.addView(Ui.text(this, "加载中…", 14f, Ui.SUBTEXT).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), 0, 0)
        })
        showPage("…", scroll)
        loadOrToast({
            pluginJson.decodeFromString(BrowsePage.serializer(), rt.call("browse", argsJson("page" to page)))
        }) { browse ->
            container.removeAllViews()
            titleText.text = browse.title ?: page
            pageStack.lastOrNull()?.let { /* title shown via toolbar only */ }
            renderBrowseInto(container, browse)
        }
    }

    // -------------------------------------------------------------- playlists

    private fun trackListPage(title: String): Pair<LinearLayout, RecyclerView> {
        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootLayout.addView(list)
        showPage(title, rootLayout)
        return rootLayout to list
    }

    private fun showPlaylistsPage() {
        val rt = runtime ?: return
        if (!rt.can("playlists")) {
            toast("此插件没有歌单功能")
            return
        }
        val (_, list) = trackListPage("我的歌单")
        loadOrToast({
            pluginJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Playlist.serializer()),
                rt.call("playlists", "{}"),
            )
        }) { playlists ->
            list.adapter = SimpleRowAdapter(
                playlists,
                { it.name },
                { "${it.trackCount} 首" + (it.owner?.let { o -> " · $o" } ?: "") },
                { it.imageUrl },
                { showPlaylistDetail(it) },
            )
        }
    }

    private fun showPlaylistDetail(summary: Playlist) {
        val rt = runtime ?: return
        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
        }
        header.addView(Ui.cover(this, summary.imageUrl, 96))
        val headerTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), 0, 0, 0)
        }
        val nameText = Ui.text(this, summary.name, 17f, bold = true)
        val subText = Ui.text(this, "", 12f, Ui.SUBTEXT)
        headerTexts.addView(nameText)
        headerTexts.addView(subText)
        header.addView(headerTexts)
        rootLayout.addView(header)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(list)
        showPage(summary.name, rootLayout)
        loadOrToast({
            pluginJson.decodeFromString(Playlist.serializer(), rt.call("playlist", argsJson("id" to summary.id)))
        }) { full ->
            nameText.text = full.name
            subText.text = listOf(
                full.owner ?: "",
                "${full.tracks.size} 首",
                full.description ?: "",
            ).filter { it.isNotBlank() }.joinToString("\n")
            list.adapter = TrackAdapter(full.tracks) { i -> Player.playQueue(this, full.tracks, i) }
        }
    }

    private fun showLikedPage() {
        val rt = runtime ?: return
        if (!rt.can("liked")) {
            toast("此插件没有喜欢列表")
            return
        }
        val (_, list) = trackListPage("喜欢的音乐")
        loadOrToast({
            pluginJson.decodeFromString(TrackPage.serializer(), rt.call("liked", argsJson("offset" to 0)))
        }) { page ->
            list.adapter = TrackAdapter(page.tracks) { i -> Player.playQueue(this, page.tracks, i) }
        }
    }

    private fun showRecentPage() {
        val rt = runtime ?: return
        if (!rt.can("recent")) return
        val (_, list) = trackListPage("最近播放")
        loadOrToast({
            pluginJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Track.serializer()),
                rt.call("recent", "{}"),
            )
        }) { tracks ->
            list.adapter = TrackAdapter(tracks) { i -> Player.playQueue(this, tracks, i) }
        }
    }

    private fun showAlbumPage(id: String) {
        val rt = runtime ?: return
        if (!rt.can("album")) return
        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = Ui.text(this, "加载中…", 16f, bold = true).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
        }
        rootLayout.addView(header)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(list)
        showPage("专辑", rootLayout)
        loadOrToast({
            pluginJson.decodeFromString(AlbumPageResult.serializer(), rt.call("album", argsJson("id" to id)))
        }) { page ->
            header.text = "${page.album.name}\n${page.album.artists.joinToString(", ") { it.name }}"
            titleText.text = page.album.name
            list.adapter = TrackAdapter(page.tracks) { i -> Player.playQueue(this, page.tracks, i) }
        }
    }

    private fun showArtistPage(id: String) {
        val rt = runtime ?: return
        if (!rt.can("artist")) return
        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = Ui.text(this, "加载中…", 16f, bold = true).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
        }
        rootLayout.addView(header)
        val albumsView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        rootLayout.addView(albumsView)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(list)
        showPage("歌手", rootLayout)
        loadOrToast({
            pluginJson.decodeFromString(ArtistPageResult.serializer(), rt.call("artist", argsJson("id" to id)))
        }) { page ->
            header.text = page.artist.name
            titleText.text = page.artist.name
            albumsView.adapter = CardAdapter(
                page.albums, { it.name }, { it.imageUrl }, { it.released ?: "" },
                { showAlbumPage(it.id) },
            )
            list.adapter = TrackAdapter(page.tracks) { i -> Player.playQueue(this, page.tracks, i) }
        }
    }

    private fun showShowPage(id: String) {
        val rt = runtime ?: return
        if (!rt.can("show")) return
        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = Ui.text(this, "加载中…", 16f, bold = true).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
        }
        rootLayout.addView(header)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(list)
        showPage("播客", rootLayout)
        loadOrToast({
            pluginJson.decodeFromString(
                ShowPageResult.serializer(),
                rt.call("show", argsJson("id" to id, "offset" to 0)),
            )
        }) { page ->
            header.text = page.show.name
            titleText.text = page.show.name
            list.adapter = TrackAdapter(page.episodes) { i -> Player.playQueue(this, page.episodes, i) }
        }
    }

    // ---------------------------------------------------------------- search

    private fun showSearchPage() {
        val rt = runtime ?: return
        if (!rt.can("search")) {
            toast("此插件没有搜索功能")
            return
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12), 0)
        }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "搜索歌曲、歌单、专辑…"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.SUBTEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
        }
        inputRow.addView(input)
        val go = Button(this).apply { text = "搜索" }
        inputRow.addView(go)
        rootLayout.addView(inputRow)

        val kinds = listOf(
            "tracks" to "单曲", "playlists" to "歌单", "albums" to "专辑",
            "artists" to "歌手", "shows" to "播客",
        )
        var currentKind = "tracks"
        val results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        fun runSearch() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            val kind = currentKind
            loadOrToast({
                pluginJson.decodeFromString(
                    SearchResult.serializer(),
                    rt.call("search", argsJson("query" to q, "kind" to kind, "offset" to 0)),
                )
            }) { result ->
                when (kind) {
                    "tracks" -> results.adapter = TrackAdapter(result.tracks) { i ->
                        Player.playQueue(this, result.tracks, i)
                    }
                    "playlists" -> results.adapter = SimpleRowAdapter(
                        result.playlists, { it.name },
                        { "${it.trackCount} 首" }, { it.imageUrl },
                        { showPlaylistDetail(it) },
                    )
                    "albums" -> results.adapter = SimpleRowAdapter(
                        result.albums, { it.name },
                        { it.artists.joinToString(", ") { a -> a.name } }, { it.imageUrl },
                        { showAlbumPage(it.id) },
                    )
                    "artists" -> results.adapter = SimpleRowAdapter(
                        result.artists, { it.name }, { "" }, { it.imageUrl },
                        { showArtistPage(it.id) },
                    )
                    "shows" -> results.adapter = SimpleRowAdapter(
                        result.shows, { it.name }, { it.publisher ?: "" }, { it.imageUrl },
                        { showShowPage(it.id) },
                    )
                }
                val count = result.tracks.size + result.albums.size + result.artists.size +
                    result.playlists.size + result.shows.size
                if (count == 0) toast("没有找到结果")
            }
        }
        for ((kind, label) in kinds) {
            val chip = Ui.text(this, label, 14f, if (kind == currentKind) Ui.ACCENT else Ui.SUBTEXT).apply {
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8))
                setOnClickListener {
                    currentKind = kind
                    chipViews.forEach { it.setTextColor(Ui.SUBTEXT) }
                    setTextColor(Ui.ACCENT)
                    runSearch()
                }
            }
            chipViews.add(chip)
            chips.addView(chip)
        }
        rootLayout.addView(chips)
        rootLayout.addView(results)
        go.setOnClickListener { runSearch() }
        showPage("搜索", rootLayout)
    }

    // ---------------------------------------------------------------- player

    private fun refreshMiniBar() {
        val track = Player.current
        if (track == null) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = track.name
        miniSub.text = track.artistNames()
        miniPlay.text = if (Player.isPlaying()) "⏸" else "▶"
        val url = track.album.imageUrl
        if (miniCover.tag != url) {
            miniCover.tag = url
            miniCover.setImageDrawable(null)
            if (!url.isNullOrBlank()) miniCover.load(url)
        }
    }

    private fun showPlayerPage() {
        val track = Player.current
        if (track == null) {
            toast("先选一首歌播放")
            return
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8))
        }
        val cover = Ui.cover(this, track.album.imageUrl, 240).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 240), Ui.dp(this@MainActivity, 240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        rootLayout.addView(cover)
        val title = Ui.text(this, track.name, 19f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@MainActivity, 16), 0, 0)
        }
        rootLayout.addView(title)
        val artist = Ui.text(this, track.artistNames(), 14f, Ui.SUBTEXT).apply { gravity = Gravity.CENTER }
        rootLayout.addView(artist)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
        }
        val posText = Ui.text(this, "0:00", 12f, Ui.SUBTEXT)
        val durText = Ui.text(this, "0:00", 12f, Ui.SUBTEXT)
        val seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        seekRow.addView(posText)
        seekRow.addView(seek)
        seekRow.addView(durText)
        rootLayout.addView(seekRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = Ui.text(this, "⏮", 30f).apply {
            setPadding(Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 6))
            setOnClickListener { Player.previous(this@MainActivity) }
        }
        val play = Ui.text(this, "⏸", 40f).apply {
            setPadding(Ui.dp(this@MainActivity, 22), 0, Ui.dp(this@MainActivity, 22), 0)
            setOnClickListener { Player.toggle(this@MainActivity) }
        }
        val next = Ui.text(this, "⏭", 30f).apply {
            setPadding(Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 22), Ui.dp(this@MainActivity, 6))
            setOnClickListener { Player.next(this@MainActivity) }
        }
        val like = Ui.text(this, "♡", 26f).apply {
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6))
        }
        controls.addView(prev)
        controls.addView(play)
        controls.addView(next)
        controls.addView(like)
        rootLayout.addView(controls)

        val lyricsAdapter = LyricsAdapter()
        val lyricsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = lyricsAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootLayout.addView(lyricsList)

        val state = PlayerPageState(cover, title, artist, seek, posText, durText, play, like, lyricsList, lyricsAdapter)
        rootLayout.tag = state
        var dragging = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) posText.text = Ui.formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                Player.seekTo(bar.progress.toLong())
            }
        })
        state.trackId = null
        like.setOnClickListener {
            val rt = runtime ?: return@setOnClickListener
            val current = Player.current ?: return@setOnClickListener
            if (!rt.can("like")) {
                toast("此插件不支持喜欢")
                return@setOnClickListener
            }
            val target = !state.liked
            loadOrToast({
                rt.call("like", argsJson("id" to current.id, "on" to target))
                target
            }) { nowLiked ->
                state.liked = nowLiked
                like.text = if (nowLiked) "♥" else "♡"
                like.setTextColor(if (nowLiked) Ui.ACCENT else Ui.TEXT)
            }
        }
        showPage("正在播放", rootLayout)
        updatePlayerPage(state)
    }

    private fun updatePlayerPage(state: PlayerPageState) {
        val track = Player.current ?: return
        if (state.trackId != track.id) {
            state.trackId = track.id
            state.cover.setImageDrawable(null)
            if (!track.album.imageUrl.isNullOrBlank()) state.cover.load(track.album.imageUrl)
            state.title.text = track.name
            state.artist.text = track.artistNames()
            state.currentLine = -1
            state.lines = emptyList()
            state.lyricsAdapter.submit(emptyList(), -1)
            loadLyricsFor(state, track)
            val rt = runtime
            if (rt != null && rt.can("isLiked")) {
                lifecycleScope.launch {
                    runCatching {
                        val result = rt.call("isLiked", buildJsonObject {
                            put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(track.id))))
                        }.toString())
                        val map = pluginJson.parseToJsonElement(result).jsonObject
                        state.liked = map[track.id]?.jsonPrimitive?.content == "true"
                        state.likeButton.text = if (state.liked) "♥" else "♡"
                        state.likeButton.setTextColor(if (state.liked) Ui.ACCENT else Ui.TEXT)
                    }
                }
            }
        }
        state.playButton.text = if (Player.isPlaying()) "⏸" else "▶"
        val pos = Player.position()
        val dur = Player.duration().takeIf { it > 0 } ?: track.duration
        if (dur > 0) {
            state.seek.max = dur.toInt()
            state.seek.progress = pos.toInt().coerceAtMost(dur.toInt())
        }
        state.posText.text = Ui.formatTime(pos)
        state.durText.text = Ui.formatTime(dur)
        if (state.lines.isNotEmpty()) {
            val idx = state.lines.indexOfLast { it.start <= pos }
            if (idx >= 0 && idx != state.currentLine) {
                val old = state.currentLine
                state.currentLine = idx
                state.lyricsAdapter.setHighlight(idx, old)
                state.lyricsList.smoothScrollToPosition(idx)
            }
        }
    }

    private fun loadLyricsFor(state: PlayerPageState, track: Track) {
        val rt = runtime ?: return
        if (!rt.can("lyrics")) {
            state.lines = listOf(LyricLine(0, 0, "此插件没有歌词"))
            state.lyricsAdapter.submit(state.lines, -1)
            return
        }
        lifecycleScope.launch {
            runCatching {
                val result = rt.call("lyrics", argsJson("id" to track.id))
                val lyrics = pluginJson.decodeFromString(Lyrics.serializer(), result)
                val lines = if (lyrics.lines.isNotEmpty()) lyrics.lines
                    else parseLrc(lyrics.lrc, lyrics.translation)
                if (state.trackId == track.id) {
                    state.lines = lines.ifEmpty { listOf(LyricLine(0, 0, "暂无歌词")) }
                    state.lyricsAdapter.submit(state.lines, -1)
                }
            }.onFailure {
                if (state.trackId == track.id) {
                    state.lines = listOf(LyricLine(0, 0, "歌词加载失败"))
                    state.lyricsAdapter.submit(state.lines, -1)
                }
            }
        }
    }

    private fun parseLrc(lrc: String?, translation: String?): List<LyricLine> {
        fun parse(src: String?): Map<Long, String> {
            if (src.isNullOrBlank()) return emptyMap()
            val tag = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
            val out = mutableMapOf<Long, String>()
            for (line in src.lines()) {
                val matches = tag.findAll(line).toList()
                if (matches.isEmpty()) continue
                val text = line.substring(matches.last().range.last + 1).trim()
                for (m in matches) {
                    val start = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toDouble()) * 1000
                    out[start.toLong()] = text
                }
            }
            return out
        }
        val main = parse(lrc)
        if (main.isEmpty()) return emptyList()
        val trans = parse(translation)
        return main.entries.sortedBy { it.key }.map { (start, text) ->
            val tr = trans[start] ?: trans.entries
                .minByOrNull { kotlin.math.abs(it.key - start) }
                ?.takeIf { kotlin.math.abs(it.key - start) < 600 }
                ?.value
            LyricLine(start = start, text = text, translation = tr)
        }
    }
}

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.Holder>() {
    private var lines: List<LyricLine> = emptyList()
    private var highlight: Int = -1

    class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val main: TextView = box.getChildAt(0) as TextView
        val sub: TextView = box.getChildAt(1) as TextView
    }

    fun submit(newLines: List<LyricLine>, newHighlight: Int) {
        lines = newLines
        highlight = newHighlight
        notifyDataSetChanged()
    }

    fun setHighlight(newHighlight: Int, oldHighlight: Int) {
        highlight = newHighlight
        if (oldHighlight in lines.indices) notifyItemChanged(oldHighlight)
        if (newHighlight in lines.indices) notifyItemChanged(newHighlight)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(Ui.text(context, "", 15f).apply { gravity = Gravity.CENTER })
        box.addView(Ui.text(context, "", 12f, Ui.SUBTEXT).apply { gravity = Gravity.CENTER })
        return Holder(box)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        val active = position == highlight
        holder.main.text = line.displayText()
        holder.main.setTextColor(if (active) Ui.ACCENT else Ui.TEXT)
        holder.main.setTypeface(holder.main.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        holder.sub.text = line.translation ?: ""
        holder.sub.visibility = if (line.translation.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = lines.size
}
