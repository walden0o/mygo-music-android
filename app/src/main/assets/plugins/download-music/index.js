// MyGo Music 插件 — 音乐下载 / Music Download
// 数据来源：https://flac.music.hi.cn/ （网易云 wyy / 酷我 kuwo）
//          + B站（bilibili，走官方接口取真实音频）
// 导出能力：browse, search, album, stream, lyrics
//
// 架构说明：
//   search   —— 按设置里的 platform（all / wyy / kuwo / bili）检索歌曲，支持分页（offset）。
//   stream   —— 播放 / 下载共用。按 quality 选择音源：
//               lossless → flac；high → mp3 320；standard → mp3 128。
//               可在插件设置里用 quality 覆盖 App 全局音质；留空则跟随 App。
//               返回的 type 决定下载文件扩展名，因此“无损”质量会下载到 .flac 文件。
//   lyrics   —— 拉取 LRC 歌词。
//   album    —— 专辑详情 + 曲目。
//   browse   —— 榜单页（网易云歌单 + B站音乐区排行榜，链接带封面）。
//
// 反爬要点：getUrl / getLyric 需要 time + sign，且会过期；每次调用前先用
// act=song 取一次最新的 time/sign/minfo。直链音频需要带 Referer 才能下载。

const API_BASE = "https://flac.music.hi.cn/ajax.php?act=";
const PAGE = 20;
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

// 网易云榜单（歌单 id）
const CHARTS = [
  { title: "热歌榜", id: "3778678" },
  { title: "新歌榜", id: "3779629" },
  { title: "飙升榜", id: "19723756" },
  { title: "原创榜", id: "2884035" },
];

// 供 host.t 翻译
function t(s) {
  try {
    return host.t(s);
  } catch (e) {
    return s;
  }
}

function fail(code, message) {
  const e = new Error(message);
  e.code = code; // signIn | setup | unavailable | unsupported
  return e;
}

// 表单编码（不依赖 URLSearchParams）
function enc(params) {
  return Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== "")
    .map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(params[k])))
    .join("&");
}

async function api(act, params) {
  let res;
  try {
    res = await fetch(API_BASE + act, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        Accept: "application/json",
      },
      body: enc(params || {}),
    });
  } catch (e) {
    throw fail("unavailable", act + ": " + ((e && e.message) || e));
  }
  if (!res.ok) throw fail("unavailable", act + ": HTTP " + res.status);
  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw fail("unavailable", act + ": invalid JSON");
  }
  if (!json || json.code !== 0) {
    throw fail("unavailable", (json && json.msg) || act + " failed");
  }
  return json.data;
}

// 读取设置里的搜索来源：wyy / kuwo / all（默认 all）
function currentMode() {
  let v = "";
  try {
    v = String(host.setting("platform") || "").trim().toLowerCase();
  } catch (e) {}
  if (/^(kuwo|k|2|酷我|酷我音乐)$/.test(v)) return "kuwo";
  if (/^(bili|bilibili|b站|哔哩|哔哩哔哩|bilibili音乐|3)$/.test(v)) return "bili";
  if (/^(wyy|w|1|netease|网易|网易云|网易云音乐)$/.test(v)) return "wyy";
  return "all";
}

// 插件内“下载音质”设置：覆盖 App 的全局音质；留空则跟随 App
function settingQuality() {
  let v = "";
  try {
    v = String(host.setting("quality") || "").trim().toLowerCase();
  } catch (e) {}
  if (/^(lossless|flac|无损|sq)$/.test(v)) return "lossless";
  if (/^(high|320|hq|高音质|高)$/.test(v)) return "high";
  if (/^(standard|128|std|标准)$/.test(v)) return "standard";
  return "";
}

// id 形如 "<platform>:<songId>" / "<platform>:<albumId>"
function parseId(id) {
  const s = String(id == null ? "" : id);
  const i = s.indexOf(":");
  if (i < 0) return { platform: "", id: s };
  return { platform: s.slice(0, i), id: s.slice(i + 1) };
}

// 接口歌曲对象 → MyGo Track
function songToTrack(platform, it) {
  const albumId = it.album_id ? platform + ":" + it.album_id : "";
  return {
    id: platform + ":" + it.id,
    name: it.name || "",
    artists: [{ id: "", name: it.artist || "", imageUrl: "" }],
    album: {
      id: albumId,
      name: it.album_name || "",
      imageUrl: it.pic_url || "",
    },
    duration: Math.round(Number(it.duration || 0) * 1000), // 秒 → 毫秒
    url: it.link || "",
    unavailable: false,
  };
}

// 按绝对下标 [start, start+count) 拉取某平台搜索结果
async function fetchRange(platform, keyword, start, count) {
  const out = [];
  let total = 0;
  if (count <= 0) return { items: out, total };
  const firstPage = Math.floor(start / PAGE) + 1;
  const lastPage = Math.floor((start + count - 1) / PAGE) + 1;
  for (let p = firstPage; p <= lastPage; p++) {
    let d;
    try {
      d = await api("search", { platform: platform, keyword: keyword, page: p, size: PAGE });
    } catch (e) {
      break; // 某站失败不影响另一站
    }
    total = Number(d && d.total) || total;
    const list = (d && d.list) || [];
    if (list.length === 0) break;
    for (let i = 0; i < list.length; i++) {
      const abs = (p - 1) * PAGE + i;
      if (abs >= start && abs < start + count) out.push(list[i]);
    }
    if (list.length < PAGE) break;
  }
  return { items: out, total: total };
}

async function totalOf(platform, keyword) {
  try {
    const d = await api("search", { platform: platform, keyword: keyword, page: 1, size: PAGE });
    const list = (d && d.list) || [];
    if (list.length === 0) return 0; // 处理“total>0 但列表为空”的站点怪癖
    return Number(d.total) || list.length;
  } catch (e) {
    return 0;
  }
}

// ===================== B站（bilibili） =====================
const BILI = "https://api.bilibili.com";
const BILI_WEB = "https://www.bilibili.com/";
let biliCookieCache = { val: "", exp: 0 };

// 通过 finger/spi 获取 buvid3/buvid4，规避搜索 412 风控
async function biliCookie() {
  const now = Date.now();
  if (biliCookieCache.val && now < biliCookieCache.exp) return biliCookieCache.val;
  try {
    const r = await fetch(BILI + "/x/frontend/finger/spi", {
      headers: { "User-Agent": UA, Referer: BILI_WEB },
    });
    const j = await r.json();
    const d = (j && j.data) || {};
    if (d.b_3) {
      const c =
        "buvid3=" + d.b_3 + "; buvid4=" + (d.b_4 || "") + "; b_nut=" + Math.floor(now / 1000);
      biliCookieCache = { val: c, exp: now + 30 * 60 * 1000 };
      return c;
    }
  } catch (e) {}
  return "";
}

function biliHeaders(cookie) {
  const h = { "User-Agent": UA, Referer: BILI_WEB };
  if (cookie) h.Cookie = cookie;
  return h;
}

function stripTags(s) {
  return String(s == null ? "" : s)
    .replace(/<[^>]*>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .trim();
}

function parseBiliDuration(str) {
  const parts = String(str || "").split(":").map((x) => parseInt(x, 10) || 0);
  let sec = 0;
  for (const p of parts) sec = sec * 60 + p;
  return sec * 1000;
}

function biliPic(p) {
  const s = String(p == null ? "" : p);
  if (!s) return "";
  if (s.indexOf("//") === 0) return "https:" + s;
  if (s.indexOf("http") === 0) return s;
  return "https://" + s;
}

async function biliSearchPage(keyword, page) {
  const cookie = await biliCookie();
  const url =
    BILI +
    "/x/web-interface/search/type?search_type=video&keyword=" +
    encodeURIComponent(keyword) +
    "&page=" +
    page;
  const r = await fetch(url, { headers: biliHeaders(cookie) });
  const j = await r.json().catch(() => ({}));
  if (!j || j.code !== 0) {
    throw fail("unavailable", "bili search: " + (j && (j.message || j.code)));
  }
  const data = j.data || {};
  return { list: data.result || [], total: Number(data.numResults) || 0 };
}

async function fetchBiliRange(keyword, start, count) {
  const out = [];
  let total = 0;
  if (count <= 0) return { items: out, total };
  const firstPage = Math.floor(start / PAGE) + 1;
  const lastPage = Math.floor((start + count - 1) / PAGE) + 1;
  for (let p = firstPage; p <= lastPage; p++) {
    let d;
    try {
      d = await biliSearchPage(keyword, p);
    } catch (e) {
      break;
    }
    total = d.total || total;
    const list = d.list || [];
    if (list.length === 0) break;
    for (let i = 0; i < list.length; i++) {
      const abs = (p - 1) * PAGE + i;
      if (abs >= start && abs < start + count) out.push(list[i]);
    }
    if (list.length < PAGE) break;
  }
  return { items: out, total };
}

function biliToTrack(it) {
  const bvid = it.bvid || "";
  return {
    id: "bili:" + bvid,
    name: stripTags(it.title),
    artists: [{ id: "", name: stripTags(it.author), imageUrl: "" }],
    album: { id: "", name: "Bilibili", imageUrl: biliPic(it.pic) },
    duration: parseBiliDuration(it.duration),
    url: bvid ? "https://www.bilibili.com/video/" + bvid : "",
    unavailable: false,
  };
}

async function biliCid(bvid) {
  const cookie = await biliCookie();
  const r = await fetch(BILI + "/x/player/pagelist?bvid=" + encodeURIComponent(bvid), {
    headers: biliHeaders(cookie),
  });
  const j = await r.json().catch(() => ({}));
  const cid = j && j.data && j.data[0] && j.data[0].cid;
  if (!cid) throw fail("unavailable", "bili: no cid");
  return cid;
}

// B站音轨：30251=Hi-Res(flac) 30250=杜比 30280=192k 30232=132k 30216=64k
function pickBiliAudio(auds, quality) {
  const list = (auds || []).slice().sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0));
  if (list.length === 0) return null;
  const byQ = (ids) => list.find((a) => ids.indexOf(Number(a.id)) >= 0);
  if (quality === "lossless") return byQ([30251, 30250]) || list[0];
  if (quality === "standard") return byQ([30216]) || byQ([30232]) || list[list.length - 1];
  return byQ([30280]) || byQ([30232]) || list[0];
}

async function biliStream(bvid, quality) {
  const cookie = await biliCookie();
  const cid = await biliCid(bvid);
  const url =
    BILI +
    "/x/player/playurl?bvid=" +
    encodeURIComponent(bvid) +
    "&cid=" +
    cid +
    "&fnval=16&fourk=1";
  const r = await fetch(url, { headers: biliHeaders(cookie) });
  const j = await r.json().catch(() => ({}));
  const dash = j && j.data && j.data.dash;
  const auds = (dash && dash.audio) || [];
  const best = pickBiliAudio(auds, quality);
  if (!best) throw fail("unavailable", "bili: no audio stream");
  const u = best.baseUrl || best.base_url;
  if (!u) throw fail("unavailable", "bili: no audio url");
  const headers = { Referer: BILI_WEB, "User-Agent": UA };
  if (cookie) headers.Cookie = cookie;
  return {
    url: u,
    type: "m4a",
    quality: quality,
    bitRate: best.bandwidth ? Math.round(best.bandwidth / 1000) : undefined,
    headers: headers,
  };
}

// B站音乐区排行榜（rid=3 音乐区，type=all）。实测无需 cookie 即可返回。
async function biliRanking() {
  const r = await fetch(
    BILI + "/x/web-interface/ranking/v2?rid=3&type=all",
    { headers: { "User-Agent": UA, Referer: BILI_WEB } }
  );
  const j = await r.json().catch(() => ({}));
  if (!j || j.code !== 0) {
    throw fail("unavailable", "bili ranking: " + (j && (j.message || j.code)));
  }
  return (j.data && j.data.list) || [];
}

// 排行榜条目字段与搜索略有不同：owner.name、duration 为整数秒、pic 为完整 URL
function biliRankToTrack(v) {
  const bvid = v.bvid || "";
  const dur =
    typeof v.duration === "number"
      ? v.duration * 1000
      : parseBiliDuration(v.duration);
  return {
    id: "bili:" + bvid,
    name: stripTags(v.title),
    artists: [{ id: "", name: (v.owner && v.owner.name) || v.author || "", imageUrl: "" }],
    album: { id: "", name: "Bilibili", imageUrl: biliPic(v.pic) },
    duration: dur,
    url: bvid ? "https://www.bilibili.com/video/" + bvid : "",
    unavailable: false,
  };
}

// ===================== 自建后端（go-music-dl，可选） =====================
// 配置 host.setting("backend") 为非空地址后，搜索 / 取流 / 歌词改走该后端，
// 从而支持 QQ / 汽水 / 咪咕 / 酷狗 / 网易云 / 酷我 / B站 等更多来源。
// 后端是独立进程（go-music-dl，AGPL-3），插件只经 HTTP 调用，不内联其代码。
const GO_SOURCES = ["netease", "kuwo", "kugou", "migu", "soda", "qq", "bilibili", "joox", "qianqian", "apple"];

function goAlias(tok) {
  tok = String(tok || "").trim().toLowerCase();
  if (/^(wyy|w|1|netease|网易|网易云|网易云音乐)$/.test(tok)) return "netease";
  if (/^(kuwo|k|2|酷我|酷我音乐)$/.test(tok)) return "kuwo";
  if (/^(bili|bilibili|b站|哔哩|哔哩哔哩|bilibili音乐)$/.test(tok)) return "bilibili";
  if (/^(migu|m|咪咕|咪咕音乐)$/.test(tok)) return "migu";
  if (/^(kugou|kg|酷狗|酷狗音乐)$/.test(tok)) return "kugou";
  if (/^(soda|qishui|汽水|汽水音乐)$/.test(tok)) return "soda";
  if (/^(qq|qqmusic|qq音乐)$/.test(tok)) return "qq";
  if (/^(joox)$/.test(tok)) return "joox";
  if (/^(qianqian|千千|千千音乐)$/.test(tok)) return "qianqian";
  if (/^(apple|applemusic|苹果音乐)$/.test(tok)) return "apple";
  return "";
}

function backendBase() {
  let v = "";
  try {
    v = String(host.setting("backend") || "").trim();
  } catch (e) {}
  if (!v) return "";
  if (!/^https?:\/\//i.test(v)) v = "http://" + v;
  v = v.replace(/\/+$/, "");
  if (!/\/music$/i.test(v)) v = v + "/music";
  return v;
}

// 配置后端时，platform 决定使用哪些后端源；留空默认 netease+kuwo，all=常用六站
function backendSources() {
  if (!backendBase()) return null;
  let v = "";
  try {
    v = String(host.setting("platform") || "").trim();
  } catch (e) {}
  if (!v) return ["netease", "kuwo"];
  if (/^all$/i.test(v)) return ["netease", "kuwo", "kugou", "migu", "soda", "bilibili"];
  const toks = v.split(/[,，\s]+/).filter(Boolean);
  const out = [];
  for (const tk of toks) {
    const s = goAlias(tk);
    if (s && out.indexOf(s) < 0) out.push(s);
  }
  return out.length ? out : ["netease", "kuwo"];
}

function decodeEntities(s) {
  return String(s == null ? "" : s)
    .replace(/&#34;|&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

// 解析 go-music-dl 搜索结果页中的 <li class="song-card" data-...> 条目
function parseSongCards(html) {
  const out = [];
  const re = /<li class="song-card"((?:\s+[a-zA-Z-]+=(?:'[^']*'|"[^"]*"))*)\s*>/g;
  let m;
  while ((m = re.exec(html))) {
    const attrStr = m[1] || "";
    const attrs = {};
    const ar = /([a-zA-Z-]+)=(?:'([^']*)'|"([^"]*)")/g;
    let a;
    while ((a = ar.exec(attrStr))) {
      attrs[a[1]] = decodeEntities(a[2] !== undefined ? a[2] : a[3]);
    }
    if (!attrs["data-id"] || !attrs["data-source"]) continue;
    out.push({
      source: attrs["data-source"],
      id: attrs["data-id"],
      name: attrs["data-name"] || "",
      artist: attrs["data-artist"] || "",
      album: attrs["data-album"] || "",
      cover: attrs["data-cover"] || "",
      duration: Number(attrs["data-duration"] || 0) || 0,
      extra: attrs["data-extra"] || "",
    });
  }
  return out;
}

// 后端歌曲的元数据（含 extra，部分平台取流/歌词必需）存 storage，供后续 stream/lyrics/歌单复用
const GO_META_KEY = "goMeta";
let goMetaCache = null;
function loadGoMeta() {
  if (goMetaCache) return goMetaCache;
  let v = null;
  try {
    v = host.storage.get(GO_META_KEY);
  } catch (e) {}
  if (typeof v === "string") {
    try {
      v = JSON.parse(v);
    } catch (e) {
      v = null;
    }
  }
  goMetaCache = v && typeof v === "object" ? v : {};
  return goMetaCache;
}
function saveGoMeta(key, c) {
  const m = loadGoMeta();
  m[key] = {
    source: c.source,
    id: c.id,
    name: c.name,
    artist: c.artist,
    album: c.album,
    cover: c.cover,
    extra: c.extra,
  };
  try {
    host.storage.set(GO_META_KEY, m);
  } catch (e) {}
}
function getGoMeta(key) {
  return loadGoMeta()[key] || null;
}

// 后端歌曲 → MyGo Track：id = "go:<source>:<id>"
function goCardToTrack(c) {
  const key = c.source + ":" + c.id;
  saveGoMeta(key, c);
  return {
    id: "go:" + key,
    name: c.name || "",
    artists: [{ id: "", name: c.artist || "", imageUrl: "" }],
    album: { id: "", name: c.album || "", imageUrl: c.cover || "" },
    duration: Math.round((c.duration || 0) * 1000),
    url: "",
    unavailable: false,
  };
}

function goMetaToTrack(id, meta) {
  return {
    id: id,
    name: meta.name || "",
    artists: [{ id: "", name: meta.artist || "", imageUrl: "" }],
    album: { id: "", name: meta.album || "", imageUrl: meta.cover || "" },
    duration: 0,
    url: "",
    unavailable: false,
  };
}

async function goSearch(keyword, sources) {
  const base = backendBase();
  const qs = sources.map((s) => "sources=" + encodeURIComponent(s)).join("&");
  const url = base + "/search?q=" + encodeURIComponent(keyword) + "&type=song&" + qs;
  let res;
  try {
    res = await fetch(url, { headers: { "User-Agent": UA, Accept: "text/html" } });
  } catch (e) {
    throw fail("unavailable", "backend search: " + ((e && e.message) || e));
  }
  if (!res.ok) throw fail("unavailable", "backend search: HTTP " + res.status);
  return parseSongCards(await res.text());
}

function goDownloadUrl(meta) {
  const base = backendBase();
  const params = {
    source: meta.source,
    id: meta.id,
    name: meta.name,
    artist: meta.artist,
    album: meta.album,
    cover: meta.cover,
    extra: meta.extra,
    stream: "1",
  };
  const qs = Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== "")
    .map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(params[k])))
    .join("&");
  return base + "/download?" + qs;
}

async function goStream(meta, quality) {
  const url = goDownloadUrl(meta);
  let type = "mp3";
  let size;
  try {
    const r = await fetch(url, { headers: { Range: "bytes=0-0", "User-Agent": UA } });
    const ct = String(r.headers.get("content-type") || "").toLowerCase();
    const cr = String(r.headers.get("content-range") || "");
    const mm = /bytes\s+\d+-\d+\/(\d+)/i.exec(cr);
    if (mm) size = Number(mm[1]);
    else {
      const cl = Number(r.headers.get("content-length") || 0);
      if (!cr && cl > 1) size = cl;
    }
    if (ct.indexOf("flac") >= 0) type = "flac";
    else if (ct.indexOf("mpeg") >= 0 || ct.indexOf("mp3") >= 0) type = "mp3";
    else if (/mp4|m4a|octet-stream/.test(ct)) type = "m4a";
    // 用前若干字节魔数校正：ftyp→m4a，fLaC→flac，ID3→mp3
    try {
      const buf = new Uint8Array(await r.arrayBuffer());
      if (buf.length >= 8) {
        const tag4 = String.fromCharCode(buf[4], buf[5], buf[6], buf[7]);
        if (tag4 === "ftyp") type = "m4a";
        else if (tag4 === "fLaC") type = "flac";
        else if (buf[0] === 0x49 && buf[1] === 0x44 && buf[2] === 0x33) type = "mp3";
      }
    } catch (e) {}
  } catch (e) {}
  return { url: url, type: type, quality: quality, size: size, headers: { "User-Agent": UA } };
}

async function goLyrics(meta) {
  const base = backendBase();
  const params = {
    source: meta.source,
    id: meta.id,
    name: meta.name,
    artist: meta.artist,
    extra: meta.extra,
    format: "lrc",
  };
  const qs = Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== "")
    .map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(params[k])))
    .join("&");
  try {
    const res = await fetch(base + "/lyric?" + qs, { headers: { "User-Agent": UA } });
    if (!res.ok) return { lrc: "" };
    const txt = await res.text();
    return { lrc: String(txt || "").trim() ? txt : "" };
  } catch (e) {
    return { lrc: "" };
  }
}

async function doSearch(keyword, offset) {
  // 配置了后端则优先走 go-music-dl（返回它支持的更多来源）；
  // 后端单次最多每源 ~20 条且不提供分页，故 more=false。
  const backSources = backendSources();
  if (backSources) {
    if (offset >= PAGE) return { tracks: [], more: false };
    const cards = await goSearch(keyword, backSources);
    return { tracks: cards.map(goCardToTrack), more: false };
  }
  const mode = currentMode();
  const count = PAGE;
  if (mode === "bili") {
    const r = await fetchBiliRange(keyword, offset, count);
    return { tracks: r.items.map(biliToTrack), more: offset + count < r.total };
  }
  if (mode === "all") {
    const totals = await Promise.all([totalOf("wyy", keyword), totalOf("kuwo", keyword)]);
    const wyyTotal = totals[0];
    const kuwoTotal = totals[1];
    const combinedTotal = wyyTotal + kuwoTotal;
    const end = Math.min(offset + count, combinedTotal);
    const tracks = [];
    // 合并顺序：先网易云，再酷我（连续、无空洞，保证 offset 语义正确）
    const wa0 = offset;
    const wa1 = Math.min(end, wyyTotal);
    if (wa1 > wa0) {
      const r = await fetchRange("wyy", keyword, wa0, wa1 - wa0);
      for (const it of r.items) tracks.push(songToTrack("wyy", it));
    }
    const ka0 = Math.max(offset, wyyTotal);
    const ka1 = end;
    if (ka1 > ka0) {
      const r = await fetchRange("kuwo", keyword, ka0 - wyyTotal, ka1 - ka0);
      for (const it of r.items) tracks.push(songToTrack("kuwo", it));
    }
    return { tracks: tracks, more: offset + count < combinedTotal };
  }
  const r = await fetchRange(mode, keyword, offset, count);
  return {
    tracks: r.items.map((it) => songToTrack(mode, it)),
    more: offset + count < r.total,
  };
}

export async function search({ query, kind, offset }) {
  kind = kind || "tracks";
  offset = Math.max(0, Number(offset) || 0);
  const q = String(query == null ? "" : query).trim();
  if (!q || kind !== "tracks") return { [kind]: [], more: false };
  const r = await doSearch(q, offset);
  return { [kind]: r.tracks, more: r.more };
}

export async function album({ id }) {
  const p = parseId(id);
  if (p.platform !== "wyy" && p.platform !== "kuwo") {
    throw fail("unavailable", "invalid album id");
  }
  const d = await api("album", { platform: p.platform, id: p.id, page: 1, size: 100 });
  const info = (d && d.info) || {};
  const tracks = ((d && d.list) || []).map((it) => songToTrack(p.platform, it));
  return {
    album: {
      id: String(id),
      name: info.name || "",
      imageUrl: info.pic_url || "",
      description: info.description || "",
      artists: [{ id: "", name: info.artist || "" }],
      released: "",
    },
    tracks: tracks,
  };
}

// 依据质量挑音源：lossless→flac，high→mp3 320，standard→mp3 128
function pickMinfo(minfo, quality) {
  const list = Array.isArray(minfo) ? minfo.filter((m) => m && m.format) : [];
  const flac = list.find((m) => m.format === "flac");
  const mp3 = list.filter((m) => m.format === "mp3");
  const mp3_320 = mp3.find((m) => String(m.bitrate) === "320") || mp3[0];
  const mp3_128 =
    mp3.find((m) => String(m.bitrate) === "128") || mp3[mp3.length - 1];
  if (quality === "lossless") return flac || mp3_320 || null;
  if (quality === "standard") return mp3_128 || mp3_320 || flac || null;
  return mp3_320 || flac || null; // high
}

function parseSize(s) {
  if (typeof s === "number" && isFinite(s)) return Math.round(s);
  const m = /([\d.]+)\s*([KMGT]?B)/i.exec(String(s == null ? "" : s));
  if (!m) return undefined;
  const n = parseFloat(m[1]);
  if (!isFinite(n)) return undefined;
  const mult =
    { B: 1, KB: 1024, MB: 1024 * 1024, GB: 1024 * 1024 * 1024, TB: 1024 * 1024 * 1024 * 1024 }[
      m[2].toUpperCase()
    ] || 1;
  return Math.round(n * mult);
}

export async function stream({ id, quality }) {
  // 插件设置里的音质优先，其次用 App 传入的，最后默认 high
  quality = settingQuality() || quality || "high";
  const p = parseId(id);
  if (p.platform === "go") {
    const meta = getGoMeta(p.id);
    if (!meta) throw fail("unavailable", "track metadata not found, please search again");
    return await goStream(meta, quality);
  }
  if (p.platform === "bili") {
    return await biliStream(p.id, quality);
  }
  if (p.platform !== "wyy" && p.platform !== "kuwo") {
    throw fail("unavailable", "invalid track id");
  }
  const songId = p.id;
  const referer =
    p.platform === "kuwo" ? "https://www.kuwo.cn/" : "https://music.163.com/";
  // time/sign 会过期，且偶发一次无效（冷启动/瞬时）。每次重试都重新取 song 拿新 token。
  let lastErr;
  for (let attempt = 0; attempt < 4; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 300 * attempt));
    try {
      const d = await api("song", { platform: p.platform, id: songId });
      const item = ((d && d.list) || [])[0];
      if (!item) throw fail("unavailable", "song not found");
      const info = pickMinfo(item.minfo, quality);
      if (!info) throw fail("unavailable", "no source for quality: " + quality);
      const u = await api("getUrl", {
        platform: p.platform,
        songid: songId,
        format: info.format,
        bitrate: info.bitrate,
        time: item.time,
        sign: item.sign,
      });
      if (!u || !u.url) throw fail("unavailable", "no download url");
      return {
        url: u.url,
        type: u.format || info.format || "mp3",
        quality: quality,
        bitRate: Number(info.bitrate) || undefined,
        size: parseSize(info.size),
        headers: { Referer: referer, "User-Agent": UA },
      };
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || fail("unavailable", "stream failed");
}

export async function lyrics({ id }) {
  const p = parseId(id);
  if (p.platform === "go") {
    const meta = getGoMeta(p.id);
    if (!meta) return { lrc: "" };
    return await goLyrics(meta);
  }
  if (p.platform === "bili") return { lrc: "" };
  if (p.platform !== "wyy" && p.platform !== "kuwo") return { lrc: "" };
  const songId = p.id;
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 300 * attempt));
    try {
      const d = await api("song", { platform: p.platform, id: songId });
      const item = ((d && d.list) || [])[0];
      if (!item) continue;
      const data = await api("getLyric", {
        platform: p.platform,
        songid: songId,
        time: item.time,
        sign: item.sign,
      });
      const lrc =
        typeof data === "string" ? data : (data && (data.lrc || data.lyric || "")) || "";
      if (lrc) return { lrc: lrc };
    } catch (e) {
      // 重试
    }
  }
  return { lrc: "" };
}

// 网易云榜单链接（带封面，向接口取每个歌单的 pic_url）
async function wyyChartLinks() {
  return Promise.all(
    CHARTS.map(async (c) => {
      let title = c.title;
      let imageUrl = "";
      try {
        const d = await api("playlist", { platform: "wyy", id: c.id, page: 1, size: 1 });
        const info = (d && d.info) || {};
        if (info.name) title = info.name;
        if (info.pic_url) imageUrl = info.pic_url;
      } catch (e) {}
      return {
        title: t(title),
        subtitle: t("NetEase Cloud Music"),
        imageUrl: imageUrl,
        page: "playlist:wyy:" + c.id,
      };
    })
  );
}

// 各平台榜单分区：网易云 + B站（酷我公开接口取不到真实榜单，见 README）
async function chartSections() {
  const secs = [];
  secs.push({ title: t("NetEase Cloud Music"), links: await wyyChartLinks() });

  let biliCover = "";
  try {
    const list = await biliRanking();
    if (list && list[0]) biliCover = biliPic(list[0].pic);
  } catch (e) {}
  secs.push({
    title: t("Bilibili"),
    links: [
      {
        title: t("Music Ranking"),
        subtitle: t("Bilibili"),
        imageUrl: biliCover,
        page: "bili_chart",
      },
    ],
  });
  return secs;
}

export async function browse({ page }) {
  const s = String(page == null ? "" : page);
  if (s === "charts") {
    const sections = await chartSections();
    // 热门榜单下面就是「我的歌单」
    sections.push({
      title: t("My Playlists"),
      playlists: listPlaylistsMeta(),
    });
    return {
      title: t("Charts"),
      sections: sections,
    };
  }
  if (s === "bili_chart") {
    let tracks = [];
    try {
      tracks = (await biliRanking()).map(biliRankToTrack);
    } catch (e) {
      tracks = [];
    }
    return {
      title: t("Bilibili") + " · " + t("Music Ranking"),
      sections: [{ title: t("Songs"), tracks: tracks }],
    };
  }
  if (s.indexOf("playlist:") === 0) {
    const rest = s.slice("playlist:".length);
    const parts = rest.split(":");
    const platform = parts[0];
    const pid = parts[1];
    const pageNum = Math.max(1, Number(parts[2]) || 1);
    const d = await api("playlist", {
      platform: platform,
      id: pid,
      page: pageNum,
      size: PAGE,
    });
    const info = (d && d.info) || {};
    const tracks = ((d && d.list) || []).map((it) => songToTrack(platform, it));
    const total = Number(d && d.total) || 0;
    const section = { title: t("Songs"), tracks: tracks };
    if (pageNum * PAGE < total) {
      section.morePage = "playlist:" + platform + ":" + pid + ":" + (pageNum + 1);
    }
    return {
      title: info.name || t("Playlist"),
      subtitle: info.description || "",
      sections: [section],
    };
  }
  throw fail("unavailable", "no such page");
}

// ===================== 歌单（我的歌单，本地保存于 host.storage） =====================
// 右键歌曲 → 「添加到歌单」由 App 调用 addToPlaylist；歌单数据存在本插件 storage。
const PL_KEY = "playlists";

function loadPlaylists() {
  try {
    const v = host.storage.get(PL_KEY);
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}

function savePlaylists(list) {
  try {
    host.storage.set(PL_KEY, list);
  } catch (e) {}
}

function newPlaylistId() {
  return "pl_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8);
}

function playlistMeta(pl) {
  return {
    id: pl.id,
    name: pl.name,
    imageUrl: pl.imageUrl || "",
    description: pl.description || "",
    owner: pl.owner || "",
    trackCount: Array.isArray(pl.tracks) ? pl.tracks.length : pl.trackCount || 0,
    mine: true,
  };
}

function listPlaylistsMeta() {
  return loadPlaylists().map(playlistMeta);
}

function findPlaylist(list, id) {
  return list.find((pl) => pl.id === id) || null;
}

function refreshPlaylistCover(pl) {
  if (pl.tracks && pl.tracks.length > 0) {
    const t0 = pl.tracks[0];
    if (t0 && t0.album && t0.album.imageUrl) pl.imageUrl = t0.album.imageUrl;
  }
}

// 把 track id 还原成完整 Track（加入歌单时保存元数据，供歌单页展示/播放）
async function resolveTrackById(id) {
  const p = parseId(id);
  if (!p.id) return null;
  if (p.platform === "go") {
    const meta = getGoMeta(p.id);
    return meta ? goMetaToTrack("go:" + p.id, meta) : null;
  }
  if (p.platform === "wyy" || p.platform === "kuwo") {
    try {
      const d = await api("song", { platform: p.platform, id: p.id });
      const it = d && d.list && d.list[0];
      if (it) return songToTrack(p.platform, it);
    } catch (e) {}
    return null;
  }
  if (p.platform === "bili") {
    try {
      const r = await fetch(BILI + "/x/web-interface/view?bvid=" + encodeURIComponent(p.id), {
        headers: { "User-Agent": UA, Referer: BILI_WEB },
      });
      const j = await r.json().catch(() => ({}));
      const v = j && j.data;
      if (v && v.bvid) {
        return {
          id: "bili:" + v.bvid,
          name: stripTags(v.title || ""),
          artists: [{ id: "", name: stripTags((v.owner && v.owner.name) || ""), imageUrl: "" }],
          album: { id: "", name: "Bilibili", imageUrl: biliPic(v.pic) },
          duration: Math.round(Number(v.duration || 0) * 1000),
          url: "https://www.bilibili.com/video/" + v.bvid,
          unavailable: false,
        };
      }
    } catch (e) {}
    return null;
  }
  return null;
}

export async function playlists() {
  return listPlaylistsMeta();
}

export async function playlist({ id }) {
  const pl = findPlaylist(loadPlaylists(), String(id));
  if (!pl) throw fail("unavailable", "playlist not found");
  return Object.assign(playlistMeta(pl), { tracks: pl.tracks || [] });
}

export async function createPlaylist({ name }) {
  const list = loadPlaylists();
  const nm = String(name == null ? "" : name).trim() || t("New Playlist");
  const pl = {
    id: newPlaylistId(),
    name: nm,
    imageUrl: "",
    description: "",
    owner: "",
    mine: true,
    tracks: [],
  };
  list.unshift(pl);
  savePlaylists(list);
  return Object.assign(playlistMeta(pl), { tracks: [] });
}

export async function renamePlaylist({ id, name }) {
  const list = loadPlaylists();
  const pl = findPlaylist(list, String(id));
  if (!pl) throw fail("unavailable", "playlist not found");
  const nm = String(name == null ? "" : name).trim();
  if (nm) pl.name = nm;
  savePlaylists(list);
}

export async function deletePlaylist({ id }) {
  savePlaylists(loadPlaylists().filter((pl) => pl.id !== String(id)));
}

export async function addToPlaylist({ id, trackIds }) {
  const list = loadPlaylists();
  const pl = findPlaylist(list, String(id));
  if (!pl) throw fail("unavailable", "playlist not found");
  if (!Array.isArray(pl.tracks)) pl.tracks = [];
  const ids = Array.isArray(trackIds) ? trackIds : [];
  const have = new Set(pl.tracks.map((tr) => tr.id));
  for (const tid of ids) {
    const key = String(tid);
    if (have.has(key)) continue;
    const tr = await resolveTrackById(key);
    if (tr) {
      pl.tracks.push(tr);
      have.add(key);
    }
  }
  refreshPlaylistCover(pl);
  savePlaylists(list);
}

export async function removeFromPlaylist({ id, trackIds }) {
  const list = loadPlaylists();
  const pl = findPlaylist(list, String(id));
  if (!pl) throw fail("unavailable", "playlist not found");
  const drop = new Set((Array.isArray(trackIds) ? trackIds : []).map(String));
  pl.tracks = (pl.tracks || []).filter((tr) => !drop.has(tr.id));
  refreshPlaylistCover(pl);
  savePlaylists(list);
}

export async function movePlaylistTrack({ id, from, to }) {
  const list = loadPlaylists();
  const pl = findPlaylist(list, String(id));
  if (!pl) throw fail("unavailable", "playlist not found");
  const tracks = pl.tracks || [];
  const a = Number(from);
  const b = Number(to);
  if (a >= 0 && a < tracks.length && b >= 0 && b < tracks.length) {
    const moved = tracks.splice(a, 1)[0];
    tracks.splice(b, 0, moved);
    pl.tracks = tracks;
    savePlaylists(list);
  }
}
