# MyGo Music for Android · 视觉设计规格（逆向自桌面版）

> 来源：`/tmp/mygo-music`（Go + `github.com/egoist/mygo/ui`）。文中 `foo.go:12` 均指 `internal/app/foo.go`，另有说明者除外。所有数值取自源码，已逐条标注。目标：Jetpack Compose + Material 3，包名 `dev.walden.mygomusic`，minSdk 26 / compileSdk 34。

## 0. 前置

**0.1 依赖**（当前 `app/build.gradle.kts` 只有 View + RecyclerView，无 Compose，必须新增）：

```kotlin
implementation(platform("androidx.compose:compose-bom:2024.09.03"))
implementation("androidx.compose.ui:ui"); implementation("androidx.compose.foundation:foundation")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.activity:activity-compose:1.9.2")
implementation("io.coil-kt:coil-compose:2.7.0")
```

**0.2 能力降级**：桌面用 glass 材质做滚动边缘毛玻璃（ui_main.go:358-360）。Android 上 API 31+ 用 `Modifier.blur`（RenderEffect）；API 26–30 退化为「背景色 96% 不透明 + 顶部 hairline」，视觉等效。Now Playing 背景的模糊封面层同理（backdrop.go:213-216）。

---

## 1. 设计令牌

### 1.1 调色板（唯一色源 `paletteFor`，ui_kit.go:40-70）

| Token | Light | Dark | 语义 | 源码 |
|---|---|---|---|---|
| `bg` | `#FFFFFF` | `#141416` | 内容背景 | ui_kit.go:58 / 44 |
| `sidebar` | `#F4F4F6` | `#1C1C1F` | 侧栏背景 | :59 / :45 |
| `text` | `#1D1D1F` | `#F5F5F7` | 主文本 | :60 / :46 |
| `muted` | `rgba(60,60,67,.62)` | `rgba(235,235,245,.56)` | 次级文本 | :61 / :47 |
| `faint` | `rgba(60,60,67,.32)` | `rgba(235,235,245,.30)` | 序号 / 时长 / 占位 | :62 / :48 |
| `hairline` | `rgba(0,0,0,.07)` | `rgba(255,255,255,.07)` | 1px 分隔线与描边 | :63 / :49 |
| `hover` | `rgba(0,0,0,.04)` | `rgba(255,255,255,.06)` | 悬停底 / 浅填充 | :64 / :50 |
| `press` | `rgba(0,0,0,.08)` | `rgba(255,255,255,.10)` | 选中底 / 按下 | :65 / :51 |
| `accent` | `oklch(.6 .21 24)` ≈ `#E2333C` | `oklch(.66 .2 22)` ≈ `#F45058` | 品牌红 | :66 / :52 |
| `onAccent` | `#FFFFFF` | `#FFFFFF` | 红底上的文字 | :67 / :53 |
| `cardShade` | `rgba(0,0,0,.16)` | `rgba(0,0,0,.45)` | 投影统一色 | :68 / :54 |
| `selection` | accent @ 30% | accent @ 30% | 文本选区 | ui_main.go:20 |

补充常色：Now Playing 底 `#0B0B0D`（ui_nowplaying.go:266）；无封面时的 tint 缺省 `#787882`（:261）；无损徽章 `#F5D58A`（:554-556）；弹层 fill `#F6F6F6` / `#2B2B2E`、edge `rgba(0,0,0,.12)` / `rgba(255,255,255,.14)`（ui_player.go:213-219）；封面 hover 蒙层 `rgba(0,0,0,.35)` + 白色图标（:388-391）。

### 1.2 accent 解析与派生

- 品牌红用 Oklch 定义（ui_kit.go:66 / :52），营销站同值（website/src/styles.css:16）。Oklab→sRGB 换算：浅色 ≈ `#E2333C`，深色 ≈ `#F45058`。
- hover / pressed 由 accent 与黑/白在 Oklab 中线性插值（ui_kit.go:31-38，`MixOklab` 按 L/a/b 分量 lerp）：

| | Light | Dark |
|---|---|---|
| accent | `#E2333C` | `#F45058` |
| hover | `#C42B33`（向黑 10%） | `#FA6F71`（向白 15%） |
| pressed | `#A8232A`（向黑 20%） | `#C53F45`（向黑 15%） |
| 选中行底 / 播放行底 | accent @ 9% → `#FCEDED` | accent @ 9% → `#28191C` |

### 1.3 叠层语义（Android 保留 alpha 叠加，括号内为合成值）

| 层 | Light | 合成到 `bg` | Dark | 合成到 `bg` |
|---|---|---|---|---|
| hairline | 黑 7% | `#EDEDED` | 白 7% | `#242426` |
| hover | 黑 4% | `#F5F5F5` | 白 6% | `#222224` |
| press | 黑 8% | `#EBEBEB` | 白 10% | `#2C2C2D` |
| muted | 黑系 62% | `#86868A` | 白系 56% | `#8C8C93` |
| faint | 黑系 32% | `#C1C1C3` | 白系 30% | `#545459` |
| 曲目行不可用 | — | 整体 opacity 0.42 | 同 | 同（ui_pages.go:504） |

### 1.4 排版（字号/字重/字距直接取自源码，行高仅在显式设置处标注）

| 角色 | sp | 字重 | 字距 | 行高 | 用途与出处 |
|---|---|---|---|---|---|
| 微标 | 9.5 | 800 | +0.6 | — | 「无损」徽章（ui_nowplaying.go:554） |
| 时间 | 10.5 | 400 | tnum | — | 播放栏已播/总时长（ui_player.go:424） |
| 时间·NP | 11 | 400 | tnum | — | 全屏播放两侧时间（ui_nowplaying.go:381） |
| 分组小标 | 11 | 600 | — | — | 搜索建议分组标题（ui_suggest.go:124） |
| 芯片 | 11.5 | 700 | +0.3 | — | 电台 chip（ui_nowplaying.go:542） |
| 标题副行 | 12 | 600 | +0.4 | — | 页面 heading 上方小字（ui_pages.go:28） |
| 标签 | 12 | 600 | — | — | 专辑/歌单标签、设置组标题（ui_album.go:72；ui_settings.go:290） |
| 说明 | 12 | 400 | — | 1.4 | 设置行说明（ui_settings.go:307） |
| 行副文 | 12.5 | 400 | — | — | 曲目行歌手、专辑列、时长（ui_pages.go:522,535） |
| 正文 | 13 | 400 | — | 1.45 | 空态说明、搜索框、分段标签（ui_pages.go:499；ui_suggest.go:81） |
| 行标题 | 13.5 | 600 | — | — | 播放栏歌名（ui_player.go:395） |
| 行标题·设置 | 13.5 | 500 | — | — | 设置行标签（ui_settings.go:306） |
| 曲目名 | 14 | 500 | — | — | 曲目行主标题（ui_pages.go:517） |
| 按钮 | 14 | 600 | — | — | pill 按钮文字（ui_kit.go:136） |
| NP 歌手 | 15 | 400 | — | — | 全屏播放副标题（ui_nowplaying.go:370） |
| 弹窗标题 | 17 | 700 | — | — | 登录弹窗（ui_account.go:220） |
| 舞台翻译 | 18 | 600 | — | — | 逐字歌词翻译（ui_stage.go:153） |
| 分区标题 | 19 | 700 | −0.3 | — | 首页分区（ui_pages.go:35） |
| 页面题·小 | 22 | 700 | −0.3 | — | NP 歌名、空态标题（ui_nowplaying.go:369；ui_pages.go:140） |
| 空歌词 | 24 | 700/800 | — | — | 「纯音乐，请欣赏」（ui_nowplaying.go:624） |
| 页面题 | 30 | 700 | −0.6 | — | 通用 heading（ui_pages.go:31） |
| 详情题 | 30 | 800 | −0.6 | 1.2 | 专辑/歌单/歌手标题（ui_album.go:73） |
| 歌词行 | 30 | 800 | — | 1.25 | 歌词 pane 行（ui_nowplaying.go:646） |
| Hero 题 | 32 | 800 | −0.8 | 1.15 | 首页主推卡标题（ui_daily.go:94） |
| 装饰数字 | 112 / 240 | 800 | −5 / −12 | 1.0 | 主推卡日期（ui_daily.go:145,87） |

字重集合：500 / 600 / 650 / 700 / 800（grep 统计）。负字距只用于 ≥19sp 的大标题；小字不加负字距。所有数字计时一律 `tnum`（ui_pages.go:535；ui_player.go:424）。

### 1.5 间距

常用刻度：`2 3 4 5 6 8 10 12 14 16 18 20 26 28 32 40 44 48 72`（DIP）。

- `pagePad = 40`（ui_pages.go:16）、`pageGap = 12`（ui_main.go:284）；
- 页面滚动容器水平内边距 = `pagePad - 12 = 28`，行内再加 12 → 文字实际左缘落在 40（ui_pages.go:70；ui_artist.go:185）；
- 侧栏内边距 14、项间距 4（ui_main.go:222,228）；
- 曲目行 `paddingX 12 / gap 14`（ui_pages.go:483）；
- 卡片列 `gap 10`、封面与文字（ui_pages.go:196-200）；
- 详情头 `gap 28`、底距 26（ui_album.go:67）；NP 左列与右栏 `gap 72`（ui_nowplaying.go:349）；
- 页面底留白 `bottom + 48 / + 32` 避开播放栏（ui_pages.go:70,466）。

### 1.6 圆角

| 半径 | 用途 | 出处 |
|---|---|---|
| 4 | 徽章、小标签 | ui_nowplaying.go:556 |
| 6 | 曲目行封面、建议选项 | ui_pages.go:510；ui_suggest.go:128 |
| 8 | 侧栏导航项、播放栏封面 | ui_main.go:248；ui_nowplaying.go:227 |
| 9 | 侧栏搜索框 | ui_suggest.go:64 |
| 10 | 卡片封面、曲目行、账户行 | ui_pages.go:200,483；ui_account.go:301 |
| 12 | 设置分组、菜单、队列表行、弹层 | ui_settings.go:293；ui_nowplaying.go:838 |
| 14 | 详情页大封面 | ui_album.go:68 |
| 18 | 登录弹窗 | ui_account.go:205 |
| 24 | 首页主推卡 | ui_daily.go:152 |
| 999 | pill 按钮、圆形按钮、头像 | ui_kit.go:132；ui_pages.go:239 |

### 1.7 阴影（统一用 `cardShade`，例外标注）

| 用途 | dy / blur / spread | 色 | 出处 |
|---|---|---|---|
| 播放栏封面 | 2 / 8 / 0 | cardShade | ui_player.go:386 |
| 卡片静置 / 悬停 | 2 / 8 / −2、12 / 28 / −6 | cardShade | ui_pages.go:206-209 |
| 详情页封面 | 18 / 40 / −10 | cardShade | ui_album.go:68 |
| 主推卡 | 14 / 34 / −14（悬停 +8 / +12） | cardShade | ui_daily.go:42 |
| 封面播放圆钮 | 4 / 12 / 0 | `rgba(0,0,0,.3)` | ui_pages.go:239 |
| 弹层 / 菜单 | 8 / 24 / −4（+0.5px 描边） | `rgba(0,0,0,.2)` | ui_player.go:191 |
| 弹窗 | 24 / 60 / −12 | `rgba(0,0,0,.4)` | ui_account.go:206 |

阴影全部无横向偏移，方向感是「正上方来光」。

### 1.8 动效

| 场景 | 时长 | 缓动 | 出处 |
|---|---|---|---|
| 颜色过渡（导航项、悬停、pill） | 120ms | ease | ui_main.go:256；ui_kit.go:118 |
| 曲目行底色 | 100ms | ease | ui_pages.go:496 |
| 滚动边缘出现/消失 | 180ms | ease | ui_main.go:349 |
| NP 淡入（减少动效时） | 200ms | ease | ui_nowplaying.go:23 |
| 分段控件胶囊滑动 | 280ms | spring, bounce .08 | ui_kit.go:298 |
| NP 全屏展开 / 播放↔暂停图标 | 340ms | spring, bounce .3 | ui_nowplaying.go:19；ui_kit.go:97 |
| 封面配色渐变 | 600ms | ease | ui_daily.go:38 |
| 主推卡高光扫过 | 1100ms | easeInOut | ui_daily.go:46 |
| 唱片滑出 | 700ms | spring, bounce .25 | ui_daily.go:60 |
| 按钮按下 | — | 缩放 0.88（圆钮）/ 0.96（pill） | ui_kit.go:123,139 |
| 卡片悬停上浮 | — | y −3，spring | ui_pages.go:206-210 |
| 骨架 shimmer | 周期 1600ms，扫过 1100ms | linear | ui_skeleton.go:41-43 |

`ui.Snappy` 是 mygo/ui 包常量，仓库未 vendor 该模块，无法读到精确贝塞尔；营销站给出全站缓动 `cubic-bezier(.22,1,.36,1)` 与 `cubic-bezier(.34,1.56,.64,1)`（website/src/styles.css:32-33），与上述弹簧观感一致 → Android 用 `CubicBezierEasing(0.22f,1f,0.36f,1f)` 与 `CubicBezierEasing(0.34f,1.56f,0.64f,1f)`；弹簧用 `spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)`。

### 1.9 骨架屏（ui_skeleton.go:41-53）

周期 1600ms、扫过 1100ms、带宽 260dp、带前静止 160ms。浅色：底 `rgba(0,0,0,.06)`，扫过白光 2.2%；深色：底 `rgba(255,255,255,.07)`，扫过白光 13%；NP 深底上：10% / 20%。占位块按真实排版尺寸（圆 999 / 文本行高 3 / 封面方图）。

### 1.10 布局常量

| 常量 | 值 | 出处 |
|---|---|---|
| 侧栏宽 | 232 | ui_main.go:13 |
| 顶栏高 | 40 | ui_main.go:318 |
| 播放栏高 | 84 | ui_main.go:281 |
| 页面标题下距 | 18 | ui_pages.go:27 |
| 分区标题间距 | 上 34 / 下 14 | ui_pages.go:35 |
| 曲目行高 | 54 | ui_pages.go:483 |
| 列表封面 | 38，r6 | ui_pages.go:510 |
| 详情封面 | 212，r14 | ui_album.go:68 |
| 网格最小列宽 | 132 / 150 / 156，gapX 20 gapY 28 | ui_search.go:142；ui_podcast.go:317；ui_pages.go:19-22 |
| NP 左列宽 | min(w×.36, 440)，且 ≥280；封面 = min(该宽, h−436) | ui_nowplaying.go:340-347 |
| NP 内容最大宽 | 1280，两侧留白 max(72, (w−1280)/2) | ui_nowplaying.go:426,431 |
| NP tabs 行高 | 28，项间距 6 | ui_nowplaying.go:437,444 |
| 队列行 | 58，r12 | ui_nowplaying.go:838 |
| 设置侧栏 | 200 | ui_settings.go:229 |

---

## 2. 信息架构

### 2.1 导航（侧栏，ui_main.go:213-243, 593-636）

固定顺序（插件未声明 navigation 时）：**首页 `Home` / 我喜欢的音乐 `Liked Songs` / 收藏的专辑 `Saved Albums` / 电台 `Radio` / 播客 `Podcasts`**（ui_main.go:612, 599-603）；图标依次 home、heart、album、radio、podcast（:599-603）。插件可覆盖标题与图标（:608-636）。

侧栏结构自上而下：源切换器（多源时，24 高 r7，:655-663）→ 搜索框（高 32，ui_suggest.go:60-90）→ 导航项 →（有搜索结果时追加「搜索结果」，ui_main.go:232-234）→「我的歌单」「收藏的歌单」两组可折叠列表（:235-238；ui_account.go:415）→ 底部账户行（ui_account.go:280-335）。

导航项：高 34、px 10、图标 17、文字 13、r8、图标与文字 gap 10；选中 = `press` 底 + 文字/图标 accent，悬停 = `hover` 底，120ms 过渡（ui_main.go:245-266）。

### 2.2 壳层（ui_main.go:48-70）

`Row[ 侧栏 232 | 内容 grow ]`；内容底色 `bg`，**悬浮**其上的顶栏与播放栏（绝对定位，滚动内容从下方穿过）。内容页滚动内边距见 §1.5。

### 2.3 顶栏行为（ui_main.go:355-372, 308-318）

高 40。默认完全透明；当内容滚到其下方时出现 frosted 底 + 底部 hairline，180ms 淡入；指针悬停也触发。返回/前进按钮 18px 图标、muted 色、位于左侧 20px，无历史时返回按钮 opacity 0.35 且禁用。Now Playing 的顶栏只放一个向下 chevron（22px，white 70%），悬停才显现（200ms）。

### 2.4 播放栏（ui_main.go:281；ui_player.go:347-447, 313-343）

高 84，px 20，三段式 gap 20：

- **左（grow 1，最小 150）**：48 封面（r8，shadow 2/8）→ 歌名 13.5/600 单行 → 艺人 13 muted（可点）；整块点击打开 Now Playing，封面悬停时叠 35% 黑 + 向下箭头；无曲目时显示 48 灰块（`hover` 底、音符图标 18 faint）+「未在播放」13 muted。
- **中（grow 1）**：transport 行（shuffle/repeat 图标 ×0.62、prev/next ×0.85、play ×1.25，基准 size 28；启用色 white，禁用 `white 45%`，激活（随机/循环）用 accent）→ 进度条（高 14 命中区，轨道 4、悬停 6，轨道 `press`、已播 accent、把手悬停才出现）→ 两端时间 10.5 tnum faint，宽 34。
- **右（grow 1，右对齐，最小 3×32+2×4）**：歌词 16 / 队列 16 / 音量 popover（fill `#F6F6F6`、描边 0.5、shadow 8/24）。

### 2.5 Now Playing 全屏层（ui_nowplaying.go:241-430）

背景 `#0B0B0D`；顶部 40 收起条 + 28 高 tabs 行（与封面左缘对齐）；左列：封面（方形，r 与播放栏封面 morph）→ 标题 22/700 −0.3 → 艺人 15 `white 60%` → like / more（22，`white 70%`）→ 进度条（高 16，轨道 `white 18%`，已播 `white 90%`）→ 时间行 11/`white 50%`（左已播、右 `-剩余`、中间无损徽章）→ transport（28）→ 音量滑杆（宽 180，`white 70%` / 轨道 18%）。右列 pane，tabs 依次为「歌词 Lyrics / 律动 Stage / 待播清单 Up Next / 选歌 Wall / 评论 Comments」；播客为「简介 About / 字幕 Captions / 待播清单 Up Next」（ui_nowplaying.go:486-500）。桌面 ⌘1…⌘9 切 pane（ui_main.go:107-111），Android 用 tabs。

---

## 3. 逐屏规格

### 3.1 首页（ui_pages.go:50-111；ui_daily.go:23-121）

- 有插件 `home()` → 走通用 browse 行；否则「最近播放 + 你的歌单」；都没有 → 空态。
- **主推卡（feature card）**：高 212、r24、内边距 x32、左列 gap 6：日期 12/600 +0.3 `white 66%` → 分区标题 **32/800 −0.8 lh1.15** → 艺人串 13 `white 80%` 单行 → 动作行（白色播放按钮：高 38、最小宽 112、r999、字 14/650、墨色 `#1D1D1F`、shadow 6/16/−6 35%；未登录时加一个 `white 16%` 底的登录 pill）。右侧是封面堆叠（156 方图 r12）与唱片；宽屏才显示前几首曲目列表（ui_daily.go:91-110, 315-370, 407-447）。
- **主推卡配色**：从封面 tint 推 `deep=hsv(h,s,.24)`、`rich=hsv(h+16,s×.9,.5)`、`glow=hsv(h+32,s×.55,1)`；无封面/低饱和时用品牌红 `h=356 s=.74`（ui_daily.go:185-199）。渐变 100°（Oklab）：`deep@10% → rich@85% → rich.Mix(deep,.3)@100%`；其上叠模糊封面 28% 白；左侧压暗 `deep 85%→0@60%`；1px `white 10%` 描边；日期数字 240/800 −12 `white 5.5%`（:201-242）。
- **分区行**：标题 19/700 −0.3，上 34 / 下 14；内容为曲目行列表或卡片网格；图卡网格最小列宽 132/150/156、gapX 20 gapY 28。
- **卡片（playlist/show/album/artist）**：Column gap10；封面 1:1、r10；悬停上浮 3、阴影 2/8/−2 → 12/28/−6；标题 13/500 最多 2 行 lh1.35；副行 12 muted 单行、上边距 −6；右下角 38 圆形播放钮（accent 底、白图标、shadow 4/12，默认 scale .8 + y6 + 淡出，悬停/卡片悬停时 scale1/1.05）（ui_pages.go:196-256）。
- **空态**：72 圆角 20 `hover` 底 + 32 图标 muted → 22/700 −0.3 标题 → 13.5 muted lh1.45 maxWidth 420 说明 → pill 按钮（ui_pages.go:121-160）。

### 3.2 Browse 页（ui_browse.go:74-120, 132-170）

- 页头无图：sub 12/600 +0.4 muted，标题 30/700 −0.6，可选 2 行说明 13 muted lh1.45；有图：212 圆形封面 + 标题 30/800 −0.6。
- 行类型：feature / title / track / albums / artists / playlists / shows / stations / links / empty（:132-145），逐行懒构建；分区「更多」跳到 `morePage`。
- 见「更多」的链接卡：横排封面 + 标题 13/500 + 副行 12 muted。

### 3.3 搜索（ui_suggest.go:60-172；ui_search.go:17-25, 183-225）

- **入口**：侧栏搜索框，高 32、r9；聚焦态 = `bg` 底 + accent 60% 描边 + accent 25% 的 3px 外光晕；静置 = `hover` 底 + hairline 描边；放大镜 14 muted 距左 10；占位「搜索」13；有内容时右侧 22×22 清除钮。
- **建议弹层**：宽同输入框、r12、shadow 8/24/−4、0.5px hairline；分组标题 11/600 faint（「搜索历史」「搜索建议」）；选项高 26、r6、px8、13sp，高亮 `press`；空查询时列出历史 + 「清空搜索历史」，有查询时首项是「搜索“query”」再是远端建议，分隔线 `pop.edge`（ui_suggest.go:112-150）。
- **结果页**：heading = `“query”`（30/700 −0.6）+ 副标题「搜索」或「搜索 · N 首」；tab 用分段控件：高 24、内缩 2、胶囊 r5（外形 r6），文字 13，浅色轨道 `black 6%` / 胶囊白 / 分隔 `black 13%`，深色 8%/24%/13%，切换 280ms 弹簧（ui_kit.go:284-322）。类型：歌曲 / 歌手 / 专辑 / 歌单 / 播客（ui_search.go:17-25）。
- **分页**：列表底部 56 高，加载中「正在加载…」12.5 muted，失败显示错误 + 「重试」；距底一屏内自动取下一页（ui_kit.go:339-356）。空结果「没有找到相关结果」13 muted 左右留 40（ui_search.go:169-173）。

### 3.4 资料库

- **我喜欢的音乐**：页头 212 方形 r14，红色渐变 `oklch(.72 .17 10) → oklch(.58 .22 350)`（135°，Oklab），中心心形 84 白；右列标签「歌单」12/600 accent → 标题 30/800 → 计数 13 muted → 播放/随机 pill（ui_pages.go:333-360）。
- **收藏的专辑 / 播客 / 电台**：heading + 卡片网格（最小列宽 150/156），空态文案见 §6。
- **歌单列表**：在侧栏两组内，组标题 11/600 faint 可折叠，行高 28，13sp，悬停 `press`，右侧更多菜单（ui_account.go:415-478）。

### 3.5 详情页（专辑 / 歌单 / 歌手 / 节目 / 电台）

共同页头（ui_album.go:63-105；ui_pages.go:276-300；ui_artist.go:197-240）：

- `Row`，底对齐（End），gap 28，底距 26。
- 封面 212×212、r14、shadow 18/40/−10；歌手为圆形（半径 = 宽/2，ui_artist.go:197-203）。
- 右列 gap 8：类型标签 12/600 accent（专辑/歌单/歌手）→ 标题 **30/800 −0.6，最多 2 行，lh1.2** → meta 13 muted（歌手 · N 首 / 发行年）→ 描述 12.5 muted、最多 2 行、lh1.45、上边距 2 → 动作行（上边距 10，gap 10）：`播放` pill（accent 底、onAccent 字、图标 14）、`随机播放` pill（`hover` 叠在 bg 上、text 字）、`保存/已收藏` pill、`更多` 图标按钮 18 muted。
- 其后是曲目行列表（§3.6），列表容器左右 28、底部 32/48。

### 3.6 曲目行（ui_pages.go:476-540）

高 54、px12、gap14、r10。状态：播放中 = accent 9% 底 + 歌名 accent；悬停 = `hover` 底（100ms）；不可用 = 整行 opacity 0.42。

列顺序：
1. 序号区 26 宽居中 —— 播放中显示 16×14 均衡器（accent 4 条柱）；悬停显示 13 播放三角 text 色；否则序号 13 tnum faint。
2. 封面 38×38 r6（列表开启封面时）。
3. 主列 grow 3、gap 3：歌名 14/500 单行（播放中 accent）+ 艺人 12.5 muted 单行（歌手名可点跳转）。
4. 专辑列 grow 2（仅内容宽 >820 且非专辑页）：12.5 muted 单行。
5. 喜欢钮：命中区 32×32、图标 15；已喜欢常显，否则悬停才显示。
6. 时长 12.5 tnum faint，右对齐，宽 44。

### 3.7 Now Playing

布局与控件见 §2.5。补充：

- **背景**：`#0B0B0D`；线性 180°（Oklab）`top = hsv(h, clamp(s×1.3, .45, .66), .35)` → `foot = hsv(h, min(s×1.05, .7), .24)`；叠一层模糊封面（48×48 上采样、`white 10%`）；再叠 `transparent→black 18%`（55%→100%）压暗底部（backdrop.go:208-239）。无封面 tint = `#787882`，主 accent = `tint.Mix(white,.45)`（ui_nowplaying.go:261-264）。可选「烟雾背景」实验（backdrop.go:20-110，Android 不做）。
- **进场**：从播放栏封面位置放大 morph（340ms 弹簧），内容分层上浮（每层延迟 30–70ms、位移 24px 淡入，ui_nowplaying.go:232-239, 150-190）；减少动效时 200ms 纯淡入。
- **待播清单（Up Next）pane**：行高 58、px10、gap14、r12；播放中 `white 12%` 底，悬停 `white 8%`；封面 42 r7；标题 14/600；艺人 12.5 `white 55%`；时长 12 tnum `white 40%`（ui_nowplaying.go:820-860）。
- **歌词 pane**：行 30/800 lh1.25；未唱 `white 28%`，当前 100%，悬停 55% 且底色 `white 6%`；缩放 0.94→1（原点左中），点击跳转；当前行滚到约 1/3 高度（ui_nowplaying.go:642-700）。
- **律动（Stage）pane（全屏歌词）**：字号 `min(64, max(36, w×.045))`，逐字点亮（未唱 38% → 100%），翻译 18/600 `white 72%`，无歌词「纯音乐，请欣赏」28/800 `white 40%`；底部 slim bar 放进度与 transport（ui_stage.go:98-160；ui_nowplaying.go:552-580）。

### 3.8 设置

桌面为独立窗口（侧栏 200 + 内容），Android 改为整页：分组卡片 r12，`bg` 底 + 1px hairline 边框，行间 1px hairline 分隔；行 minHeight 48、padding 12/16、gap 16：标签 13.5/500 + 说明 12 muted lh1.4 + 右侧控件（开关/下拉/按钮）；分组标题 12/600 muted、margin 14/4/4/4（ui_settings.go:288-313）。分区：通用 / 插件 / 刘海播放器 / 播放 / 缓存 / 播客 / 实验 / 更新（ui_settings.go:323-480）。搜索设置可过滤（:180-206）。

### 3.9 账户与登录

- 账户行：高 40、px8、gap10、r10；头像 26 圆形（无头像 = `press` 底 + 14 图标 muted），名字 13/600，右侧上下箭头 13 faint；悬停 `hover` 底（ui_account.go:280-335）。未登录：显示「登录 %s」13/500 muted + 圆形占位，点击弹登录。
- 登录弹窗：宽 360、padding 24、r18、`bg` 底 + 1px hairline + shadow 24/60/−12 40% 黑；spring 入场 scale .94（ui_account.go:195-210）。标题 17/700，正文 13 muted lh1.45；二维码区 208 白底 r12（:236-242）；按钮右对齐 gap 10。
- Android：`AlertDialog`/`Dialog` 承载；二维码需白底（不受深色主题影响）。

### 3.10 加载 / 空 / 错误

- 骨架：见 §1.9；详情页骨架 = 212 封面方块 + 标题骨线 + 6 行曲目骨线。
- 空态：`empty(msg)` = 13 muted、上下 padding 40、水平居中（ui_artist.go:193-195）。
- 错误：整页居中，13 muted、maxWidth 520、居中，下方按钮组 gap 10：补救按钮（登录 / 去设置）+「重试」（ui_main.go:416-430）。

---

## 4. MyGo 的视觉签名 → 手机 + M3 转译

| # | 签名 | 桌面实现 | Android 方案 |
|---|---|---|---|
| 1 | 232 扁平侧栏，无边框无分组线 | ui_main.go:13, 58-61, 222 | `NavigationBar`（5 项，正好等于默认项数）+ `NavigationRail`（≥600dp）；歌单分组放 `NavigationDrawer`/二级页。选中态：accent 文字/图标 + `press` 底（不用 M3 的胶囊指示器，改 `NavigationBarItem` 自定义 indicator 为 r8 满宽条） |
| 2 | 悬浮播放栏，内容从下方穿过 | ui_main.go:66-75, 281 | `Scaffold(bottomBar = Column { MiniPlayer; NavigationBar })`；MiniPlayer 高 64、封面 40 r8、顶部 2dp `LinearProgressIndicator`；点击 → 全屏 Now Playing |
| 3 | 滚动边缘毛玻璃（hard edge） | ui_main.go:355-372；180ms | `TopAppBar` 下方 16dp `verticalGradient(bg→transparent)` + `animateFloatAsState(180ms)`；API31+ 加 `Modifier.blur(24.dp)` |
| 4 | 封面驱动配色（主推卡、NP 背景） | ui_daily.go:185-242；backdrop.go:208-239 | 从 Coil bitmap 取主色（自写 1k 采样量化）+ 上述 HSV 公式；渐变用 `Brush.linearGradient`（自定义 Oklab lerp helper，8 段采样近似 Oklab） |
| 5 | 大字号 + 负字距的编辑感标题 | ui_pages.go:31；ui_album.go:73 | `TextStyle(fontSize = 30.sp, fontWeight = Black, letterSpacing = (-0.6).sp)`；装饰日期用 `displayLarge` 自定义 88sp/800/−2sp/`white 5.5%` |
| 6 | 骨架屏而非转圈 | ui_skeleton.go:41-53 | `rememberInfiniteTransition` + 1.6s 平移的 260dp 白色窄高斯带；`Modifier.shimmer()` |
| 7 | 分段控件（白胶囊浮在灰轨上） | ui_kit.go:284-322 | M3 `SingleChoiceSegmentedButtonRow` 高度压到 32dp，或自绘 track+胶囊（推荐，尺寸完全可控） |
| 8 | 悬停语言 | hover 4%/6%、100–120ms | 映射为 press/长按：`collectIsPressedAsState()` + `animateColorAsState(120ms)`；列表禁用默认 ripple 泛白过大问题（`LocalRippleConfiguration` 或 `indication = null`） |
| 9 | 0.5–1px hairline 与极轻描边 | ui_kit.go:63；ui_player.go:191 | `BorderStroke(0.5.dp, hairline)`；深色下 M3 默认 outline 太重，必须用 7% 白 |
| 10 | 统一的空态 / 错误 / 骨架三件套 | ui_pages.go:121-160；ui_main.go:416-430 | 抽 `EmptyState(icon,title,desc,action)` / `ErrorState(msg,onRetry)` / `SkeletonList()` 三个 composable 复用 |

---

## 5. M3 映射表

### 5.1 ColorScheme ← MyGo 调色板

| M3 role | Light | Dark |
|---|---|---|
| `primary` | `#E2333C` | `#F45058` |
| `onPrimary` | `#FFFFFF` | `#FFFFFF` |
| `primaryContainer` | `#FDEDED`（accent 8%） | `#38201F`（accent 16% 叠 bg） |
| `onPrimaryContainer` | `#A8232A` | `#FFD9DA` |
| `secondary` / `onSecondary` | `#F5F5F5` / `#1D1D1F`（hover 底作次级按钮） | `#2A2A2C` / `#F5F5F7` |
| `surface` | `#FFFFFF` | `#141416` |
| `onSurface` | `#1D1D1F` | `#F5F5F7` |
| `surfaceVariant` | `#F4F4F6` | `#1C1C1F` |
| `onSurfaceVariant` | `#86868A` | `#8C8C93` |
| `surfaceContainer` / `ContainerHigh` | `#F5F5F5` / `#EBEBEB` | `#222224` / `#2C2C2D` |
| `outline` | `#EDEDED`（hairline） | `#242426` |
| `outlineVariant` | `#F0F0F2` | `#2A2A2D` |
| `background` / `onBackground` | `#FFFFFF` / `#1D1D1F` | `#141416` / `#F5F5F7` |
| `error` | `#C42B33` | `#FA6F71` |
| `scrim` | `rgba(0,0,0,.35)` | `rgba(0,0,0,.55)` |
| `surfaceTint` | `#E2333C` 但 **tonal elevation 全部设为 0** | 同 |

注意：MyGo 没有「elevation 提亮」，深色卡片一律同底色 + hairline；`surfaceTint = transparent` 或 elevation 0，否则 M3 默认会在深色下把 Card 提亮成灰蓝。

### 5.2 Typography ← 字号

| M3 role | 值 | 对应 |
|---|---|---|
| `displayLarge` | 88sp / 800 / −2sp / lh 1.0 | 首页装饰日期（桌面 112–240） |
| `displaySmall` | 32sp / 800 / −0.8 / lh 1.15 | 首页主推标题 |
| `headlineLarge` | 30sp / 800 / −0.6 / lh 1.2 | 详情页标题 |
| `headlineMedium` | 30sp / 700 / −0.6 | 页面 heading |
| `headlineSmall` | 22sp / 700 / −0.3 | NP 歌名、空态标题 |
| `titleLarge` | 19sp / 700 / −0.3 | 分区标题 |
| `titleMedium` | 17sp / 700 | 弹窗标题 |
| `titleSmall` | 15sp / 400 | NP 艺人 |
| `bodyLarge` | 14sp / 500 | 曲目名 |
| `bodyMedium` | 13sp / 400 / lh 1.45 | 正文、说明 |
| `bodySmall` | 12.5sp / 400 | 行副文、时长 |
| `labelLarge` | 14sp / 600 | pill 按钮 |
| `labelMedium` | 12sp / 600 / +0.4sp | 标签、组标题 |
| `labelSmall` | 11sp / 600 / +0.6sp | 徽章、建议分组 |

字体族：桌面用系统字体（-apple-system / Segoe / system-ui），Android 用 `FontFamily.Default`（Roboto），中文回退思源黑/系统 Noto Sans SC；不要引入衬线（衬线只出现在营销站的标题 Fraunces/Noto Serif SC，website/src/styles.css:28）。

### 5.3 Shapes ← 圆角

`extraSmall 4` · `small 8` · `medium 12` · `large 14` · `extraLarge 24`；另需 `RoundedCornerShape(999.dp)` 与 6/9/10 三个非标值，直接写 `RoundedCornerShape(n.dp)`。

### 5.4 组件选型

| 元素 | M3 组件 |
|---|---|
| 底部导航 | `NavigationBar` + `NavigationBarItem` |
| 顶栏（滚动出现） | `TopAppBar` + `Modifier.blur`/渐变 scrim，`scrollBehavior = pinned` |
| 页面外壳 | `Scaffold`（topBar / bottomBar / snackbarHost） |
| 分段 tab（搜索类型、NP tabs） | `SingleChoiceSegmentedButtonRow` 或自绘胶囊 |
| 主行动（播放/随机/保存） | `FilledTonalButton`（随机/保存）+ `Button`（播放，accent） |
| 图标按钮 | `IconButton`（32/40 命中区）+ 自绘 999 底 |
| 喜欢 | `IconToggleButton` |
| 更多菜单 | `DropdownMenu` + `DropdownMenuItem`（r12、elevation 0、hairline 描边） |
| 卡片 | `Card(colors = surface, elevation = 0, shape = 10.dp)` + `AsyncImage` |
| 网格 | `LazyVerticalGrid(GridCells.Adaptive(150.dp), gap 20/28)` |
| 曲目行 | 自绘 `Row`（不用 `ListItem`：需要序号/封面/喜欢/时长四列） |
| 详情页头 | 自绘 `Row`；无 `CollapsingToolbar`（桌面是静态头，滚动不折叠） |
| 筛选标签 | `FilterChip` / `AssistChip`（仅在需要 chip 语义处；搜索类型用分段控件） |
| Mini player | 自绘 `Surface` + `LinearProgressIndicator` |
| Now Playing | `ModalBottomSheet(skipPartiallyExpanded = true)` 或全屏 `NavHost` + `SharedTransitionLayout` |
| 登录 | `AlertDialog`（自定义 shape 18dp） |
| 开关/选择 | `Switch` / `RadioButton` / `DropdownMenu` |
| 轻提示 | `Snackbar`（对应桌面 toast，ui_main.go:78-80） |
| 空/错/骨架 | 自绘 `EmptyState` / `ErrorState` / `shimmer()` |

### 5.5 Theme 骨架

```kotlin
private val Light = lightColorScheme(
  primary = Color(0xFFE2333C), onPrimary = Color.White,
  primaryContainer = Color(0xFFFDEDED), onPrimaryContainer = Color(0xFFA8232A),
  background = Color(0xFFFFFFFF), onBackground = Color(0xFF1D1D1F),
  surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D1D1F),
  surfaceVariant = Color(0xFFF4F4F6), onSurfaceVariant = Color(0xFF86868A),
  outline = Color(0xFFEDEDED), outlineVariant = Color(0xFFF0F0F2),
)
private val Dark = darkColorScheme(
  primary = Color(0xFFF45058), onPrimary = Color.White,
  background = Color(0xFF141416), onBackground = Color(0xFFF5F5F7),
  surface = Color(0xFF141416), onSurface = Color(0xFFF5F5F7),
  surfaceVariant = Color(0xFF1C1C1F), onSurfaceVariant = Color(0xFF8C8C93),
  outline = Color(0xFF242426), outlineVariant = Color(0xFF2A2A2D),
)
// 叠层用 Color(0x0A000000)/0x11FFFFFF 等直接叠在 surface 上；不要用 M3 的 tonal elevation。
```

---

## 6. 中文文案清单（`internal/i18n/zh_cn.go`，Android 复制为 `strings.xml`）

导航与分组：`Home` 首页 · `Liked Songs` 我喜欢的音乐 · `Saved Albums` 收藏的专辑 · `Radio` 电台 · `Podcasts` 播客 · `Search Results` 搜索结果 · `My Playlists` 我的歌单 · `Saved Playlists` 收藏的歌单 · `Settings` 设置 · `Account` 账户。

播放与队列：`Play` 播放 · `Pause` 暂停播放 · `Playing` 正在播放 · `Resume` 继续播放 · `Not Playing` 未在播放 · `Previous` 上一首 · `Next` 下一首 · `Shuffle` 随机播放 · `Repeat` 循环播放 · `Queue` 播放队列 · `Up Next` 待播清单 · `Lyrics` 歌词 · `Stage` 律动 · `Wall` 选歌 · `Captions` 字幕 · `Open Now Playing` 打开正在播放 · `Close` 关闭 · `Buffering…` 正在缓冲… · `Lossless` 无损 · `No lyrics. Enjoy the music.` 纯音乐，请欣赏 · `About This Episode` 节目简介 · `Comments` 评论 · `About` 简介。

通用动作：`Back` 返回 · `Forward` 前进 · `Retry` 重试 · `Cancel` 取消 · `Save` 保存 · `Saved` 已收藏 · `Delete` 删除 · `Edit` 编辑 · `More` 更多 · `Like` 喜欢 · `Like/Unlike` 喜欢/取消喜欢 · `Added to Liked Songs` 已添加到我喜欢的音乐 · `Add to Playlist` 添加到歌单 · `Added to the playlist` 已添加到歌单 · `New Playlist` 新建歌单 · `Copy Link` 复制链接 · `Copy Title` 复制歌名 · `Go to Album: %s` 查看专辑：%s · `Go to Artist: %s` 查看歌手：%s · `Go to Podcast` 查看播客 · `Follow` 关注 · `Following` 已关注。

搜索：`Search` 搜索 · `Clear Search` 清除搜索 · `Recent Searches` 搜索历史 · `Clear Recent Searches` 清空搜索历史 · `Suggestions` 搜索建议 · `Search for “%s”` 搜索“%s” · `Nothing found` 没有找到相关结果 · `%d songs found` 找到 %d 首歌曲 · `Search · %s` 搜索 · %s。

实体与计数：`Songs` 歌曲 · `Artists` 歌手 · `Albums` 专辑 · `Playlists` 歌单 · `Episodes` 节目 · `Album` 专辑 · `Playlist` 歌单 · `%d songs` %d 首 · `1 song` 1 首 · `1 album` 1 张专辑 · `%d albums` %d 张专辑 · `%d episodes` %d 期 · `Recently Played` 最近播放 · `No albums` 暂无专辑 · `Nothing here yet` 这里还什么都没有。

空态与引导：`Add a Music Folder` 添加音乐文件夹 · `Choose where your music lives, and it will all be here: MP3, FLAC, Ogg, WAV and M4A files, with their tags, covers and lyrics.` 选择存放音乐的位置，歌曲都会出现在这里：MP3、FLAC、Ogg、WAV 与 M4A 文件，连同标签、封面和歌词。 · `Looking for music…` 正在查找音乐… · `No music found` 没有找到音乐 · `Podcasts you subscribe to will show here` 订阅的播客会显示在这里 · `Couldn't load this page` 页面加载失败 · `Couldn't load this album` 专辑加载失败 · `Couldn't load this artist` 歌手加载失败 · `Couldn't load this playlist` 歌单加载失败 · `Loading…` 正在加载…。

账户与登录：`Sign In` 登录 · `Sign in to %s` 登录 %s · `Sign Out` 退出登录 · `Open Settings` 打开设置 · `Language` 语言 · `General` 通用 · `Plugins` 插件 · `Playback` 播放 · `Cache` 缓存 · `Experiments` 实验性功能 · `Updates` 更新。

时间：`just now` 刚刚 · `an hour ago` 1 小时前 · `yesterday` 昨天 · `%d minutes ago` %d 分钟前 · `%d hours ago` %d 小时前 · `%d days ago` %d 天前。首页问候：`Good morning` 早上好 · `Good afternoon` 下午好 · `Good evening` 晚上好 · `Good night` 夜深了；日期格式 `Date + 星期X`（i18n.go:131-136）。

完整约 300 条键值见 `internal/i18n/zh_cn.go:7-371`，英文原文即 `T("…")` 调用点的 key。

---

## 7. 与当前 Android 实现的差异（改造清单）

1. `Ui.kt:19-23` 现为 `BG #121212 / SURFACE #1E1E1E / TEXT #FFFFFF / SUBTEXT #AAAAAA / ACCENT #E60026`，全部作废，改用 §1.1（尤其深色底是 `#141416` 而非 `#121212`，强调色是 `#F45058` 而非 `#E60026`）。
2. 现有 `LinearLayout + toolbar + 全屏 push 页` 结构 → `Scaffold + NavigationBar + MiniPlayer`，页面进 `NavHost`。
3. 缺 Compose 依赖（§0.1）；`coil` 需换成 `coil-compose`。
4. 现有硬编码 `16/14/12` 间距与 `8dp` 圆角需按 §1.5/§1.6 重排；文字色一律走 `MaterialTheme.colorScheme`。
5. 现有 `Ui.text(... bold = true)` 的单字重 → 按 §1.4 建立 14 级 `TextStyle`。
