# Bug 分析报告 — MyGo Music for Android

审计时间：2026-11-07 · 对象：commit `0a06801` · 共 **27 条**（1 CRITICAL / 6 HIGH / 14 MEDIUM / 6 LOW）

> ## ✅ 修复状态（2026-11-08 更新）
> **26 条已修复并验证，1 条部分修复（H3 的队列计数上报）**。详见文末「修复实施与验证」。
> 修复过程中另外**捕获并修掉了 3 个由修复本身引入的回归**（见该节），这也是必须做实机回归的原因。

> 已并入两份外部审计并逐条复核：
> ① 第三方全面功能测试报告 → 新增 M16 / L17 / L18 / L19，并为 C1 补入两个更严重的实证症状（**自然连播永久停摆**、null 串线解码错）；
> ② Compose UI / 状态层专项审计 → 新增 H6 / H7 / M17 / M18 / M19 / L20 / L21。
> 详见文末「交叉核对」。

**方法**：全量源码阅读 + 独立最小复现 + 模拟器实机取证（API 37）+ `dumpsys` / 合并后 manifest / `aapt2` / Media3 AAR 字节码核对。
**验证状态**：`已实证` = 有可运行复现或设备取证；`代码确证` = 代码路径明确、未在设备上触发。

| # | 级别 | 问题 | 验证 |
|---|---|---|---|
| C1 | 🔴 | 插件调用共用 `pending` 槽位 → 结果串台 + 60/90s 卡死 | 已实证 |
| H2 | 🟠 | `playAt` 不取消旧请求 → 播的和显示的不是同一首 | 代码确证 |
| H3 | 🟠 | 通知栏/锁屏/蓝牙 上下首失效（session 队列只有 1 项） | 已实证 |
| H4 | 🟠 | 无音频焦点管理 → 来电不暂停、压过其他 App | 代码确证 |
| H5 | 🟠 | **标准 LRC 歌词被整段丢弃** → 永远「暂无歌词」 | 已实证 |
| H6 | 🟠 | **切歌后歌词永不重载** → 歌词页永久转圈 | 代码确证 |
| H7 | 🟠 | 播客「加载更多」按钮是死的（早退守卫挡住追加） | 代码确证 |
| M6 | 🟡 | `close()` 不取消在途调用，引擎可能在 evaluate 中被关 | 代码确证 |
| M7 | 🟡 | `serviceStarted` 永不复位 → 服务回收后不再前台化 | 代码确证 |
| M8 | 🟡 | Android 8–11 耳机/蓝牙媒体键无接收者 | 已实证 |
| M9 | 🟡 | 无 wake mode / WAKE_LOCK → 息屏流播可能卡住 | 代码确证 |
| M10 | 🟡 | 单曲出错整条队列停摆（不跳下一首） | 代码确证 |
| M11 | 🟡 | 队列播完 / 出错后 ▶ 按钮点了没反应 | 代码确证 |
| M12 | 🟡 | JSON 歌词反转义顺序错 + `\uXXXX` 不解码 | 已实证 |
| M13 | 🟡 | `allowBackup` 默认开 → 登录凭证被云备份 | 已实证 |
| M14 | 🟡 | 分页 offset 漂移 → 加载更多重复 | 代码确证 |
| M15 | 🟡 | 失败重试重复执行非幂等调用（点赞/建歌单） | 代码确证 |
| M16 | 🟡 | 插件返回 null → 抛英文序列化错误而非空态 | 已实证 |
| M17 | 🟡 | 播放器爱心状态不同步 → 可能误「取消喜欢」 | 代码确证 |
| M18 | 🟡 | 切插件不取消在途加载 → 旧插件结果写进新会话 | 代码确证 |
| M19 | 🟡 | LazyColumn key 用 `section.hashCode()` → 重复分区崩溃（潜在） | 代码确证 |
| L16 | ⚪ | 通知权限被拒后无补救入口；通知点击叠 Activity；QR 主线程；release 未签名 | 代码确证 |
| L17 | ⚪ | `_log` 是 no-op → 插件 console 全部静默丢弃 | 已实证 |
| L18 | ⚪ | harness 硬编码他人机器路径（3 处），本机跑不起来 | 已实证 |
| L19 | ⚪ | 5 处 AutoMirrored 图标弃用警告 → RTL 不镜像 | 已实证 |
| L20 | ⚪ | 登录时两层 ModalBottomSheet 叠加 | 代码确证 |
| L21 | ⚪ | 播放轮询空转：空闲/暂停也每 500ms 跑一次 | 代码确证 |

---

## 🔴 CRITICAL

### C1. 插件调用共用一个 `pending` 槽位 → 结果串台 + 卡死
**已实证**（独立复现 5/5 轮 + 实机截图）

`PluginRuntime.kt:335-336`
```kotlin
val deferred = CompletableDeferred<String>()
pending = deferred          // ← 单槽位，被每个并发调用覆盖
```
`PluginRuntime.kt:271` — 交付端不做身份匹配：
```kotlin
engine.function("_deliver") { a -> pending?.complete(a[0] as String); null }
```
`prelude.js:261-276` 的 `__invoke` 是 fire-and-forget，回传**不带调用 id**。
`AppState.kt:360-362` 又把每次调用丢进 IO 线程池，全项目 **20+ 处 `scope.launch { … call(...) }`** 完全并发。

**最易命中：点一首歌，同一 runtime 上同时飞 4 个网络调用**
- `Player.kt:110` → `stream`
- `Player.kt:132` → `played`
- `AppState.kt:988` → `played`（**重复上报，第二次**）
- `refreshPlayer` → `refreshLikes` → `isLiked`

先返回的 payload 被交给「最后覆盖 pending 的调用者」→ `stream` 拿到 `isLiked` 的 JSON，真正该收结果的调用永远等不到。
（字段声明在 `PluginRuntime.kt:183`，赋值点在 `:336`。）

**最严重的变体：自然播完的自动连播 → 永久停摆，必须强杀**（第三方实测，机制已复核）
```kotlin
// Player.kt:59-62
if (state == Player.STATE_ENDED) {
    if (index + 1 < queue.size) playAt(app, index + 1)   // → rt.call("stream")
}
onUiUpdate?.invoke()                                     // → refreshPlayer → played + isLiked
```
```kotlin
// Player.kt:97-102  先推进 UI，再去解析 stream
index = at; current = track
onUiUpdate?.invoke()      // ← UI 立刻显示「已切到下一首」
scope.launch { rt.call("stream", ...) }   // ← 这一路被串台，永远拿不到 url
```
三路并发命中 C1 → `stream` 挂死 → ExoPlayer 停在 `STATE_ENDED`。
因为状态不再变化，`onPlaybackStateChanged` **不会再次触发**，自动连播彻底断链；
再叠加 M11（ENDED 时 `play()` 是 no-op），点 ▶ 也救不回来 → **只能强杀重启**。
手动切歌恰好撞上自动连播时同样停摆。

**另一实证症状**：`account()` 的 `null` 串线进 `home()` → 报 `JSON input: null` 解码错误（酷狗/网易云均复现，另见 M16）。
第三方统计激活插件后首页约 **50% 概率**超时至 60s（重试必成功，因单发调用无重叠）——该比例来自其测试，我方未独立统计。

**实机取证**（`screenshots/BUG-race-timeout.png`）：
```
Timed out waiting for 60000 ms            ← 页面加载（AppState 默认 60s）
无法播放：Timed out waiting for 90000 ms    ← stream（PluginRuntime 默认 90s）
```
紧接着**单独重试立刻成功加载真实数据 → 排除网络原因**。

**独立最小复现**（`/tmp/bugrepro`，照抄 `call()` + `__invoke`/`_deliver`）：
```
call(account)   -> TIMEOUT
call(home)      -> TIMEOUT
call(playlists) -> got "account"   <== WRONG PAYLOAD
=== 5 rounds x 3 calls: 5 wrong payloads, 10 timeouts ===
```

> **为什么冷启动常「看起来正常」**：未登录时 `account()` 读本地、`playlists()` 直接抛 signIn，
> 两者在同一轮 evaluate 内交付完毕，只有 `home()` 是真异步 → 恰好不重叠。
> 一旦登录 / 播放 / 搜索，重叠必然出现。这解释了此前「进度一直 0:00、歌词卡住、页面转圈、首页偶发空白」。

**修复**：每次调用生成 id，JS 回传该 id，Kotlin 用 `ConcurrentHashMap<String, CompletableDeferred>` 按 id 派发；
或先用 `Mutex` 串行化整个 `call()`。同时删掉 `AppState.kt:988` 的重复 `played`。

---

## 🟠 HIGH

### H2. `playAt` 不取消旧请求 → 慢返回的上一首覆盖当前播放
**代码确证** · `Player.kt:103-128`
```kotlin
scope.launch {
    val result = rt.call("stream", args)   // 无 job 取消、无 index 校验
    httpFactory.setDefaultRequestProperties(stream.headers)
    p.setMediaItem(item)                   // 无条件写入
```
连点「下一首」：A 慢 B 快，A 后到就把播放器切成 A，而 `current`/UI 仍是 B → **听到的和显示的不是同一首**。
`httpFactory` 是全局单例，A 的鉴权头还会污染 B 的请求。
**修复**：存 `Job` 并在 `playAt` 开头 cancel；resolve 后校验 `index == at && current?.id == track.id`；headers 改 per-item DataSource。

### H3. 通知栏/锁屏/蓝牙「上一首·下一首」失效
**已实证**（`dumpsys media_session`） · `Player.kt:126` 只塞一个 item
```
queueTitle=null, size=1        ← 应用队列实际 187 首
actions=7339999                ← 含 skip next/prev，按钮可见但按了没用
```
**修复**：`setMediaItems` 映射整个队列（URL 占位 + ResolvingDataSource），或给 MediaSession 加自定义 next/prev 命令转发到 `Player.next/previous`。

### H4. 完全没有音频焦点管理
**代码确证** · `Player.kt:52-56`
```kotlin
ExoPlayer.Builder(app).setMediaSourceFactory(...).build()   // 从不 setAudioAttributes
```
Media3 默认 `handleAudioFocus = false`。来电 / 导航语音 / 其他播放器启动时**不暂停不降音，直接压过去**；
拔耳机也不暂停（无 `setHandleAudioBecomingNoisy`）。
**修复**：
```kotlin
.setAudioAttributes(AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true)
.setHandleAudioBecomingNoisy(true)
```

### H5. 标准 LRC 歌词被整段丢弃 → 永远「这首歌暂无歌词」
**已实证**（可运行测试） · `util/LyricText.kt:17-36`
```kotlin
if (raw.startsWith("{") || raw.startsWith("[")) {   // ← 所有 LRC 都以 '[' 开头！
    ... 找 "tx" / "text" 键 ...
    return emptyList()                              // :31  找不到就返回空
}
return raw.lineSequence()...                        // :33-36  LRC 兜底永远走不到
```
可达性已确认：`assets/plugins/netease/index.js:507` 在没有 YRC 时返回
`{ lrc: body.lrc?.lyric || "", translation }`（**无 lines**）——这是常见情况。

测试结果：
```
standard LRC enters JSON branch? true   <-- should be false
  -> no "tx"/"text" keys => emptyList() => UI shows 这首歌暂无歌词
LRC with metadata enters JSON branch? true
```
**修复**：JSON 分支只判 `startsWith("{")`；纯文本路径同时剥掉 `[ti:]` `[ar:]` `[offset:]` 等元信息标签。

### H6. 切歌后歌词永不重载 → 歌词页永久转圈
**代码确证** · `ui/screens/PlayerScreen.kt:77` + `ui/AppState.kt:986, 1000-1020`
```kotlin
// PlayerScreen.kt:77  只按 tab 触发，切歌不会重跑
LaunchedEffect(tab) { if (tab == 1) state.loadLyrics() }
```
```kotlin
// AppState.kt:983-986  切歌时把歌词重置为 Idle，却从不重新加载
if (track?.id != lastSeenTrackId) {
    lastSeenTrackId = track?.id
    lyrics = Load.Idle        // ← 重置后再没有人调用 loadLyrics()
```
**触发**：打开播放器 → 点「歌词」→ 让歌曲自然连播或手动按下一首（歌词页保持选中）。
**后果**：`lyrics` 停在 `Idle` → `LyricsView` 走 `else` 分支（`PlayerScreen.kt:428-444`）
→ **此后每一首都永久显示「正在加载歌词…」**。
更糟：`loadLyrics()`（`:1008-1018`）无条件写回结果，旧歌的慢请求可能在新歌重置后落地
→ 显示**上一首的歌词**；而 `:1002` 的 `if (lyrics is Load.Ready) return` 守卫又会挡住新歌歌词，来回切 tab 也救不回来。

> ⚠️ 这是「歌词卡住」的**第三个独立原因**（另两个是 C1 串台、H5 纯 LRC 被丢弃）。三者叠加，歌词链路基本不可用。

**修复**：`LaunchedEffect(tab, track.id)`；`loadLyrics` 发布结果前校验 `currentTrack?.id == track.id`。

### H7. 播客「加载更多」按钮是死的
**代码确证** · `ui/AppState.kt:737-740` + `ui/screens/DetailScreens.kt:337`
```kotlin
fun loadShow(id: String, reset: Boolean = false) {
    val existing = showDetails[id].readyOrNull()
    if (!reset && existing != null) return      // ← 追加分支被这行挡死
    val offset = if (reset || existing == null) 0 else existing.episodes.size
```
```kotlin
TextButton(onClick = { state.loadShow(route.id) }) { Text("加载更多") }   // reset 默认 false
```
**触发**：打开一个首页 `more = true` 的播客 → 点「加载更多」。
**后果**：已有 `Ready` 页 + `reset=false` → 早退，什么都不发生。
全项目 `reset=false` **只有这一个调用点**（其余 4 处都传 `reset=true`），
所以 `:740` 的 offset 与 `:748-751` 的追加逻辑**永远不可达** → 第一页之后的剧集永远看不到。
对照：`loadLiked`（`:620-645`）没有这个守卫，分页正常工作，反证守卫就是缺陷。
**修复**：去掉/放宽该早退守卫，让追加路径跑起来；去重另用 loading-more 标志。

---

## 🟡 MEDIUM

### M6. `close()` 不取消在途调用，引擎可能在 evaluate 中被关
**代码确证** · `PluginRuntime.kt:369-372`
```kotlin
fun close() { scope.cancel(); runCatching { engine.close() } }
```
`scope` 是 PluginRuntime 自己的、**实际从未使用**（真正工作跑在 `AppState.scope` / `Player.scope`）。
`AppState.kt:331` 切插件时 `runtime?.close()`，此时别的协程可能正在 `engine.evaluate` 里 → 原生 use-after-free 风险；
`pending` 从不 complete，在途调用只能等超时。**未能在设备上触发崩溃**，按风险计。
**修复**：close 时把所有在途 deferred 以 CancellationException 完成；确保无 evaluate 在飞再 close；同步清 `Player.runtime`。

### M7. `serviceStarted` 永不复位
**代码确证** · `Player.kt:32,78-84`：一旦 true 不再变。用户划掉通知 / 系统回收 Service 后
`ensureService()` 直接 return → 后续播放没有前台服务 → 后台被杀。
**修复**：`PlaybackService.onDestroy()` 里复位。

### M8. Android 8–11 耳机/蓝牙媒体键完全无接收者
**已实证**（合并后 manifest） · `AndroidManifest.xml:23-31`
```xml
<service android:name=".PlaybackService" ...>
    <intent-filter>
        <action android:name="android.media.session.MediaButtonReceiver" />  <!-- service 收不到广播 -->
```
合并 manifest 里唯一的 `<receiver>` 是 `ProfileInstallReceiver`，**没有任何 MEDIA_BUTTON 接收者**。
API 31 以下媒体键以广播送达 → 无人接收 → 按键无效（minSdk 26，影响 8–11）。
**修复**：声明 `androidx.media3.session.MediaButtonReceiver` + `android.intent.action.MEDIA_BUTTON` filter；删掉 service 上那个假 action。

### M9. 无 wake mode / WAKE_LOCK
**代码确证** · `Player.kt:52-56` 无 `setWakeMode`，manifest 无 `WAKE_LOCK` → 默认 `WAKE_MODE_NONE`。
息屏 + 重新缓冲时 audio HAL wakelock 释放 → CPU 休眠 → 播放卡住直到亮屏（设备相关）。
**修复**：`setWakeMode(C.WAKE_MODE_NETWORK)` + 声明 `WAKE_LOCK`。

### M10. 单曲出错整条队列停摆
**代码确证** · `Player.kt:69-72` 的 `onPlayerError` 只弹 snackbar，不跳不重试；
`playAt` 的 catch（`:136-141`）同理——而此时 `index`/`current` **已经前进**。
专辑里有一首无版权/VIP → 后面的歌永远不播。
**修复**：出错自动跳下一首，并加「连续失败 N 次即停」的护栏。

### M11. 队列播完 / 出错后 ▶ 按钮点了没反应
**代码确证** · `Player.kt:154-158`
```kotlin
if (p.isPlaying) p.pause() else p.play()
```
`STATE_ENDED` 时 `playWhenReady` 已是 true → `play()` 是 no-op；
出错后处于 IDLE，不 `prepare()` 直接 `play()` 同样无效。
**修复**：ENDED → `seekTo(0)` + `play()`；IDLE-after-error → 重新 prepare。

### M12. JSON 歌词反转义顺序错 + `\uXXXX` 不解码
**已实证**（可运行测试） · `util/LyricText.kt:39-44`
```kotlin
value.replace("\\n", "\n") ... .replace("\\\\", "\\")   // 双反斜杠放最后 → 顺序错
```
测试：
```
input chars   : [BS][BS]n
actual output : [BS][LF]     <-- expected [BS]n
unicode \u4f60\u597d -> \u4f60\u597d   <-- expected 你好
```
`\uXXXX` 完全没处理（NetEase 的 ensure_ascii 风格 JSON 会显示乱码），`\/` 也未还原。
**修复**：单趟从左到右反转义，或直接 `Json.parseToJsonElement` 取值。

### M13. `allowBackup` 默认开启 → 登录凭证被云备份
**已实证** · `AndroidManifest.xml:8-14` 无 `allowBackup`（默认 true）。
cookie/token 明文写在 `filesDir/plugins/<id>/storage.json`（`PluginRuntime.kt:166,210`）与 SharedPreferences。
本次会话中**卸载重装后 `plugin=bilibili` 被系统自动恢复**，证明备份链路确实生效。
**修复**：`android:allowBackup="false"`，或用 `dataExtractionRules` 排除 `plugins/` 与 `plugin-*` prefs。

### M14. 分页 offset 漂移 → 「加载更多」重复
**代码确证** · `AppState.kt:817` + `PluginRuntime.kt:317-320,360`
`stripNullArrayElements` 删掉数组里的 null（长度变短），而
`searchOffset = offset + result.countFor(...)` 用的是**裁剪后**长度。
插件返回 20 条含 2 个 null → 下次 offset 用 18 → 重复 2 条。
**修复**：用插件回传的真实 offset，或在 strip 前记录原始长度。

### M15. 失败重试会重复执行非幂等调用
**代码确证** · `PluginRuntime.kt:350`
```kotlin
if (evaluateError != null && attempt == 1) continue   // 同名同参再跑一遍
```
`like` / `played` / `postComment` / `createPlaylist` / `addToPlaylist` 会**双写**。
**修复**：只对只读导出重试，或 JS 侧按 requestId 去重。

### M16. 插件返回 null → 抛英文序列化错误，而不是友好空态
**已实证**（第三方真机复现 + 代码确证） · `PluginRuntime.kt:356` → `AppState.kt:555` / `:365-368`
```kotlin
// PluginRuntime.kt:356  把 null 原样作为字符串 "null" 返回
if (raw == "null") return raw
```
```kotlin
// AppState.kt:555  直接 decode 到非空模型 → kotlinx.serialization 抛异常
Load.Ready(pluginJson.decodeFromString(BrowsePage.serializer(), call("home", "{}")))
```
```kotlin
// AppState.kt:365-368  原样透传 e.message（英文）
else -> e.message ?: "出错了"
```
**不一致的对照组**：`refreshAccount`（`AppState.kt:382-386`）用 `runCatching{...}.getOrNull()` **容忍** null，
其余 16 处 `decodeFromString` 都没有这层保护。
**后果**：用户看到 `JSON input: null` / `Expected start of the object` 之类英文报错，而非「暂无内容」空态。
**修复**：在 `call()` 或各 `load*` 统一把 `"null"` 归一为空模型（如 `BrowsePage()` / 空列表），
并让 `errorText` 对 `SerializationException` 输出中文兜底文案。

### M17. 播放器爱心状态不同步 → 可能误「取消喜欢」
**代码确证** · `ui/AppState.kt:985,989,846-848` + `ui/screens/PlayerScreen.kt:233-234`
```kotlin
currentLiked = track?.let { isLiked(it.id) } ?: false   // :985 先读缓存（此时通常为空）
refreshLikes(listOf(track.id))                          // :989 再去拿权威状态
// 但 refreshLikes 只写 likedIds，从不回写 currentLiked：
likedIds[id] = value.jsonPrimitive.content.lowercase() == "true"   // :847
```
播放器爱心读的是 `state.currentLiked`。
**触发**：播一首尚未缓存喜欢状态的歌 —— 例如 `playStation`（`:933-949`，从不预取喜欢状态）。
**后果**：爱心显示错误（已喜欢显示成未喜欢）；用户点下去时
`toggleLike` 用的是**已经正确**的 `likedIds` 算 `target = !isLiked(id)`（`:860`）
→ **把本想喜欢的歌取消喜欢了**。
**修复**：`refreshLikes` 命中 `currentTrack?.id` 时同步写 `currentLiked`；或直接让 `currentLiked` 从 `likedIds` 派生。

### M18. 切插件不取消在途加载 → 旧插件结果写进新会话
**代码确证** · `ui/AppState.kt:275-346 (activate)`、`:794-827 (runSearch)`
`activate()` 把 `search`/`searchQuery`/各页状态清空，但**不取消任何 Job**；
全文件 grep 无 `generation` 之类的代际标记，`runSearch` 的写回无守卫：
```kotlin
search = Load.Ready(if (base == null) result else base.merge(result))   // :819
```
**触发**：发起搜索 → 加载中打开顶栏插件切换器 → 切到另一个插件。
**后果**：旧插件的协程在重置之后完成，把结果写进共享 `search`/`searchOffset`
→ 新插件的搜索页显示**上一个插件的结果**（或旧 runtime 被 close 后抛出的错误），而搜索框是空的。
`loadPage` / 各详情页 / 歌词加载同样是「无守卫写回」。
与 M6 是同一问题的两层：M6 是原生引擎层，M18 是 UI 状态层。
**修复**：启动时捕获代际号（或 runtime 实例），写回前比对；`activate` 里取消所有在途加载 Job。

### M19. LazyColumn key 用 `section.hashCode()` → 重复分区会崩
**代码确证（潜在，未构造出真实触发）** · `ui/screens/HomeBrowse.kt`（9 处）+ `Models.kt:129`
```kotlin
item("title-${section.hashCode()}") { ... }
item("tracks-${section.hashCode()}") { ... }
```
`Section` 是 `data class` → 两个结构相同的分区 hashCode 相同 → key 重复
→ `IllegalArgumentException: Key was already used` → **崩溃**。
分区内容由插件 JS 在运行时产生，key 方案本身不提供唯一性保证。
审计方未能确认内置插件会输出重复分区，故按**潜在崩溃**计（自定义插件即可触发）。
**修复**：改用 `forEachIndexed` + `"title-$index"`，与内容无关地保证唯一。

---

## ⚪ LOW（L16 合集）

- **通知权限被拒后无补救**：`ui/App.kt:102-113` 的 launcher 回调是空 `{ }`；13+ 拒绝后前台通知被抑制 → 无锁屏控制，也没有跳系统设置的入口。
- **点通知叠出重复 Activity**：`Player.kt:197-203` 用裸 Intent，无 `SINGLE_TOP|CLEAR_TOP`，`MainActivity` 是 standard → 应用已开时点通知会再压一个实例，返回键要按两次。
- **QR 在主线程生成**：`util/Qr.kt:9-23` 经 `AppState.kt:411-414` 的 `main.post` 调用，720×720 IntArray + ~4.2MB bitmap + 51.8 万次循环全在主线程 → 登录二维码出现时掉帧。改到 `Dispatchers.Default`。
- **release 未签名未压缩**：`app/build.gradle.kts:20-24` `isMinifyEnabled=false`、无 signingConfig、仓库无 proguard 规则 → `assembleRelease` 产出 `app-release-unsigned.apk`（装不上），体积等同 debug（4 ABI，55MB+）；将来开 minify 若无 keep 规则会打断 QuickJS JNI 与 kotlinx.serialization。

### L17. `_log` 是 no-op → 插件 console 全部静默丢弃
**已实证** · `PluginRuntime.kt:247`
```kotlin
engine.function("_log") { null }
```
`prelude.js:223-230` 把 `console.log/info/warn/error` **全部**接到 `_log`：
```js
const __logArgs = function (args) { _log(Array.prototype.map.call(args, String).join(" ")); };
globalThis.console = { log: ..., info: ..., warn: ..., error: ... };
```
插件自己的诊断信息（请求失败原因、鉴权提示）一条都看不到，排障只能靠猜；harness 侧有打印，App 侧没有。
**修复**：`engine.function("_log") { a -> Log.d("MyGoPlugin", a[0] as String); null }`，warn/error 分级，release 可关。

### L18. harness 硬编码他人机器路径（3 处），本机开箱跑不起来
**已实证** · `harness/src/main/kotlin/Main.kt`
```kotlin
:104  File("/home/hatch/workspace/tools/hatch-trust.p12")...
:110  .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("::1", 18080)))
:215  File(args.getOrElse(0) { "/home/hatch/workspace/mygo-android/ref-src/..." })
```
不止报告里提到的 1 处，是 **3 处**：他人私有证书、写死的本机代理端口、写死的默认插件目录。
换机器必须打补丁才能跑，等于这个验证工具对外不可用。
**修复**：证书/代理改为可选环境变量（缺省不启用），默认插件目录指向仓库内 `app/src/main/assets/plugins/`。

### L19. 5 处 AutoMirrored 图标弃用警告 → RTL 语言下不镜像
**已实证**（构建日志精确 5 条）
```
HomeBrowse.kt:170:70   Icons.Rounded.QueueMusic
LibraryScreen.kt:91:53 Icons.Rounded.QueueMusic
PlayerScreen.kt:134:43 Icons.Rounded.QueueMusic
PlayerScreen.kt:338:31 Icons.Rounded.QueueMusic
Sheets.kt:275:40       Icons.Rounded.Logout
```
manifest 已声明 `android:supportsRtl="true"`，但这 5 个方向性图标用的是非 AutoMirrored 版本 → 阿拉伯语/希伯来语下朝向错误。
**修复**：换成 `Icons.AutoMirrored.Rounded.*`（纯替换，零风险）。

### L20. 登录时两层 ModalBottomSheet 叠加
**代码确证** · `ui/App.kt:184-212` + `ui/AppState.kt:409` + `ui/sheets/Sheets.kt:298`
`App.kt` 里 `sheet != null` 与 `signIn != null` 是**两个独立的 `ModalBottomSheet`**；
`startSignIn()` 只设 `signIn = SignInState()`（`:409`），**不清 `sheet`**。
**触发**：未登录 → 打开账户 Sheet → 点「登录」。
**后果**：两个 modal sheet 同时 compose，遮罩叠加，账户 Sheet 留在二维码 Sheet 后面。
**修复**：`startSignIn()` 里先 `sheet = null`。

### L21. 播放轮询空转：空闲/暂停也每 500ms 跑一次
**代码确证** · `ui/App.kt:116-121`
```kotlin
LaunchedEffect(state.isPlaying, state.currentTrack?.id) {
    while (true) { state.refreshPlayer(); kotlinx.coroutines.delay(500) }
}
```
循环体从不判断播放状态 → 没有队列 / 已暂停时仍每 500ms 唤醒一次，只要界面还在 compose。
有 key 且可取消，**不是真泄漏**，只是常开定时器（无谓耗电/CPU）。
**修复**：仅在播放时循环（`!isPlaying` 时早退），位置刷新交给 `Player.onUiUpdate` + 播放中 ticker。

---

## ✅ 已排除（避免误报，均经核对）

- **前台通知 ID 冲突**：`startForeground(1001)` 恰好等于 Media3 `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID`（从 AAR 字节码确认）→ 是**有意替换**，不会重复/残留。
- **FGS 5 秒窗口**：`onCreate` 里立即 `startForeground` 已覆盖，之前的崩溃修复有效。
- **16 KB 对齐**：所有 `.so` zip 偏移 16K 对齐，`libquickjs.so` 的 `PT_LOAD p_align=16384` → 合规。
- **Player 单例字段线程安全**：listener / session callback / `Player.scope` 全在主线程，字段主线程受限 → 除 H2 的**顺序**问题外无数据竞争。
- `ui-tooling` 仅 `debugImplementation`；`MyGoState` 持 applicationContext（无 Activity 泄漏）；QR 尺寸有界且 `runCatching` 兜底；service/activity 的 `exported` 是 MediaSession 与启动器**必需**；targetSdk 34 未触发强制 edge-to-edge。
- `usesCleartextTraffic="true"`：自建 Navidrome/Ampache 走 http 所需，属有意取舍（但建议在设置里提示账号可能明文过网）。

---

## 交叉核对（第三方全面功能测试报告）

### 采纳并已并入
| 他们的条目 | 核实结果 | 落到本报告 |
|---|---|---|
| P0 并发竞态（`pending` 单槽位） | ✅ 与我方 C1 同一根因；`:183` 是声明行，我方原引 `:336` 是赋值行，两者都对 | C1 |
| P0 新症状：**自然连播永久停摆，需强杀** | ✅ 机制复核成立（`Player.kt:59-62` + `:97-102`），且比原有症状更严重 | C1 扩充 |
| P0 新症状：`account` 的 null 串线进 `home` | ✅ 成立，同时暴露独立的 null 处理缺陷 | C1 + M16 |
| P1 插件返回 null → 原始英文解码错误 | ✅ `call():356` 返回 `"null"`，16 处 decode 无保护，`errorText` 原样透传；`refreshAccount` 是唯一容忍者 | **M16（新增）** |
| P2 `_log` no-op | ✅ `:247` + prelude 把 console 四级全接过去 | **L17（新增）** |
| P2 harness 硬编码路径 | ✅ 且不止 1 处，实为 **3 处**（p12 / 代理 / 默认插件目录） | **L18（新增）** |
| 构建 5 个 AutoMirrored 弃用警告 | ✅ 精确 5 条（4×QueueMusic + 1×Logout），已定位到行 | **L19（新增）** |
| 网易云 30 秒试听属服务侧行为 | ✅ 同意，非 App 缺陷；也解释了我方早前看到的 `0:00 / 0:30` | 不计缺陷 |
| `usesCleartextTraffic` 全局放行 | ✅ 事实成立，但属自建服务器 http 的有意取舍 | 已列入「已排除」并加提示建议 |

### 需要纠正的地方
1. **「App.kt 未提交改动」— 不成立。** 通知权限申请已在 `0a06801` 提交；
   `git status --short` 当前只有 `?? BUGS.md`（本报告），工作区无其他未提交改动。
2. **「AppState.kt 约 6500 行」— 数字有误。** `AppState.kt` 实为 **1096 行**，全项目 Kotlin 合计 **5893 行**。不影响其结论。
3. **「通知含上一首/暂停按钮 ✅」不能推为「通知控制可用」。** 按钮*存在*与*有效*是两件事：
   `dumpsys media_session` 显示 `queueTitle=null, size=1`（应用队列 187 首），
   ExoPlayer 播放列表只有 1 项 → 按 next/prev 不会换歌（H3）。该测试未验证「按下之后是否真的切歌」。
4. **「逐句歌词同步滚动 ✅」与 H5 不冲突。** 通过的是 YRC `lines` 路径；
   H5 坏的是**纯 LRC 兜底**路径（`LyricText.kt:17` 把 `[` 开头误判为 JSON），两者是不同分支。
5. **「harness 3 项失败均为 harness 侧问题」部分同意。** 预热规避确实存在（`PluginRuntime.kt:308-313`），
   但 harness 自身硬编码路径是真缺陷（L18），不能整体归为「非缺陷」。

### Compose UI / 状态层专项审计（第二份）
全部 7 条**均已复核成立**，已并入：

| 他们的条目 | 核实结果 | 落到本报告 |
|---|---|---|
| H1 切歌后歌词永不重载 | ✅ `LaunchedEffect(tab)` 缺 track key；`refreshPlayer` 重置为 Idle 后无人重载 | **H6** |
| H2 播客「加载更多」死的 | ✅ `loadShow` 早退守卫；`reset=false` 全项目仅此一个调用点 → 追加分支不可达 | **H7** |
| M1 爱心状态不同步 | ✅ `refreshLikes` 只写 `likedIds`，从不回写 `currentLiked` → 可误取消喜欢 | **M17** |
| M2 切插件不取消在途加载 | ✅ `activate` 无 cancel、无代际守卫（grep 确认） | **M18** |
| M3 `section.hashCode()` 作 key | ✅ `Section` 是 data class、9 处使用；**潜在**崩溃，未构造真实触发 | **M19** |
| L1 登录两层 Sheet 叠加 | ✅ `startSignIn` 不清 `sheet`，App.kt 两个独立 ModalBottomSheet | **L20** |
| L2 轮询空转 | ✅ 循环体不判播放状态 | **L21** |

该审计还**主动排除**了几条误报候选（值得肯定，避免我方误改）：
`saveAlbum` / `subscribeShow` 确实会更新详情 map；browse/home 是替换而非追加，重进 tab 不会重复；
所有 `!!` 都有前置判空；slider/progress 除法有守卫；
`navPages` / `pages` 的 key 经解析全部 16 个内置 `plugin.json` 确认无重复（仅自定义插件才可能触发）。

---

## 修复优先级（更新）

1. **C1** — 总根因。修完可一并消掉：播放不出声 / 进度 0:00 / 页面转圈 60s / null 串线 /
   **自然连播永久停摆**。建议 `Mutex.withLock` 串行化 `call()`（与 QuickJS 单线程语义一致，改动最小），
   或 id 派发（保留并发）；同时删掉 `AppState.kt:988` 的重复 `played`。
2. **歌词链路 4 连修：H6 + H5 + M12 + M16** — 这是目前最坏的功能簇（四个独立缺陷叠加）：
   H6 切歌不重载、H5 纯 LRC 被丢弃、M12 反转义错、M16 null 抛英文错。
   全部是纯函数/薄封装，**已有可运行测试可回归**，性价比最高，建议紧随 C1。
3. **H3 + H4 + M8** — 音乐 App 基本盘：通知栏控制、音频焦点、媒体键。
4. **H2 / M6 / M18 / M7 / M10 / M11** — 播放正确性与后台存活
   （M10+M11 是 C1 停摆后无法自愈的放大器；M6+M18 是切插件的原生层+状态层双写问题，建议一起修）。
5. **H7 + M17** — 两个「按钮看着能用其实没用/反效果」的功能缺陷，改动都很小。
6. **M13** — 凭证安全，一行 manifest。
7. **L17 / L19 / L20 / L21** — 可观测性、RTL、Sheet 叠加、轮询空转，改动极小，建议顺手做
   （L17 对后续排障价值最高）。
8. 其余按需。

> 复现工程：`/tmp/bugrepro`（并发串台）、`/tmp/lyrictest`（歌词）。
> 实机取证：`screenshots/BUG-race-timeout.png`；第三方佐证图在 `/tmp/mygo-test/`。
> **审计未改动任何项目源码**，仅新增本报告与取证图。

---

# 修复实施与验证（2026-11-08）

分工：桥层/歌词/harness 由 Lead 改；`Player.kt`/Manifest/gradle 由 media-fixer 改；`ui/**` 由 ui-fixer 改（文件级互斥，无写冲突）。

## 修复清单

| # | 状态 | 关键改动 | 验证方式 |
|---|---|---|---|
| C1 | ✅ | `pending` 单槽位 → `ConcurrentHashMap<Long,Deferred>`；`__invoke(name,args,callId)` + `_deliver(callId,json)` 按 id 派发；`engineMutex` 串行化 native evaluate（await 在锁外，保留网络并发） | 复现工程 6轮×3并发 **0 串台 0 超时**；实机切歌/搜索/首页无超时 |
| H2 | ✅ | `resolveJob` 取消 + `index==at && current?.id==track.id` 双重守卫；headers 改 `ResolvingDataSource` 按 DataSpec 注入，删除全局 `setDefaultRequestProperties` | 实机日志 `LOOKUP hit=true keys=[Referer,User-Agent]` 确认 per-item 命中 |
| H3 | ⚠️ 部分 | `QueueForwardingPlayer` 覆写 `getAvailableCommands`/`seekToNext·Prev*`/`hasNext·Prev*`/`getMaxSeekToPreviousPosition=0` | actions 位实测：末首 `7339703`（next 禁用/prev 启用）， mid-queue `7340031`（双向启用）；**MEDIA_NEXT 真的换歌**。队列计数仍报 1（见下） |
| H4 | ✅ | `setAudioAttributes(USAGE_MEDIA+MUSIC, handleAudioFocus=true)` + `setHandleAudioBecomingNoisy(true)` | 编译 + API 核对；来电/拔耳机未构造 |
| H5 | ✅ | JSON 分支只在**真能解析且取到歌词文本**时使用，否则回落 LRC；剥离 `[ti:]`/`[ar:]`/`[offset:]` 元信息 | 10/10 用例；**实机中英文歌词均正常渲染** |
| H6 | ✅ | `LaunchedEffect(tab, track.id)`；`loadLyrics` 捕获 trackId+generation，只在仍是当前曲时发布 | **实机决定性验证**：留在歌词页派发 MEDIA_NEXT → 歌词自动换成新歌的 |
| H7 | ✅ | 删掉 `loadShow` 的 `!reset && existing!=null` 早退；改 `showLoadingMore` 防并发，offset=已有集数 | 编译 + 逻辑对照 `loadLiked` |
| M6 | ✅ | `close()` 立即 `completeExceptionally` 全部在途；teardown 排在 `engineMutex` 之后；`load()` 也纳入同锁（拆 `loadLocked()`） | 复现工程：在途调用 **501ms 取消**，不再等 30s |
| M7 | ✅ | `serviceDestroyed()` 在 `onDestroy` 复位 `serviceStarted` | 编译 |
| M8 | ✅ | 删除 service 上的假 action，改真正的 `androidx.media3.session.MediaButtonReceiver` | `dumpsys`：`Last MediaButtonReceiver: componentName=…/androidx.media3.session.MediaButtonReceiver` |
| M9 | ✅ | `setWakeMode(WAKE_MODE_NETWORK)` + `WAKE_LOCK` | 合并 manifest 确认 |
| M10 | ✅ | 出错自动跳下一首 + 连续 3 次失败护栏；播放成功/手动操作复位计数 | 实机观察到 1→2→3 后正确停止 |
| M11 | ✅ | `toggle` 在 ENDED/IDLE 时重新 resolve/prepare | 编译 |
| M12 | ✅ | **删除手写反转义**，改用 JSON 解析器（`\uXXXX`/`\\`/`\n` 天然正确） | `\u4f60\u597d`→你好；`path\\next`→`path\next` 用例通过 |
| M13 | ✅ | `allowBackup="false"` | 合并 manifest 确认 |
| M14 | ✅ | 新增 `callWithRaw(): RawCallResult(stripped, raw)`；decode 用 stripped，offset 用 **raw** 数组长度 | 编译 + 实机搜索/加载更多正常 |
| M15 | ✅ | 重试白名单化（只读导出），写操作不再双写 | 代码 + 白名单核对 |
| M16 | ✅ | `decodeOrNull` 把 `"null"` 归一为空态；`errorText` 映射 `SerializationException` 为中文 | 编译；实机未再出现英文 JSON 报错 |
| M17 | ✅ | `refreshLikes` 命中当前曲时同步 `currentLiked` | 编译 + 逻辑 |
| M18 | ✅ | `generation` 代际号 + `loaderJobs` 全量取消；13 个 loader 全部守卫；`CancellationException` 当正常取消 | 编译；实机切插件无「插件已关闭」闪现 |
| M19 | ✅ | 9 处 `section.hashCode()` → `forEachIndexed` 索引 key | 编译 |
| L16a | ✅ | 拒绝记录持久化 + 设置页「去设置」入口，不每次启动唠叨 | 编译 |
| L16b | ✅ | PendingIntent `SINGLE_TOP\|CLEAR_TOP` + `launchMode="singleTop"` | 合并 manifest 确认 |
| L16c | ✅ | QR bitmap 移到 `Dispatchers.Default`，主线程只发布 | 编译 |
| L16d | ✅ | `proguard-rules.pro`（QuickJS JNI / serialization / Media3 / OkHttp）+ minify/shrink 开 + 可选 env 签名 | **`assembleRelease` BUILD SUCCESSFUL**（6.9M unsigned），keep 生效，4 ABI so 齐全，16KB 对齐 intact |
| L17 | ✅ | `_log` → `Log.d("MyGoPlugin", "[id] …")` | 实机可见插件 console |
| L18 | ✅ | 证书/代理/插件目录改 env 可选；默认指向仓库内插件；**删除过期的 prelude 私有副本**改为构建时从 app 拷贝；同步 id 协议；修正 `addModule` 与 `install` 顺序；补 `host.dir` 注入 | harness 实跑网易云 **8 passed / 1 failed**（唯一失败是服务端 VIP 限制） |
| L19 | ✅ | 5 处 → `Icons.AutoMirrored.Rounded.*` | 重编译后 5 条弃用警告消失 |
| L20 | ✅ | `startSignIn` 先 `sheet=null` | 编译 |
| L21 | ✅ | 轮询改 `while(state.isPlaying)`，空闲/暂停只刷一次 | 编译 |

### H3 为何只做 P0
Media3 的平台队列来自事件携带的**真实单曲 Timeline**，并用 `getCurrentMediaItemIndex()` 去 `getWindow(index)`。
若伪造 187 条窗口或让 index > 0 而 Timeline 只有 1 窗，会**越界崩溃**——比原 bug 更糟。
因此只做「按钮可用 + 真的换歌 + 首尾正确禁用」，**队列计数仍显示 1**。完整队列上报需 `setMediaItems` 重构，另开任务。

## ⚠️ 修复过程中捕获并修掉的 3 个回归

这三个都是**修复本身引入的**，纯编译检查发现不了，靠实机回归才暴露：

1. **FGS 崩溃复发（阻塞级）** —— 重写 `PlaybackService.onCreate` 时丢了 `startPreparationForeground()` **和 `addSession(session!!)`**。
   后者才是让 `MediaSessionService` 自动接管前台通知的开关；只覆写 `onGetSession` 不会前台化。
   实测播放约 60s 后 `ForegroundServiceDidNotStartInTimeException`，session 归零。
   → 已补回两者，实测 **75 秒零崩溃、session 存活**。

2. **stream 契约被破坏（阻塞级）** —— 改成「`track.url` 非空就跳过 `stream()`」。
   但 `bilibili/index.js:214` 的 `track.url` 是**网页地址** `https://www.bilibili.com/video/<bvid>`，
   于是 HTML 被喂给 ExoPlayer → `UnrecognizedInputFormatException`。
   HOST_SPEC 规定只要插件导出 `stream` 就**必须**调用它取播放地址与 Referer/UA。
   → 改为 `rt.can("stream")` 时必须解析，无该导出才回落 `track.url`。

3. **UA 与 quality 被擅自改动** —— UA 从浏览器串改成 `MyGoMusicAndroid/0.1`，且 `stream` 的 `quality` 参数被丢弃。
   → 均已恢复（`quality` 影响插件选择的码率档位）。

## 验证证据

**回归工程** `/tmp/bugrepro`（编译仓库真实 `LyricText.kt` + 读仓库真实 `prelude.js`）：
```
T1 concurrency : PASS   (6 轮 × 3 并发，0 串台 0 超时)
T2 close       : PASS   (501ms 取消，不再 30s 挂起)
T3 lyrics      : PASS   (10/10 用例)
```

**官方插件端到端** `harness`（真实网易云网络数据，走新 id 桥）：
```
8 passed, 1 failed
唯一失败：stream — 该音频暂不可完整播放，可能需要会员或购买（服务端限制）
search 50 首 / suggest / 歌单 144 首 hydration / lyrics / home 全 PASS
```

**实机（模拟器 API 37）决定性验证**：
```
state=PLAYING(3), position=78716, speed=1.0      ← 真实出声，不再卡在 0:00
actions: 末首 7339703(next禁用/prev启用) → 队列中 7340031(双向启用)
MEDIA_NEXT 留在歌词页 → 曲目切换且歌词自动重载成新歌的中文歌词
全程 FATAL / ForegroundServiceDidNotStart / Timed out waiting 计数 = 0
```
截图：`screenshots/FIXED-lyrics-auto-reload.png`、`FIXED-player-playing.png`、`FIXED-home.png`

## 未能实机构造的场景（诚实说明）
- H4 来电抢占 / 拔耳机暂停：需要真实电话或耳机事件
- M9 息屏长时间流播卡顿：设备相关，需长时间真机观察
- M8 Android 8–11 媒体键：手上只有 API 37 设备（receiver 注册已确认）
- H7 播客追加分页：需要一个 `more=true` 的播客源
- 自然播完自动连播：网易云对本模拟器多为会员/试听限制，改用媒体键切歌覆盖了同一条并发路径（stream+played+isLiked）

## 已知遗留 / 新发现（未纳入本次 27 条）
- **H3 队列计数上报**：需 `setMediaItems` 重构，另开。
- **harness 大 JSON 截断**：bilibili 的 search/home 返回体较大时 harness 侧 `JsonDecodingException: Unexpected EOF`；
  app 端同样数据正常，属 harness 工具限制，另开。
