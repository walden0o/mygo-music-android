// Bilibili, for personal listening. A video is a track, and a video of many
// parts is an album with a track for each part. The audio is the video's own
// soundtrack, which Bilibili serves as AAC (or FLAC, for a few Hi-Res uploads)
// inside fragmented MP4. The app plays that itself, from Bilibili's servers.
// Only HE-AAC, which some videos have and nothing else, it doesn't decode:
// ffmpeg decodes that to WAV and serves it on this machine while it plays.
//
// Signed out, it searches, plays, and shows charts, parts, comments and
// subtitles. Signing in with the Bilibili app's QR code adds favorites,
// history, recommendations and following.

const UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const REFERER = "https://www.bilibili.com/";
const API = "https://api.bilibili.com";

const t = host.t;

function fail(code, message) {
  const e = new Error(message || code);
  e.code = code;
  return e;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------------------
// Cookies: the session from signing in, and the browser ID every visitor gets.

const session = () => host.storage.get("cookie") || "";

function cookieValue(name) {
  for (const part of session().split(";")) {
    const at = part.indexOf("=");
    if (at > 0 && part.slice(0, at).trim() === name) return part.slice(at + 1).trim();
  }
  return "";
}

const signedIn = () => !!cookieValue("SESSDATA");
const csrf = () => cookieValue("bili_jct");
const myMid = () => cookieValue("DedeUserID");

function needAccount() {
  if (!signedIn()) throw fail("signIn");
}

// Bilibili turns away search and space pages from a visitor without buvid3.
let visitorCookie = "";

async function visitor() {
  if (visitorCookie) return visitorCookie;
  const kept = host.storage.get("visitor");
  if (kept) return (visitorCookie = kept);
  const res = await fetch(`${API}/x/frontend/finger/spi`, { headers: { "User-Agent": UA, Referer: REFERER } });
  const body = await res.json();
  const d = body.data || {};
  if (!d.b_3) throw new Error(t("Bilibili gave this visit no ID"));
  visitorCookie = `buvid3=${d.b_3}; buvid4=${encodeURIComponent(d.b_4 || "")}; b_nut=${Math.floor(Date.now() / 1000)}`;
  host.storage.set("visitor", visitorCookie);
  return visitorCookie;
}

async function headers(extra) {
  const cookie = [await visitor(), session()].filter(Boolean).join("; ");
  return { "User-Agent": UA, Referer: REFERER, Origin: "https://www.bilibili.com", Cookie: cookie, ...extra };
}

// ---------------------------------------------------------------------------
// WBI: most of the website's newer endpoints want their query signed with two
// keys the nav endpoint hands out, which change every day.

const mixinTable = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41,
  13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34,
  44, 52,
];

let wbiKey = null; // { key, day }

const keyOf = (url) => (url || "").split("/").pop().split(".")[0];

async function mixinKey(fresh) {
  const day = new Date().toISOString().slice(0, 10);
  if (!fresh && wbiKey && wbiKey.day === day) return wbiKey.key;
  const res = await fetch(`${API}/x/web-interface/nav`, { headers: await headers() });
  const body = await res.json();
  const img = body.data?.wbi_img;
  if (!img) throw new Error(t("Bilibili didn't answer: try again later"));
  const raw = keyOf(img.img_url) + keyOf(img.sub_url);
  const key = mixinTable.map((i) => raw[i]).join("").slice(0, 32);
  wbiKey = { key, day };
  return key;
}

function query(params) {
  return Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null)
    .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(params[k]))}`)
    .join("&");
}

async function signed(params, fresh) {
  const key = await mixinKey(fresh);
  const all = { ...params, wts: Math.floor(Date.now() / 1000) };
  const sorted = {};
  for (const k of Object.keys(all).sort()) {
    if (all[k] === undefined || all[k] === null) continue;
    sorted[k] = String(all[k]).replace(/[!'()*]/g, "");
  }
  const q = query(sorted);
  return `${q}&w_rid=${host.crypto.md5(q + key)}`;
}

// ---------------------------------------------------------------------------
// Calling the API.

function apiError(body, status) {
  const code = body?.code;
  const message = body?.message || `HTTP ${status}`;
  if (code === -101) return fail("signIn", t("Sign in to Bilibili to do this"));
  if (code === -352 || code === -412 || status === 412) {
    return new Error(t("Bilibili is checking for bots: try again in a moment"));
  }
  if ([-403, -404, 62002, 62004, 62012, 87008].includes(code)) return fail("unavailable", message);
  const e = new Error(message);
  e.biliCode = code;
  return e;
}

// get("/x/...", params, { wbi }) and post("/x/...", form) answer the JSON's data.
async function get(path, params = {}, { wbi = false, base = API } = {}) {
  for (let attempt = 0; ; attempt++) {
    const q = wbi ? await signed(params, attempt > 0) : query(params);
    const res = await fetch(`${base}${path}${q ? `?${q}` : ""}`, { headers: await headers() });
    let body;
    try {
      body = await res.json();
    } catch {
      throw apiError(null, res.status);
    }
    if (body.code === 0) return body.data ?? body.result;
    // A day-old key is turned down as a bot would be: one more try with a new one.
    if (wbi && attempt === 0 && body.code === -352) continue;
    throw apiError(body, res.status);
  }
}

async function post(path, form) {
  needAccount();
  const res = await fetch(`${API}${path}`, {
    method: "POST",
    headers: await headers({ "Content-Type": "application/x-www-form-urlencoded" }),
    body: query({ ...form, csrf: csrf() }),
  });
  const body = await res.json().catch(() => null);
  if (body?.code !== 0) throw apiError(body, res.status);
  return body.data;
}

// ---------------------------------------------------------------------------
// Turning Bilibili's answers into the app's shapes.

const entities = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

function clean(text) {
  return String(text ?? "")
    .replace(/<[^>]+>/g, "")
    .replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e) => {
      if (e[0] === "#") return String.fromCodePoint(e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : +e.slice(1));
      return entities[e.toLowerCase()] ?? m;
    })
    .trim();
}

// Covers are 16:9; the image server crops them square for the app.
function image(url, size = 480) {
  if (!url) return undefined;
  let u = String(url);
  if (u.startsWith("//")) u = `https:${u}`;
  u = u.replace(/^http:\/\//, "https://");
  if (/hdslb\.com\/bfs\//.test(u) && !u.includes("@")) u += `@${size}w_${size}h_1c.jpg`;
  return u;
}

function seconds(v) {
  if (typeof v === "number") return v * 1000;
  if (typeof v !== "string" || !v) return 0;
  return v.split(":").reduce((s, n) => s * 60 + (parseInt(n, 10) || 0), 0) * 1000;
}

// Uploaders' names as tracks give them, for an artist page Bilibili won't fill in.
const uploaders = new Map();

function artistOf(mid, name) {
  const id = String(mid ?? "");
  const n = clean(name);
  if (id && n && uploaders.size < 5000) uploaders.set(id, n);
  return { id, name: n || t("Unknown uploader") };
}

// A track. Its id is "BV…" for a video, which plays its first part, and
// "BV…|cid" for any later part, so that a video has one id wherever it's found:
// in search, history, favorites or recommendations. 0.1's "BV…|cid" for a first
// part still plays.
function track({ bvid, cid, title, part, page, pages, pic, mid, author, duration, albumName }) {
  const name = pages > 1 && part ? `P${page} ${clean(part)}` : clean(title);
  return {
    id: cid && page > 1 ? `${bvid}|${cid}` : bvid,
    name: name || bvid,
    artists: [artistOf(mid, author)],
    album: { id: bvid, name: clean(albumName ?? title), imageUrl: image(pic), artists: [artistOf(mid, author)] },
    duration: seconds(duration),
    url: `https://www.bilibili.com/video/${bvid}${page > 1 ? `?p=${page}` : ""}`,
  };
}

function splitId(id) {
  const [bvid, cid] = String(id).split("|");
  return { bvid, cid };
}

// ---------------------------------------------------------------------------
// Videos.

const views = new Map();

async function view(bvid) {
  const known = views.get(bvid);
  if (known && Date.now() - known.at < 10 * 60 * 1000) return known.data;
  const data = await get("/x/web-interface/view", /^av\d+$/i.test(bvid) ? { aid: bvid.slice(2) } : { bvid });
  views.set(bvid, { data, at: Date.now() });
  if (data.bvid && data.bvid !== bvid) views.set(data.bvid, { data, at: Date.now() });
  return data;
}

function partsOf(v) {
  const pages = (v.pages || []).slice(0, 500);
  return pages.map((p, i) =>
    track({
      bvid: v.bvid,
      cid: p.cid,
      title: v.title,
      part: p.part,
      page: p.page || i + 1,
      pages: pages.length,
      pic: v.pic,
      mid: v.owner?.mid,
      author: v.owner?.name,
      duration: p.duration,
    }),
  );
}

// Where a track plays from: its video, part and archive ID.
async function locate(id) {
  const { bvid, cid } = splitId(id);
  const v = await view(bvid);
  if (cid) return { bvid: v.bvid, cid, aid: v.aid, view: v };
  const first = v.pages?.[0]?.cid || v.cid;
  if (!first) throw fail("unavailable", t("This video has nothing to play"));
  return { bvid: v.bvid, cid: String(first), aid: v.aid, view: v };
}

// What was pasted, if it's a video: a link, a b23.tv short link, a BV or av ID.
async function pasted(text) {
  const bv = text.match(/BV[0-9A-Za-z]{10}/);
  if (bv) return { bvid: bv[0], page: pageOf(text) };
  const av = text.match(/(?:^|[^a-z])av(\d+)/i);
  if (av) return { bvid: `av${av[1]}`, page: pageOf(text) };
  const short = text.match(/https?:\/\/(?:b23\.tv|bili2233\.cn)\/[0-9A-Za-z]+/);
  if (short) {
    const res = await fetch(short[0], { redirect: "manual", headers: { "User-Agent": UA } });
    const to = res.headers.get("location") || res.url || "";
    const found = to.match(/BV[0-9A-Za-z]{10}/);
    if (found) return { bvid: found[0], page: pageOf(to) };
  }
  return null;
}

function pageOf(s) {
  const m = s.match(/[?&]p=(\d+)/);
  return m ? +m[1] : 0;
}

// ---------------------------------------------------------------------------
// Account.

export async function account() {
  if (!signedIn()) return null;
  try {
    const nav = await get("/x/web-interface/nav");
    if (!nav?.isLogin) return endSession();
    const me = { id: String(nav.mid), name: nav.uname, imageUrl: image(nav.face, 240) };
    host.storage.set("account", me);
    return me;
  } catch (e) {
    if (e.code === "signIn") return endSession();
    // Offline, the last known account stands.
    return host.storage.get("account") || null;
  }
}

function endSession() {
  host.storage.remove("cookie");
  host.storage.remove("account");
  folderCache = null;
  favoriteIds = null;
  return null;
}

const sessionKeys = ["DedeUserID", "DedeUserID__ckMd5", "SESSDATA", "bili_jct", "sid"];

// Once the QR code is confirmed, the poll's answer sets the session's cookies,
// and has lately also had them in the query of the address it answers. Both
// are read. Values are kept as they come, percent-encoded as a browser keeps
// them ("…%2C…"); decoded is tried too, should Bilibili want that.
function setCookies(res) {
  let lines = [];
  if (typeof res.headers.getSetCookie === "function") lines = res.headers.getSetCookie();
  if (!lines.length) {
    const joined = res.headers.get("set-cookie") || "";
    // Joined with commas, which Expires dates have too: split only before name=.
    lines = joined ? joined.split(/,(?=\s*[A-Za-z0-9_]+=)/) : [];
  }
  const found = {};
  for (const line of lines) {
    const pair = line.split(";")[0].trim();
    const at = pair.indexOf("=");
    if (at > 0) found[pair.slice(0, at)] = pair.slice(at + 1);
  }
  return found;
}

function queryOf(address) {
  const found = {};
  for (const part of (String(address || "").split("?")[1] || "").split("#")[0].split("&")) {
    const at = part.indexOf("=");
    if (at > 0) found[part.slice(0, at)] = part.slice(at + 1);
  }
  return found;
}

async function takeSession(address, fromHeaders) {
  const fromQuery = queryOf(address);
  const raw = { ...fromQuery, ...fromHeaders };
  if (!raw.SESSDATA) {
    // Names only, never values, so the listener can say what came back.
    const names = (o) => Object.keys(o).join(",") || "-";
    throw new Error(
      `${t("Bilibili didn't take that sign-in: try again")} (no SESSDATA; query: ${names(fromQuery)}; cookies: ${names(fromHeaders)})`,
    );
  }
  const decode = (v) => {
    try {
      return decodeURIComponent(v);
    } catch {
      return v;
    }
  };
  const asIs = sessionKeys.filter((k) => raw[k]).map((k) => `${k}=${raw[k]}`).join("; ");
  const decoded = sessionKeys.filter((k) => raw[k]).map((k) => `${k}=${decode(raw[k])}`).join("; ");
  let said = "";
  for (const cookie of asIs === decoded ? [asIs] : [asIs, decoded]) {
    host.storage.set("cookie", cookie);
    folderCache = null;
    favoriteIds = null;
    const res = await fetch(`${API}/x/web-interface/nav`, { headers: await headers() });
    const body = await res.json().catch(() => null);
    if (body?.code === 0 && body.data?.isLogin) {
      const me = { id: String(body.data.mid), name: body.data.uname, imageUrl: image(body.data.face, 240) };
      host.storage.set("account", me);
      return me;
    }
    said = body ? `${body.code} ${body.message || ""}`.trim() : `HTTP ${res.status}`;
    console.warn(`signing in: nav answered ${said}`);
  }
  endSession();
  throw new Error(`${t("Bilibili didn't take that sign-in: try again")} (${said})`);
}

// Scanning a QR code with the Bilibili app.
let signingIn = 0;
const passport = "https://passport.bilibili.com";

// Signing in is on Bilibili's own page, in a window of the app, which offers
// all its ways in: the QR code for the Bilibili app, a text message, or a
// password. It's done once the session's cookies are there.
async function signInOnPage() {
  const { cookie } = await host.webSignIn({
    url: "https://passport.bilibili.com/login",
    cookiesOf: `${API}/`,
    until: ["SESSDATA", "bili_jct"],
    userAgent: UA,
  });
  const found = {};
  for (const part of String(cookie || "").split(";")) {
    const at = part.indexOf("=");
    if (at > 0) found[part.slice(0, at).trim()] = part.slice(at + 1).trim();
  }
  return takeSession("", found);
}

export async function signIn() {
  signingIn++;
  if (typeof host.webSignIn === "function") return signInOnPage();
  return signInWithQR();
}

// The QR code alone, in the app's own sign-in prompt, for an app too old to
// show a page.
async function signInWithQR() {
  const mine = ++signingIn;
  host.signInPrompt({ text: t("Getting a QR code from Bilibili…") });
  const gen = await get("/x/passport-login/web/qrcode/generate", {}, { base: passport });
  const key = gen.qrcode_key;
  host.signInPrompt({ qr: gen.url, text: t("Scan with the Bilibili app, then confirm on your phone") });
  const until = Date.now() + 180000;
  while (Date.now() < until && mine === signingIn) {
    await sleep(1500);
    const res = await fetch(`${passport}/x/passport-login/web/qrcode/poll?${query({ qrcode_key: key })}`, {
      headers: await headers(),
    });
    const body = await res.json().catch(() => null);
    if (body?.code !== 0) throw apiError(body, res.status);
    const r = body.data || {};
    if (r.code === 86090) host.signInPrompt({ qr: gen.url, text: t("Scanned: confirm on your phone") });
    if (r.code === 86038) throw new Error(t("The QR code expired: sign in again"));
    if (r.code === 0) {
      const me = await takeSession(r.url, setCookies(res));
      if (r.refresh_token) host.storage.set("refreshToken", r.refresh_token);
      return me;
    }
  }
  throw new Error(t("Signing in timed out: try again"));
}

export function signOut() {
  signingIn++;
  endSession();
}

// ---------------------------------------------------------------------------
// Search.

const pageSize = 20;

// The app asks for more from how many it already has, while Bilibili counts
// in pages of twenty, some of which the plugin leaves out (courses, live rooms,
// a video already given). So each search remembers which page comes next after
// how many it gave, and what it gave, to give no video twice: the app keys its
// cards by id, and a card that's there twice can't be clicked.
const searches = new Map();

function searchState(kind, q, offset) {
  const key = `${kind}\n${q}`;
  let s = searches.get(key);
  if (!s || offset === 0) {
    s = { next: new Map([[0, 1]]), seen: new Set() };
    searches.delete(key);
    searches.set(key, s);
    while (searches.size > 30) searches.delete(searches.keys().next().value);
  }
  return s;
}

// Results from offset on: a page of Bilibili's, or the next ones too when all
// of one was left out, so that "more" never comes with nothing.
async function searchPages(kind, q, offset, type, keep) {
  const s = searchState(kind, q, offset);
  let page = s.next.get(offset) ?? Math.floor(offset / pageSize) + 1;
  const out = [];
  let more = false;
  for (let tries = 0; tries < 3; tries++) {
    const d = await get("/x/web-interface/wbi/search/type", { search_type: type, keyword: q, page, page_size: pageSize }, { wbi: true });
    for (const r of d.result || []) {
      const id = keep(r);
      if (!id || s.seen.has(id)) continue;
      s.seen.add(id);
      out.push(r);
    }
    more = page < (d.numPages || 0);
    page++;
    if (out.length || !more) break;
  }
  s.next.set(offset + out.length, page);
  return { found: out, more: more && out.length > 0 };
}

export async function search({ query: q, kind, offset }) {
  const link = await pasted(q);
  if (link) {
    if (kind !== "tracks" || offset > 0) return { [kind]: [], more: false };
    const v = await view(link.bvid);
    const tracks = partsOf(v);
    if (link.page > 1 && link.page <= tracks.length) tracks.unshift(...tracks.splice(link.page - 1, 1));
    return { tracks, more: false };
  }
  if (kind === "tracks" || kind === "albums") {
    const { found, more } = await searchPages(kind, q, offset, "video", (r) => r.bvid);
    if (kind === "albums") {
      // A video is an album of its parts: opening one lists them.
      return {
        albums: found.map((r) => ({ id: r.bvid, name: clean(r.title), imageUrl: image(r.pic), artists: [artistOf(r.mid, r.author)] })),
        more,
      };
    }
    return {
      tracks: found.map((r) => track({ bvid: r.bvid, title: r.title, pic: r.pic, mid: r.mid, author: r.author, duration: r.duration })),
      more,
    };
  }
  if (kind === "artists") {
    const { found, more } = await searchPages(kind, q, offset, "bili_user", (u) => u.mid && String(u.mid));
    return { artists: found.map((u) => ({ id: String(u.mid), name: clean(u.uname), imageUrl: image(u.upic, 240) })), more };
  }
  if (kind === "playlists") {
    // Bilibili has no search for playlists. The nearest thing is your own
    // favorites folders, and the collections you saved, by name.
    if (!signedIn() || offset > 0) return { playlists: [], more: false };
    const needle = q.trim().toLowerCase();
    const mine = await playlists();
    return { playlists: mine.filter((pl) => pl.name.toLowerCase().includes(needle)), more: false };
  }
  return { [kind]: [], more: false };
}

export async function suggest({ query: q }) {
  if (!q.trim() || /BV[0-9A-Za-z]{10}|b23\.tv/.test(q)) return [];
  const res = await fetch(`https://s.search.bilibili.com/main/suggest?${query({ term: q, main_ver: "v1" })}`, {
    headers: await headers(),
  });
  const body = await res.json();
  const tags = body.result?.tag || [];
  return tags.map((x) => x.value).filter(Boolean).slice(0, 10);
}

// ---------------------------------------------------------------------------
// Charts and Home.

// Bilibili moved to new categories in 2025, and the rankings of the old ones
// stopped then. Music is 1003 among the new ones, whose own subcategories have
// no ranking of their own.
const musicRegion = 1003;
const musicTids = new Set([3, 28, 29, 30, 31, 59, 130, 193, 194, 243, 244, 265, 266, 267]);

const videoTrack = (v) =>
  track({
    bvid: v.bvid,
    cid: v.videos > 1 ? undefined : v.cid,
    title: v.title,
    pic: v.pic || v.cover,
    mid: v.owner?.mid ?? v.author?.mid,
    author: v.owner?.name ?? v.author?.name,
    duration: v.duration,
  });

// The music category's ranking: what's done well there over the last few days.
async function musicRank() {
  const d = await get("/x/web-interface/ranking/v2", { rid: musicRegion, type: "all" });
  return (d.list || []).filter((v) => v.bvid).map(videoTrack);
}

// What's new in the music category, a different mix each time it's asked. It
// gives twenty at most per request; pages > 1 asks again and keeps the new ones.
async function musicFresh(pages = 1) {
  const seen = new Set();
  const out = [];
  for (let i = 1; i <= pages; i++) {
    const d = await get("/x/web-interface/region/feed/rcmd", { display_id: i, request_cnt: 20, from_region: musicRegion });
    for (const v of d.archives || []) {
      if (!v.bvid || (v.goto && v.goto !== "av") || seen.has(v.bvid)) continue;
      seen.add(v.bvid);
      out.push(videoTrack(v));
    }
  }
  return out;
}

// Beyond music: the whole site's ranking, what's popular now, and the
// classics Bilibili tells newcomers to see. (Its weekly picks turn away a
// visitor, and its original and newcomer rankings stopped in 2025.)
async function siteRank() {
  const d = await get("/x/web-interface/ranking/v2", { rid: 0, type: "all" });
  return (d.list || []).filter((v) => v.bvid).map(videoTrack);
}

async function popular(pages = 1) {
  const out = [];
  const seen = new Set();
  for (let pn = 1; pn <= pages; pn++) {
    const d = await get("/x/web-interface/popular", { ps: 50, pn });
    for (const v of d.list || []) {
      if (!v.bvid || seen.has(v.bvid)) continue;
      seen.add(v.bvid);
      out.push(videoTrack(v));
    }
    if (d.no_more) break;
  }
  return out;
}

async function classics() {
  const d = await get("/x/web-interface/popular/precious", { page_size: 100, page: 1 });
  return (d.list || []).filter((v) => v.bvid).map(videoTrack);
}

// The weekly Bilibili music chart. Each entry is a song, but the chart plays
// it through a video someone uploaded with it, which is what's shown: the
// video as its uploader titled it.
async function musicChart() {
  const periods = await get("/x/copyright-music-publicity/toplist/all_period", { list_type: 1 });
  let latest = null;
  for (const year of Object.values(periods.list || {})) {
    for (const p of year) if (!latest || p.ID > latest.ID) latest = p;
  }
  if (!latest) return { tracks: [], period: 0 };
  const d = await get("/x/copyright-music-publicity/toplist/music_list", { list_id: latest.ID });
  const tracks = (d.list || [])
    .filter((s) => s.creation_bvid)
    .map((s) =>
      track({
        bvid: s.creation_bvid,
        title: s.creation_title,
        pic: s.creation_cover,
        mid: s.creation_up,
        author: s.creation_nickname,
        duration: s.creation_duration || 0,
      }),
    );
  return { tracks, period: latest.priod };
}

async function recommended() {
  const d = await get(
    "/x/web-interface/wbi/index/top/feed/rcmd",
    { fresh_type: 4, ps: 20, fresh_idx: 1, fresh_idx_1h: 1, brush: 1, feed_version: "V8" },
    { wbi: true },
  );
  return (d.item || [])
    .filter((v) => v.bvid && (v.goto === "av" || !v.goto))
    .map((v) =>
      track({ bvid: v.bvid, cid: v.cid, title: v.title, pic: v.pic, mid: v.owner?.mid, author: v.owner?.name, duration: v.duration }),
    );
}

const settle = (p) => p.then((v) => v, (e) => (console.warn(e.message), null));

export async function home() {
  const [chart, rank, fresh, rcmd] = await Promise.all([
    settle(musicChart()),
    settle(musicRank()),
    settle(musicFresh()),
    signedIn() ? settle(recommended()) : null,
  ]);
  const sections = [];
  if (rcmd?.length) sections.push({ title: t("Recommended for you"), tracks: rcmd.slice(0, 12) });
  if (chart?.tracks.length) sections.push({ title: t("Bilibili Music Chart"), tracks: chart.tracks.slice(0, 12), morePage: "chart" });
  if (rank?.length) sections.push({ title: t("Trending in Music"), tracks: rank.slice(0, 12), morePage: "rank" });
  if (fresh?.length) sections.push({ title: t("New in Music"), tracks: fresh.slice(0, 12), morePage: "fresh" });
  if (!sections.length) throw new Error(t("Bilibili didn't answer: try again later"));
  return { title: t("Home"), sections };
}

export async function browse({ page }) {
  if (page === "charts") {
    const [chart, rank, site, hot, picks] = await Promise.all([
      settle(musicChart()),
      settle(musicRank()),
      settle(siteRank()),
      settle(popular()),
      settle(classics()),
    ]);
    const sections = [];
    const add = (title, tracks, morePage) => tracks?.length && sections.push({ title, tracks: tracks.slice(0, 10), morePage });
    add(t("Bilibili Music Chart"), chart?.tracks, "chart");
    add(t("Trending in Music"), rank, "rank");
    add(t("Bilibili Ranking"), site, "site");
    add(t("Popular Now"), hot, "popular");
    add(t("Must-See Classics"), picks, "classics");
    if (!sections.length) throw new Error(t("Bilibili didn't answer: try again later"));
    return { title: t("Charts"), sections };
  }
  if (page === "site") {
    return { title: t("Bilibili Ranking"), subtitle: t("The whole site's top videos lately"), sections: [{ tracks: await siteRank() }] };
  }
  if (page === "popular") {
    return { title: t("Popular Now"), subtitle: t("What everyone's watching right now"), sections: [{ tracks: await popular(2) }] };
  }
  if (page === "classics") {
    return { title: t("Must-See Classics"), subtitle: t("Bilibili's picks for newcomers"), sections: [{ tracks: await classics() }] };
  }
  if (page === "chart") {
    const chart = await musicChart();
    return {
      title: t("Bilibili Music Chart"),
      subtitle: chart.period ? t("Week %d").replace("%d", chart.period) : undefined,
      sections: [{ tracks: chart.tracks }],
    };
  }
  // rank:<rid> was 0.1's address for the old categories' rankings.
  if (page === "rank" || /^rank:\d+$/.test(page)) {
    return { title: t("Trending in Music"), subtitle: t("What's done well in the music category lately"), sections: [{ tracks: await musicRank() }] };
  }
  if (page === "fresh") {
    return { title: t("New in Music"), subtitle: t("Different each time you look"), sections: [{ tracks: await musicFresh(2) }] };
  }
  throw fail("unavailable", t("no such page"));
}

// ---------------------------------------------------------------------------
// Albums (a video's parts, or the collection a one-part video is in) and
// artists (uploaders).

async function getAlbum({ id }) {
  const v = await view(id);
  const owner = artistOf(v.owner?.mid, v.owner?.name);
  const album = {
    id: v.bvid,
    name: clean(v.title),
    imageUrl: image(v.pic),
    artists: [owner],
    released: v.pubdate ? new Date(v.pubdate * 1000).toISOString().slice(0, 10) : undefined,
  };
  const season = v.ugc_season;
  if ((v.pages?.length || 0) <= 1 && season?.sections?.length) {
    const tracks = [];
    for (const s of season.sections) {
      for (const ep of s.episodes || []) {
        const tr = track({
          bvid: ep.bvid,
          cid: ep.cid,
          title: ep.title,
          pic: ep.arc?.pic,
          mid: v.owner?.mid,
          author: v.owner?.name,
          duration: ep.arc?.duration ?? ep.page?.duration,
          albumName: season.title,
        });
        tr.album.id = v.bvid;
        tracks.push(tr);
      }
    }
    if (tracks.length > 1) {
      return { album: { ...album, name: clean(season.title), imageUrl: image(season.cover) || album.imageUrl }, tracks };
    }
  }
  return { album, tracks: partsOf(v) };
}

async function getArtist({ id }) {
  const [info, videos, relation] = await Promise.all([
    settle(get("/x/space/wbi/acc/info", { mid: id }, { wbi: true })),
    settle(get("/x/space/wbi/arc/search", { mid: id, ps: 40, pn: 1, order: "pubdate" }, { wbi: true })),
    signedIn() ? settle(get("/x/relation", { fid: id })) : null,
  ]);
  const name = info?.name || videos?.list?.vlist?.[0]?.author || uploaders.get(String(id)) || "";
  let tracks = (videos?.list?.vlist || []).map((v) =>
    track({ bvid: v.bvid, title: v.title, pic: v.pic, mid: id, author: name, duration: v.length }),
  );
  // The space page often turns away a visitor that isn't signed in. Their
  // videos are still found by searching for their name.
  if (!tracks.length && name) {
    const d = await settle(get("/x/web-interface/wbi/search/type", { search_type: "video", keyword: name, page: 1, page_size: 50 }, { wbi: true }));
    tracks = (d?.result || [])
      .filter((r) => r.bvid && String(r.mid) === String(id))
      .map((r) => track({ bvid: r.bvid, title: r.title, pic: r.pic, mid: r.mid, author: r.author, duration: r.duration }));
  }
  if (!name && !tracks.length) throw new Error(t("Bilibili is checking for bots: try again in a moment"));
  return {
    artist: {
      id: String(id),
      name: clean(name) || t("Unknown uploader"),
      imageUrl: image(info?.face, 480),
      following: relation ? [2, 6].includes(relation.attribute) : undefined,
    },
    tracks,
  };
}

export { getAlbum as album, getArtist as artist };

export async function followArtist({ id, on }) {
  await post("/x/relation/modify", { fid: id, act: on ? 1 : 2, re_src: 11 });
}

// ---------------------------------------------------------------------------
// Favorites: the default folder is Liked; the others are playlists.

let folderCache = null; // { at, created, collected }

async function folders(fresh) {
  needAccount();
  if (!fresh && folderCache && Date.now() - folderCache.at < 60000) return folderCache;
  const [created, collected] = await Promise.all([
    get("/x/v3/fav/folder/created/list-all", { up_mid: myMid(), type: 0 }),
    settle(get("/x/v3/fav/folder/collected/list", { up_mid: myMid(), pn: 1, ps: 50, platform: "web" })),
  ]);
  folderCache = { at: Date.now(), created: created?.list || [], collected: collected?.list || [] };
  return folderCache;
}

async function defaultFolder() {
  const { created } = await folders();
  if (!created.length) throw new Error(t("You have no favorites folder on Bilibili"));
  return created.find((f) => (f.attr & 2) === 0 && /默认收藏夹/.test(f.title)) || created[0];
}

function mediaTrack(m) {
  if (m.attr === 9 || m.title === "已失效视频") {
    const tr = track({ bvid: m.bvid || `av${m.id}`, title: t("Video no longer available"), pic: m.cover, mid: m.upper?.mid, author: m.upper?.name, duration: m.duration });
    tr.unavailable = true;
    return tr;
  }
  return track({ bvid: m.bvid, title: m.title, pic: m.cover, mid: m.upper?.mid, author: m.upper?.name, duration: m.duration });
}

async function folderPage(mediaId, pn) {
  return get("/x/v3/fav/resource/list", { media_id: mediaId, pn, ps: 20, platform: "web", order: "mtime", type: 0, tid: 0 });
}

// Every page of a folder, five at a time, up to a thousand videos.
async function wholeFolder(mediaId) {
  const first = await folderPage(mediaId, 1);
  const total = Math.min(first.info?.media_count || 0, 1000);
  const pages = Math.ceil(total / 20);
  const medias = [...(first.medias || [])];
  for (let pn = 2; pn <= pages; pn += 5) {
    const batch = [];
    for (let k = pn; k < pn + 5 && k <= pages; k++) batch.push(folderPage(mediaId, k));
    for (const r of await Promise.all(batch)) medias.push(...(r.medias || []));
  }
  return { info: first.info, medias };
}

// Bilibili leaves some videos out of a page of twenty (deleted, hidden), so the
// app's offset doesn't say which page comes next: remember it, as search does.
let likedNext = null; // { folder, next: offset → page }

export async function liked({ offset }) {
  const folder = await defaultFolder();
  if (!likedNext || offset === 0 || likedNext.folder !== folder.id) likedNext = { folder: folder.id, next: new Map([[0, 1]]) };
  let pn = likedNext.next.get(offset) ?? Math.floor(offset / 20) + 1;
  let r;
  do r = await folderPage(folder.id, pn++);
  while (!r.medias?.length && r.has_more);
  const tracks = (r.medias || []).map(mediaTrack);
  likedNext.next.set(offset + tracks.length, pn);
  return { tracks, more: !!r.has_more && tracks.length > 0 };
}

let favoriteIds = null; // { at, folder, bvids }

async function favorites() {
  if (favoriteIds && Date.now() - favoriteIds.at < 30000) return favoriteIds;
  const folder = await defaultFolder();
  const ids = await get("/x/v3/fav/resource/ids", { media_id: folder.id, platform: "web" });
  favoriteIds = { at: Date.now(), folder, bvids: new Set((ids || []).map((x) => x.bvid)) };
  return favoriteIds;
}

export async function isLiked({ ids }) {
  if (!signedIn()) return {};
  const { bvids } = await favorites();
  return Object.fromEntries(ids.map((id) => [id, bvids.has(splitId(id).bvid)]));
}

async function setFavorite(id, folderId, on) {
  const { aid } = await locate(id);
  await post("/x/v3/fav/resource/deal", {
    rid: aid,
    type: 2,
    add_media_ids: on ? folderId : "",
    del_media_ids: on ? "" : folderId,
  });
}

export async function like({ id, on }) {
  const folder = await defaultFolder();
  await setFavorite(id, folder.id, on);
  favoriteIds = null;
}

function folderPlaylist(f, mine) {
  return {
    id: `fav:${f.id}`,
    name: f.title,
    imageUrl: image(f.cover),
    owner: f.upper?.name,
    trackCount: f.media_count,
    mine,
    saved: !mine,
  };
}

export async function playlists() {
  const { created, collected } = await folders(true);
  const def = await defaultFolder();
  const out = created.filter((f) => f.id !== def.id).map((f) => folderPlaylist(f, true));
  for (const c of collected) {
    if (c.type === 21) {
      out.push({ id: `season:${c.id}`, name: c.title, imageUrl: image(c.cover), owner: c.upper?.name, trackCount: c.media_count, saved: true });
    } else {
      out.push(folderPlaylist(c, false));
    }
  }
  return out;
}

async function getPlaylist({ id }) {
  const [kind, num] = String(id).split(":");
  if (kind === "fav") {
    const { info, medias } = await wholeFolder(num);
    const mine = String(info?.upper?.mid) === myMid();
    return { ...folderPlaylist({ ...info, media_count: medias.length }, mine), description: info?.intro, tracks: medias.map(mediaTrack) };
  }
  if (kind === "season") {
    const tracks = [];
    let info = null;
    for (let pn = 1; pn <= 50; pn++) {
      const d = await get("/x/space/fav/season/list", { season_id: num, pn, ps: 20 });
      info ||= d.info;
      tracks.push(...(d.medias || []).map(mediaTrack));
      if (!d.medias?.length || tracks.length >= (d.info?.media_count || 0)) break;
    }
    return {
      id,
      name: info?.title || "",
      imageUrl: image(info?.cover),
      owner: info?.upper?.name,
      description: info?.intro,
      trackCount: tracks.length,
      saved: true,
      tracks,
    };
  }
  throw fail("unavailable", t("no such playlist"));
}

export { getPlaylist as playlist };

function folderId(id) {
  const m = String(id).match(/^fav:(\d+)$/);
  if (!m) throw fail("unsupported", t("Only your own favorites folders can be changed"));
  return m[1];
}

export async function createPlaylist({ name }) {
  const f = await post("/x/v3/fav/folder/add", { title: name, privacy: 0 });
  folderCache = null;
  return folderPlaylist({ ...f, media_count: 0 }, true);
}

export async function renamePlaylist({ id, name }) {
  await post("/x/v3/fav/folder/edit", { media_id: folderId(id), title: name });
  folderCache = null;
}

export async function addToPlaylist({ id, trackIds }) {
  const f = folderId(id);
  for (const tid of trackIds) await setFavorite(tid, f, true);
  folderCache = null;
}

export async function removeFromPlaylist({ id, trackIds }) {
  const f = folderId(id);
  for (const tid of trackIds) await setFavorite(tid, f, false);
  folderCache = null;
}

// ---------------------------------------------------------------------------
// History and the station of related videos.

export async function recent() {
  if (!signedIn()) return [];
  const d = await get("/x/web-interface/history/cursor", { ps: 30, type: "archive" });
  return (d.list || [])
    .filter((h) => h.history?.business === "archive" && h.history.bvid)
    .map((h) =>
      track({
        bvid: h.history.bvid,
        cid: h.history.cid,
        title: h.title,
        part: h.history.part,
        page: h.history.page,
        pages: h.videos,
        pic: h.cover,
        mid: h.author_mid,
        author: h.author_name,
        duration: h.duration,
      }),
    );
}

export function played({ id }) {
  host.storage.set("lastPlayed", id);
}

export function stations() {
  const list = [{ id: "related", name: t("Related"), description: t("Goes on from what you played last") }];
  if (signedIn()) list.push({ id: "recommended", name: t("Recommended"), description: t("Bilibili's picks for you") });
  return list;
}

const stationSeen = [];

export async function stationNext({ id }) {
  let tracks;
  if (id === "recommended") {
    tracks = await recommended();
  } else {
    const seed = splitId(host.storage.get("lastPlayed") || "BV1GJ411x7h7").bvid;
    const list = await get("/x/web-interface/archive/related", /^av/i.test(seed) ? { aid: seed.slice(2) } : { bvid: seed });
    const music = (list || []).filter((v) => musicTids.has(v.tid));
    tracks = (music.length >= 3 ? music : list || []).map((v) =>
      track({ bvid: v.bvid, cid: v.cid, title: v.title, pic: v.pic, mid: v.owner?.mid, author: v.owner?.name, duration: v.duration }),
    );
  }
  const fresh = tracks.filter((tr) => !stationSeen.includes(splitId(tr.id).bvid)).slice(0, 5);
  const out = fresh.length ? fresh : tracks.slice(0, 5);
  for (const tr of out) stationSeen.push(splitId(tr.id).bvid);
  stationSeen.splice(0, Math.max(0, stationSeen.length - 300));
  return out;
}

// ---------------------------------------------------------------------------
// Comments.

function comment(r) {
  return {
    id: r.rpid_str || String(r.rpid),
    author: r.member?.uname || "",
    avatarUrl: image(r.member?.avatar, 120),
    text: clean(r.content?.message),
    time: (r.ctime || 0) * 1000,
    likes: r.like || 0,
    liked: r.action === 1,
  };
}

export async function comments({ trackId, offset }) {
  const { aid } = await locate(trackId);
  const pn = Math.floor(offset / 20) + 1;
  const d = await get("/x/v2/reply", { type: 1, oid: aid, pn, ps: 20, sort: 1 });
  const total = d.page?.count || 0;
  const replies = (d.replies || []).map(comment);
  // Signed out, Bilibili answers the first page only.
  return { comments: replies, total, more: signedIn() && replies.length > 0 && pn * 20 < total };
}

export async function likeComment({ trackId, commentId, on }) {
  const { aid } = await locate(trackId);
  await post("/x/v2/reply/action", { type: 1, oid: aid, rpid: commentId, action: on ? 1 : 0 });
}

export async function postComment({ trackId, text }) {
  const { aid } = await locate(trackId);
  const d = await post("/x/v2/reply/add", { type: 1, oid: aid, message: text, plat: 1 });
  return comment(d.reply || { rpid: d.rpid, content: { message: text }, ctime: Date.now() / 1000 });
}

// ---------------------------------------------------------------------------
// Lyrics: a video's own subtitles, when it has them as a track rather than
// burned into the picture. The uploader's over Bilibili's AI ones, Chinese
// first; with subtitles in another language too, the other language is the
// lyric and the Chinese its translation, line by line as their times match.

const isAI = (s) => /^ai-/.test(s.lan || "");
const isChinese = (s) => /zh/.test(s.lan || "");

async function subtitleLines(sub) {
  let url = sub.subtitle_url;
  if (url.startsWith("//")) url = `https:${url}`;
  const res = await fetch(url.replace(/^http:/, "https:"), { headers: { "User-Agent": UA, Referer: REFERER } });
  const body = await res.json();
  return (body.body || [])
    .map((l) => ({
      start: Math.round(l.from * 1000),
      end: Math.round(l.to * 1000),
      // AI subtitles mark what's sung with ♪, and an instrumental stretch as
      // "♪ 音乐 ♪" or the like, which says nothing.
      text: clean(l.content).replace(/[♪♫♬]/g, "").replace(/\s+/g, " ").trim(),
    }))
    .filter((l) => l.text && !/^[(（\[【]?(音乐|纯音乐|music|instrumental)[)）\]】]?$/i.test(l.text));
}

export async function lyrics({ id }) {
  const { bvid, cid, aid } = await locate(id);
  const d = await get("/x/player/wbi/v2", { aid, bvid, cid }, { wbi: true });
  const subs = (d.subtitle?.subtitles || []).filter((s) => s.subtitle_url);
  const rank = (s) => (isAI(s) ? 2 : 0) + (isChinese(s) ? 0 : 1);
  subs.sort((a, b) => rank(a) - rank(b));
  if (!subs.length) throw fail("unavailable", t("This video has no subtitles"));

  const chinese = subs.find((s) => !isAI(s) && isChinese(s));
  const other = subs.find((s) => !isAI(s) && !isChinese(s));
  if (chinese && other) {
    const [lines, zh] = await Promise.all([subtitleLines(other), subtitleLines(chinese)]);
    if (lines.length) {
      for (const l of lines) {
        const match = zh.find((z) => Math.abs(z.start - l.start) <= 400);
        if (match && match.text !== l.text) l.translation = match.text;
      }
      return { lines };
    }
  }
  const lines = await subtitleLines(subs[0]);
  if (!lines.length) throw fail("unavailable", t("This video has no subtitles"));
  return { lines };
}

// ---------------------------------------------------------------------------
// Playing: Bilibili's audio, by the app itself, or through ffmpeg for HE-AAC.

let ffmpegPath = null;

// How to get ffmpeg, said for the system the app runs on.
function installFFmpeg() {
  switch (host.platform) {
    case "darwin":
      return t("This video plays through ffmpeg, which isn't installed. Install it with Homebrew (brew install ffmpeg), then play again.");
    case "windows":
      return t("This video plays through ffmpeg, which isn't installed. Install it (in a terminal: winget install Gyan.FFmpeg), then restart MyGo Music.");
    default:
      return t("This video plays through ffmpeg, which isn't installed. Install it with your package manager (such as sudo apt install ffmpeg), then play again.");
  }
}

// An ffmpeg that's there but won't start, often one Homebrew upgraded a
// library of (dyld: Library not loaded), wants reinstalling, not installing.
function reinstallFFmpeg(why) {
  let text;
  switch (host.platform) {
    case "darwin":
      text = t("This video plays through ffmpeg, which is installed but won't start. Reinstall it with Homebrew (brew reinstall ffmpeg), then play again.");
      break;
    case "windows":
      text = t("This video plays through ffmpeg, which is installed but won't start. Reinstall it, then restart MyGo Music.");
      break;
    default:
      text = t("This video plays through ffmpeg, which is installed but won't start. Reinstall it with your package manager, then play again.");
  }
  return why ? `${text} (ffmpeg: ${why.length > 160 ? why.slice(0, 160) + "…" : why})` : text;
}

// GUI apps don't get the shell's PATH on macOS, so Homebrew's places are tried
// too: the same ones the process permission names. On Windows and Linux the
// PATH finds it. No ffmpeg isn't a "setup" error: the app takes those for a
// setting left empty, and shows "fill these in" in place of what to do. One
// that isn't there fails to start; one that starts and fails is broken.
async function ffmpeg() {
  if (ffmpegPath) return ffmpegPath;
  let broken = null;
  for (const path of ["ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"]) {
    let proc;
    try {
      proc = host.spawn(path, ["-version"]);
    } catch (e) {
      if (/allow/i.test(e.message)) throw fail("setup", t("Allow Bilibili to start programs in its settings"));
      continue;
    }
    proc.closeStdin();
    while ((await proc.readLine()) !== null) {}
    if ((await proc.wait()) === 0) return (ffmpegPath = path);
    if (broken === null) broken = (proc.errorOutput() || "").split("\n").map((l) => l.trim()).find(Boolean) || "";
  }
  if (broken !== null) throw fail("brokenFFmpeg", reinstallFFmpeg(broken));
  throw fail("noFFmpeg", installFFmpeg());
}

// The audio of a part: FLAC when asked for lossless and there is some; else
// AAC-LC, the best for High and one of at most about 132 kbps for Standard; else, as
// the app decodes no HE-AAC, the best there is, for ffmpeg to decode.
// Dolby is left out. Mirrors on mcdn hosts are
// peer-to-peer caches that often stall, so the upos ones go first.
async function audioOf({ bvid, cid }, quality) {
  const lossless = quality === "lossless";
  const params = { bvid, cid, fnval: lossless ? 4048 : 16, fourk: 0, fnver: 0 };
  let d;
  try {
    d = await get("/x/player/wbi/playurl", params, { wbi: true });
  } catch (e) {
    if (e.code === "unavailable" || e.code === "signIn") throw e;
    d = await get("/x/player/playurl", params);
  }
  const dash = d.dash;
  if (!dash) throw fail("unavailable", t("Bilibili has no audio for this video"));
  const urls = (a) =>
    [a.baseUrl || a.base_url, ...(a.backupUrl || a.backup_url || [])]
      .filter(Boolean)
      .sort((x, y) => /mcdn|szbdyd/.test(x) - /mcdn|szbdyd/.test(y));
  const flac = dash.flac?.audio;
  if (lossless && flac && (flac.baseUrl || flac.base_url)) return { urls: urls(flac), as: "flac", bandwidth: flac.bandwidth };
  const all = (dash.audio || []).filter((a) => a.baseUrl || a.base_url).sort((a, b) => b.bandwidth - a.bandwidth);
  if (!all.length) throw fail("unavailable", t("Bilibili has no audio for this video"));
  const lc = all.filter((a) => /^mp4a\.40\.2$/i.test(a.codecs || ""));
  if (lc.length) {
    const pick = quality === "standard" ? lc.find((a) => a.bandwidth <= 140000) || lc[lc.length - 1] : lc[0];
    return { urls: urls(pick), as: "m4a", bandwidth: pick.bandwidth };
  }
  return { urls: urls(all[0]), as: "he", bandwidth: all[0].bandwidth };
}

// ffmpeg answers the one request the app makes for the track, as it converts,
// so the track starts before all of it has come. No more than a few wait at a
// time, for tracks skipped before the app asked for them.
const serving = [];

// It's decoded to 16-bit WAV: with nothing to encode, ffmpeg gets far ahead
// of the listener in seconds, and the app seeks in a WAV straight to the byte.
// MP3 is many times slower to make, and a FLAC that's still being written
// can't say how long it is, so the app decodes it from the start to seek. An
// hour of WAV is about 635 MB.
async function serve(url) {
  const bin = await ffmpeg();
  while (serving.length >= 3) serving.shift().kill();
  const port = 20000 + Math.floor(Math.random() * 40000);
  const proc = host.spawn(bin, [
    "-nostdin", "-hide_banner", "-nostats", "-loglevel", "info",
    "-user_agent", UA,
    "-headers", `Referer: ${REFERER}\r\n`,
    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
    "-rw_timeout", "15000000",
    "-i", url,
    "-vn", "-map", "0:a:0",
    "-c:a", "pcm_s16le", "-f", "wav",
    "-listen", "1", `http://127.0.0.1:${port}/`,
  ]);
  proc.closeStdin();
  serving.push(proc);
  let stopped = false;
  const timer = setTimeout(() => proc.kill(), 15 * 60 * 1000);
  proc.wait().then(() => {
    stopped = true;
    clearTimeout(timer);
    const at = serving.indexOf(proc);
    if (at >= 0) serving.splice(at, 1);
  });
  // ffmpeg prints what it read just before it opens the port; knocking on the
  // port to find out would use up the one request it answers.
  const until = Date.now() + 15000;
  while (!proc.errorOutput().includes("Input #0")) {
    if (stopped) throw new Error(`ffmpeg: ${proc.errorOutput().trim().split("\n").pop()}`);
    if (Date.now() > until) {
      proc.kill();
      throw new Error(t("The audio didn't start in time"));
    }
    await sleep(30);
  }
  await sleep(100);
  return `http://127.0.0.1:${port}/`;
}

// The app fetches FLAC and AAC-LC from Bilibili itself, with the headers its
// servers want. It takes one address, so it gets the first mirror: upos before
// mcdn, as audioOf put them.
export async function stream({ id, quality }) {
  const where = await locate(id);
  const tier = quality === "standard" ? "standard" : "high";
  const audio = await audioOf(where, quality);
  const headers = { Referer: REFERER, "User-Agent": UA };
  if (audio.as === "flac") return { url: audio.urls[0], type: "m4a", quality: "lossless", headers };
  if (audio.as === "m4a") return { url: audio.urls[0], type: "m4a", quality: tier, bitRate: audio.bandwidth, headers };
  let lastError;
  for (const url of audio.urls.slice(0, 3)) {
    try {
      return { url: await serve(url), type: "wav", quality: tier, bitRate: audio.bandwidth };
    } catch (e) {
      if (e.code === "setup" || e.code === "noFFmpeg" || e.code === "brokenFFmpeg") throw e;
      lastError = e;
      console.warn(`playing ${id} from ${url.slice(0, 60)}: ${e.message}`);
    }
  }
  throw lastError;
}
