# UI 重设计说明（Jetpack Compose + Material 3）

这次改动的目标：保留原有插件宿主能力（QuickJS 宿主 API、播放、登录、插件设置全部照旧），
把界面**整体重写**成一套参考 MyGo 桌面版视觉语言、又符合 Android / Material 3 设计规范的 UI。

原实现是单个 Activity 里用 `LinearLayout` / `TextView` / `RecyclerView` 手工拼装
（`MainActivity.kt` 1255 行 + `Ui.kt`），只有一套写死的深灰配色（`#121212 / #1E1E1E / #E60026`），
没有主题、没有动效、没有平板适配，也没有现在播放页的层级。

## 1. 技术栈变化

| | 改前 | 改后 |
| --- | --- | --- |
| UI 框架 | 原生 View 手工拼装 | **Jetpack Compose**（BOM 2024.12.01 / Compose 1.7.6） |
| 构建 | AGP 8.5.2 / Gradle 8.10.2 / Kotlin 2.0.20 / compileSdk 34 | **AGP 8.13.0 / Gradle 8.13 / Kotlin 2.4.10 / compileSdk 36**（为 16 KB 页对齐的 quickjs 版本所需） |
| 设计系统 | 无，硬编码颜色 | **Material 3**：完整 `ColorScheme` / `Typography` / `Shapes` |
| 导航 | 自己维护的 View 栈 | 底部 `NavigationBar` + 页面栈（详情页隐藏底栏） |
| 图片 | Coil `ImageView` | `coil-compose` `AsyncImage` + 占位/骨架 |
| 播放器 UI | 简易 SeekBar + 歌词列表 | 全屏 Now Playing：封面/歌词双页、可拖动进度、队列 |
| 主题 | 固定深色 | 深色 / 浅色 / 跟随系统 + Android 12+ 动态取色 |
| 图标 | 文字符号（`‹`、“插件”） | Material Icons（Rounded 系列） |
| 启动图 / 图标 | 系统默认 | 品牌色启动底色 + 自适应图标（音符） |

## 2. 设计系统（取自 MyGo，落地为 M3）

- **配色**（`ui/theme/Theme.kt`）
  - 深色：背景 `#141416`，卡片 `#1C1C1F`，文字 `#F5F5F7`，次级文字 `#9C9CA4`，
    描边 `#26262B`，主色 `#F45058`（MyGo 的 `oklch(0.66 0.2 22)`）。
  - 浅色：背景 `#FFFFFF`，分组面 `#F4F4F6`，文字 `#1D1D1F`，主色 `#E2333C`。
  - 保留 MyGo 的“几乎看不见的细线”：`hairline = 白 7% / 黑 7%`，用于列表与顶栏分隔。
  - **刻意关掉 M3 tonal elevation**（全部 `tonalElevation = 0`），否则深色卡片会被 M3 抬成灰蓝。
- **字体**：沿用系统字体（中文为 Noto Sans CJK），按 MyGo 的紧凑比例重写 M3 type scale：
  页面大标题 30sp / 600，区块标题 17sp / 600，正文 14–15sp，辅助/时间 11–12.5sp。
- **形状**：6 / 10 / 14 / 20 / 28dp；卡片 12–14dp，搜索框与胶囊按钮 999dp。
- **动效**：页面切换与 Now Playing 用 `slideInVertically` 340ms；骨架用 900ms 呼吸；
  进度条 2dp 细线；点击反馈走 M3 ripple。
- **签名元素转译**：桌面 232px 侧栏 → 底部 4 项 `NavigationBar`；
  悬浮播放栏 → `Scaffold.bottomBar` 里的 64dp MiniPlayer（顶部 2dp 进度线）；
  Now Playing 全屏覆盖 → `AnimatedVisibility` + 全屏 Surface；
  封面驱动的色调 → 详情页/播放页以封面为背景叠渐变 scrim。

## 3. 信息架构

- **底部四个 Tab**：首页 / 浏览 / 搜索 / 音乐库（对应 MyGo 侧栏核心项）。
- **顶栏**：左侧是当前音源胶囊（点头像切换插件），右侧设置与账户头像；进入详情页变成「返回 + 标题」。
- **MiniPlayer**：有播放内容时浮在底栏上方，点击进入 Now Playing，左侧 2dp 进度线。
- **详情页**：播放列表 / 专辑 / 歌手 / 播客 / 浏览子页，统一「封面 Hero + 播放/随机/收藏 + 曲目列表」。

| 页面 | 文件 | 内容 |
| --- | --- | --- |
| 首页 | `ui/screens/HomeBrowse.kt` | 问候语 + 快捷胶囊（喜欢/最近/歌单/播客）+ 插件 `home()` 的各个 section（横滑卡片、专辑、歌手、歌单、播客、电台、链接） |
| 浏览 | 同上 | 无 `browse` 导出时列出插件 `navigation` 声明的页面（每日推荐、排行榜…）；有则渲染 browse 根页 |
| 搜索 | `ui/screens/SearchScreen.kt` | 圆角搜索框、输入建议、五类结果切换（单曲/专辑/歌手/歌单/播客）、分页“加载更多” |
| 音乐库 | `ui/screens/LibraryScreen.kt` | 账户卡（登录/退出）、喜欢/最近/歌单/播客入口、歌单横滑、插件设置与切换入口 |
| 详情 | `ui/screens/DetailScreens.kt` | 歌单/专辑/歌手/播客/喜欢/最近/歌单列表/播客列表，统一 Hero 头与曲目行 |
| 播放 | `ui/screens/PlayerScreen.kt` | 封面页 / 歌词页（逐行滚动高亮 + 翻译）、进度拖动、上一首/播放/下一首/随机/队列 |
| 弹层 | `ui/sheets/Sheets.kt` | 插件切换、设置、账户、插件设置表单、播放队列、扫码登录 |
| 骨架 | `ui/App.kt` | Scaffold、顶栏、MiniPlayer、NavigationBar、页面栈、Snackbar、Now Playing 覆盖层 |
| 状态 | `ui/AppState.kt` | 单例状态容器：插件生命周期、各页 Load 状态、搜索分页、喜欢集合、播放镜像、导航 |

状态采用 `MyGoState`（进程级单例）+ Compose `mutableStateOf`，这样旋转屏幕不会销毁
QuickJS 运行时或播放队列；所有插件调用仍在协程里走 `PluginRuntime.call`。

## 4. 同时修掉的宿主问题

1. **播放前台服务崩溃**（`ForegroundServiceDidNotStartInTimeException`）：
   `PlaybackService` 在 `onCreate` 立刻 `startForeground` 一条“正在准备播放”通知，
   等 Media3 接管后再换成媒体通知。流地址解析慢时不再被系统杀掉。
2. **QuickJS 缺 `TextEncoder/TextDecoder`**：网易云等插件用 `TextEncoder` 编码请求体，
   原宿主会直接 `ReferenceError` 导致插件加载失败。已在 `assets/prelude.js` 里补 UTF-8 实现。
3. **默认音源**：优先选择开箱可用的插件（网易云 / 酷狗 / Bilibili / YouTube Music …），
   插件列表也按此排序，用户第一次打开就能看到内容，而不是要求先填服务器地址的插件。
4. **登录弹层竞态**：取消登录后不会再被异常结果“复活”。
5. **歌词格式兼容**：网易云把富文本歌词（`{"t":0,"c":[{"tx":"..."}]}`）塞进 `lrc` 字段，
   播放页现在会解析成纯文本逐行显示，而不是把 JSON 原样糊在屏幕上（`util/LyricText.kt`）。

## 5. 验证

- `./gradlew assembleDebug` 通过（Kotlin 2.0.20 + AGP 8.5.2 + JDK 17，compileSdk 34）。
- 在 Pixel 9 / API 37 模拟器（1080×2400，density 420）实机安装启动，逐屏截图确认：
  首页、浏览、搜索（含五类结果）、音乐库、歌单详情、专辑详情、歌手详情、
  全屏播放（封面/歌词）、设置弹层、插件切换、扫码登录、深色/浅色两套主题。
  截图在 `screenshots/`（`ui-final-*` 为最终版，`_baseline_home.png` 为改前对比）。
- 用 `uiautomator dump` 核对文案与控件位置，确认中文渲染、底栏/迷你播放器/详情页层级正确。

## 6. 已知限制（插件侧，不是 UI）

- 部分网易云接口在未登录/无版权时返回 `参数错误`、`请先登录网易云音乐`，UI 会以
  统一错误态 + 重试呈现；`每日推荐`、`喜欢`、歌词等需要登录后才有数据。
- 应用内登录依赖插件 `signIn` 导出与扫码，纯桌面辅助进程插件（yt-dlp/ffmpeg/librespot）仍只能浏览与搜索。

## 7. 16 KB 页大小对齐（Android 15+/16 安装警告）

Android 16 起，安装含原生库的应用时，如果 `.so` 不是按 **16 KB 页**对齐，会弹出
「此应用不符合 16 KB 对齐要求」的提示。

- **根因**：`io.github.dokar3:quickjs-kt:1.0.0-alpha13` 的 `libquickjs.so` 是旧 NDK 构建，
  ELF `LOAD` 段 `p_align = 0x1000`（4 KB）。`androidx.graphics.path.so` 本来就是 0x4000，没问题。
- **修复**：升级 `quickjs-kt` → **1.0.10**（官方已改成 16 KB 对齐，`p_align = 0x4000`）。
  该版本 AAR 要求 `minCompileSdk 36`、依赖 Kotlin 2.4 元数据，因此连带升级
  AGP 8.5.2 → **8.13.0**、Gradle 8.10.2 → **8.13**、Kotlin 2.0.20 → **2.4.10**、compileSdk 34 → **36**
  （`targetSdk` 保持 34，行为不变）。Kotlin 2.4 起 `jvmTarget` 必须走 `kotlin.compilerOptions` DSL，已同步改。
- **验证**（全部通过）：
  ```bash
  # ELF 段对齐：全部 0x4000
  readelf -lW lib/arm64-v8a/libquickjs.so | awk '/LOAD/{print $NF}' | sort -u
  # APK 内 16 KB zip 对齐（build-tools 36）
  zipalign -c -v -P 16 4 app/build/outputs/apk/debug/app-debug.apk   # Verification successful
  ```
  并在模拟器与实机（Android 17）安装运行，首页/搜索/播放均正常。

## 8. 构建与运行

```bash
# 需要 JDK 17 + Android SDK（compileSdk 34）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.walden.mygomusic/.MainActivity
```

> 首次构建需要联网拉取 Compose 依赖；国内网络可在 `~/.gradle/init.d/` 里加阿里云镜像
> （本次开发即用此法，见提交说明，不改动项目本身的仓库配置）。
