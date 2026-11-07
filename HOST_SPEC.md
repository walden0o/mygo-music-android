# MyGo Music 插件宿主规格（从官方源码提炼，2026-11-07）

来源：mygo-music-main/internal/plugin/{host,plugin}.go、internal/music/music.go、examples/demo-plugin、mygo-music-netease-cloud-music/index.js + plugin.json。安卓壳按此实现，插件原文件不改。

## 插件包
目录含 plugin.json + main JS（+ 可选 locales/<lang>.json、图标）。
plugin.json：id, name, version, main, playback("stream"|"remote"), permissions{network:[主机名], process:[]}, settings[{key,label,help,link,type("select"),options[{value,label}]}], navigation[{builtin: home|liked|savedAlbums|stations|shows 或 page+title+icon}]。

## JS 契约
- ES 模块、**具名导出**、无 import（官方两插件均如此）；每个导出可选，缺失即宿主隐藏对应功能。
- 每个函数只收一个对象参数，可 async；抛 Error 且带 .code ∈ signIn/setup/unavailable/unsupported 时宿主按语义处理，其余显示 message。
- 数据类型（JSON 字段）：Artist{id,name,imageUrl,following}、Album{id,name,imageUrl,artists,released,saved}、Track{id,name,artists,album,duration(ms),url,unavailable,showId,description,published}、Playlist{id,name,imageUrl,description,owner,trackCount,mine,saved,fixed,tracks}、Account{id,name,imageUrl,page}、SearchResult{tracks|albums|artists|playlists|shows, more}、Page{tracks,more}、Browse{title,subtitle,imageUrl,description,sections[Section{title,tracks,albums,artists,playlists,shows,stations,links[{title,subtitle,imageUrl,page}],morePage,empty,pageTitle,subtitle}]}、Stream{url,type,quality,bitRate,size,peak,gain,headers{}}、Lyrics{lrc,translation,lines[{start,end,text,translation,words[{start,end,text}]}]}、Comment{id,author,avatarUrl,text,time,likes,liked}、Show/Station 见 music.go。

## 宿主→插件调用（函数名=导出名，参数/返回）
account()→Account|null；signIn()→Account（期间经 signInPrompt 展示）；signOut()；setSetting({key,value})；
home()→Browse；browse({page})→Browse；
search({query,kind,offset})→SearchResult（kind: tracks/albums/artists/playlists/shows）；suggest({query})→string[]；
playlists()→Playlist[]（无 tracks）；playlist({id})→Playlist（含 tracks）；
liked({offset})→Page；isLiked({ids})→{id:bool}；like({id,on})；likeEpisode({id,on})；
recent()→Track[]；played({id})；
createPlaylist({name})→Playlist；renamePlaylist({id,name})；deletePlaylist({id})；addToPlaylist({id,trackIds})；removeFromPlaylist({id,trackIds})；movePlaylistTrack({id,from,to})；savePlaylist({id,on})；
album({id})→{album,tracks}；artist({id})→{artist,tracks,albums}；followArtist({id,on})；saveAlbum({id,on})；savedAlbums({offset})→{albums,more}；
shows()→Show[]；show({id,offset})→{show,episodes,more}；subscribeShow({id,on})；
stations()→Station[]；stationNext({id})→Track[]；
comments({trackId,offset})→{comments,total,more}；likeComment({trackId,commentId,on})；postComment({trackId,text})→Comment；
lyrics({id})→Lyrics；stream({id,quality})→Stream。

## 插件→宿主（全局）
- console.log/info/warn/error。
- fetch(url, {method,headers,body,signal,redirect})→Response{ok,status,headers{get,getSetCookie()},json(),text()}；宿主按 permissions.network 做主机白名单（moejs webapi.AllowHosts）；redirect:"error" 要兑现；signal 来自 AbortController。
- 引擎内建：Promise/async、URL、URLSearchParams、AbortController、setTimeout/clearTimeout、Map/Set、Object.fromEntries、padStart 等 ES2020。
- host.platform / host.arch / host.dir / host.dataDir
- host.storage.get(key)→JSON 值或 undefined；set(key, JSON 值)（落盘 storage.json）；remove(key)
- host.language()→"en"|"zh-CN"；host.t(key)（查插件 locales）；host.setting(key)→string
- host.openURL(url)（仅 http/https）
- host.signInPrompt({qr, text})：同步无返回，登录流程中宿主展示（网易云：qr=二维码内容字符串）
- host.crypto.md5(str)→小写 hex；host.crypto.aesEcbEncrypt(keyStr, textStr)→小写 hex（AES/ECB/PKCS7）
- host.removeDataDir(name)（仅自身数据目录内）
- host.waitForRedirect({port,path,timeout(ms),page})→Promise<query 对象>（浏览器回跳登录用，MVP 可后置）
- host.webSignIn({url,cookiesOf,until[],userAgent})→Promise<{cookie}>（WebView 登录，网易云 loginMethod=web 时用；MVP 先做 qr，此为第二期）
- host.spawn(...)：process 权限，MVP 不实现（网易云/YouTube 均不需要）

## 网易云插件（v0.1.8）对宿主的具体依赖
- storage 键：cookies（对象）、account、device（52 位 hex 持久化）。
- settings：quality（select: standard/higher/exhigh/lossless/hires，默认 exhigh）、loginMethod（select: qr 默认 / web）。
- fetch 只经一个 request() 助手：18s AbortController 超时、检查 response.ok、读 headers 的 Set-Cookie 并回存 storage。
- signIn：qr 模式轮询（2s 间隔、总 240s）期间多次调 signInPrompt 更新状态文案；成功返回 {id,name,imageUrl}。
- stream 返回 {url(强制 https), type, bitRate, size, gain, peak, headers{UA, Referer}} → 宿主用 Media3 带 headers 播放；freeTrialInfo 非空时插件已标 unavailable/抛错。
- playback="stream"；permissions.network: music.163.com, interfacepc.music.163.com。
