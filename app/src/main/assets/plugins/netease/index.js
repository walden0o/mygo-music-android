// MyGo Music plugin. No Node APIs, imports, build step, or external API server.
// API paths / EAPI protocol cross-checked against NeteaseCloudMusicApiEnhanced.
const ORIGIN = "https://music.163.com";
// Validated with both the host's default Go HTTP transport and Node fetch.
const API = "https://interface.music.163.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36 NeteaseMusicDesktop/3.1.17.204416";
const PAGE = 50;
const REQUEST_TIMEOUT = 15000;
let epoch = 0;
let loginFlight = null;
let likeCache = null;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}
function idOf(value) {
  const id = String(value ?? "");
  if (!/^[1-9]\d*$/.test(id)) throw fail("unavailable", "无效的网易云资源 ID");
  return id;
}
function offsetOf(value = 0) {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error("分页位置无效");
  return value;
}
function textOf(value, label) {
  const text = String(value ?? "").trim();
  if (!text) throw new Error(label + "不能为空");
  return text;
}
const savedSession = () => host.storage.get("session") || null;
function requireAccount() {
  const session = savedSession();
  if (!session?.account?.id || !cookieObject(session.cookie).MUSIC_U) {
    throw fail("signIn", "请先登录网易云音乐");
  }
  return session.account;
}
function clearSession() {
  epoch++;
  likeCache = null;
  host.storage.remove("session");
}
function cookieObject(text = "") {
  const values = {};
  for (const part of text.split(";")) {
    const pos = part.indexOf("=");
    if (pos <= 0) continue;
    const key = part.slice(0, pos).trim();
    const value = part.slice(pos + 1).trim();
    try { values[key] = decodeURIComponent(value); }
    catch { values[key] = value; }
  }
  return values;
}
function cookieText(values) {
  return Object.keys(values).map((key) => key + "=" + encodeURIComponent(values[key])).join("; ");
}
function responseCookies(headers) {
  const rows = typeof headers.getSetCookie === "function"
    ? headers.getSetCookie()
    : (headers.get("set-cookie") || "").split(/,\s*(?=[A-Za-z0-9_-]+=)/);
  return rows.filter(Boolean).map((row) => row.split(";", 1)[0]).join("; ");
}
function deviceID() {
  let id = host.storage.get("deviceId");
  if (!id) {
    id = host.crypto.md5("mygo-music:" + Date.now() + ":" + Math.random());
    host.storage.set("deviceId", id);
  }
  return id;
}

// e_r=false asks for JSON: the host exposes encryption, not response decryption.
// No automatic retries: a timed-out mutation may already have reached NetEase.
async function api(path, data = {}, options = {}) {
  if (options.auth) requireAccount();
  const generation = epoch;
  const session = savedSession();
  const cookies = cookieObject(options.jar ? options.jar.value : session?.cookie);
  const header = {
    os: "pc", osver: "Microsoft-Windows-10-Professional-build-19045-64bit",
    appver: "3.1.17.204416", deviceId: deviceID(), channel: "netease",
    buildver: "1700000000", resolution: "1920x1080", __csrf: cookies.__csrf || "",
    requestId: Date.now() + "_" + String(Math.floor(Math.random() * 10000)).padStart(4, "0"),
  };
  for (const key of ["MUSIC_U", "MUSIC_A", "NMTID"]) {
    if (cookies[key]) header[key] = cookies[key];
  }
  const json = JSON.stringify({ ...data, csrf_token: cookies.__csrf || "", e_r: false, header });
  const digest = host.crypto.md5("nobody" + path + "use" + json + "md5forencrypt");
  const params = host.crypto.aesEcbEncrypt("e82ckenh8dichen8", path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);
  try {
    const response = await fetch(API + "/eapi/" + path.slice(5), {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": UA, referer: ORIGIN + "/", cookie: cookieText({ ...cookies, ...header }),
      },
      body: "params=" + params.toUpperCase(), signal: controller.signal,
    });
    if (!response.ok) throw new Error("网易云请求失败（HTTP " + response.status + "）");
    let body;
    try { body = await response.json(); }
    catch { throw new Error("网易云返回了无法识别的数据，请稍后重试"); }
    if (!body || typeof body !== "object") throw new Error("网易云返回了空数据");
    const code = Number(body.code ?? 200);
    if ([301, 302, 401].includes(code)) {
      if (!options.jar && generation === epoch && session) clearSession();
      throw fail("signIn", "网易云登录已过期，请重新登录");
    }
    if (code !== 200 && !(options.codes || []).includes(code)) {
      const message = String(body.message || body.msg || "网易云接口返回错误");
      throw fail(code === 404 ? "unavailable" : "", message + "（" + code + "）");
    }
    const received = responseCookies(response.headers);
    if (received) {
      const cookie = cookieText({ ...cookies, ...cookieObject(received) });
      if (options.jar) options.jar.value = cookie;
      else if (session && generation === epoch) host.storage.set("session", { ...session, cookie });
    }
    return body;
  } catch (error) {
    if (controller.signal.aborted) throw new Error("连接网易云超时，请重试");
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function toAccount(profile) {
  return { id: String(profile.userId), name: profile.nickname || "网易云用户", imageUrl: image(profile.avatarUrl) };
}
const image = (url) => typeof url === "string" ? url.replace(/^http:\/\//, "https://") : "";
const list = (value) => Array.isArray(value) ? value : [];
const optionalID = (id) => /^[1-9]\d*$/.test(String(id ?? "")) ? String(id) : "";
function toArtist(value = {}) {
  return { id: optionalID(value.id), name: value.name || "", imageUrl: image(value.picUrl || value.img1v1Url), following: !!value.followed };
}
function toAlbum(value = {}) {
  const stamp = Number(value.publishTime);
  return {
    id: optionalID(value.id), name: value.name || "", imageUrl: image(value.picUrl || value.blurPicUrl),
    artists: list(value.artists || (value.artist ? [value.artist] : [])).map(toArtist),
    released: stamp > 0 ? new Date(stamp).toISOString().slice(0, 10) : "",
    saved: !!(value.subscribed || value.isSub),
  };
}
function toTrack(value = {}, privilege = {}) {
  return {
    id: String(value.id ?? ""), name: value.name || "未知歌曲",
    artists: list(value.ar || value.artists).map(toArtist), album: toAlbum(value.al || value.album),
    duration: Number(value.dt ?? value.duration ?? 0), url: ORIGIN + "/#/song?id=" + value.id,
    unavailable: Number(privilege.st ?? value.privilege?.st ?? 0) < 0,
  };
}
function toPlaylist(value = {}) {
  const userId = savedSession()?.account?.id;
  return {
    id: String(value.id ?? ""), name: value.name || "", imageUrl: image(value.coverImgUrl || value.picUrl),
    description: value.description || value.copywriter || "", owner: value.creator?.nickname || "",
    trackCount: Number(value.trackCount ?? value.songCount ?? 0),
    mine: !!userId && String(value.creator?.userId ?? value.userId) === userId,
    saved: !!value.subscribed,
  };
}
function toShow(value = {}) {
  return {
    id: String(value.id ?? ""), name: value.name || "", imageUrl: image(value.picUrl),
    description: value.desc || "", publisher: value.dj?.nickname || "",
    episodeCount: Number(value.programCount ?? 0), subscribed: !!(value.subed || value.subscribed),
  };
}
function toEpisode(program) {
  const track = toTrack(program.mainSong);
  const radio = program.radio || {};
  return {
    ...track, name: program.name || track.name, showId: String(radio.id || ""),
    album: { id: "", name: radio.name || "", imageUrl: image(program.coverUrl || radio.picUrl) },
    artists: [{ id: "", name: program.dj?.nickname || radio.name || "" }],
    description: program.description || "",
    published: program.createTime ? new Date(program.createTime).toISOString().slice(0, 10) : "",
    url: ORIGIN + "/#/program?id=" + program.id,
  };
}
function toComment(value) {
  return {
    id: String(value.commentId), author: value.user?.nickname || "网易云用户",
    avatarUrl: image(value.user?.avatarUrl), text: value.content || "", time: Number(value.time || 0),
    likes: Number(value.likedCount || 0), liked: !!value.liked,
  };
}

async function completeLogin(jar, generation) {
  const body = await api("/api/w/nuser/account/get", {}, { jar });
  if (!body.profile?.userId || !cookieObject(jar.value).MUSIC_U) throw fail("signIn", "未取得登录状态，请重新登录");
  if (generation !== epoch) throw fail("signIn", "本次登录已取消");
  const me = toAccount(body.profile);
  host.storage.set("session", { cookie: jar.value, account: me });
  likeCache = null;
  return me;
}
async function login() {
  const generation = ++epoch;
  const jar = { value: "" };
  const method = (host.setting("loginMethod") || "qr").trim().toLowerCase();
  if (method === "web") {
    const result = await host.webSignIn({ url: ORIGIN + "/#/login", cookiesOf: ORIGIN + "/", until: ["MUSIC_U"], userAgent: UA });
    jar.value = result.cookie || "";
    return completeLogin(jar, generation);
  }
  if (method !== "qr") throw fail("setup", "登录方式请填 qr 或 web，或留空");
  const key = (await api("/api/login/qrcode/unikey", { type: 3 }, { jar })).unikey;
  if (!key) throw new Error("网易云未返回登录二维码，请稍后重试");
  const qr = ORIGIN + "/login?codekey=" + encodeURIComponent(key);
  host.signInPrompt({ qr, text: "请用网易云音乐 App 扫码，并在手机上确认登录" });
  // Bounded below the host's five-minute sign-in deadline, including requests.
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline && generation === epoch) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    if (generation !== epoch) break;
    const body = await api("/api/login/qrcode/client/login", { key, type: 3 }, { jar, codes: [800, 801, 802, 803] });
    if (body.code === 800) throw fail("signIn", "二维码已过期，请重新点击登录");
    if (body.code === 803) return completeLogin(jar, generation);
    if (![801, 802].includes(body.code)) throw new Error("网易云返回了未知扫码状态，请重试");
    host.signInPrompt({ qr, text: body.code === 802 ? "扫码成功，请在手机上确认登录" : "等待网易云音乐 App 扫码" });
  }
  throw fail("signIn", generation === epoch ? "扫码登录超时，请重新登录" : "本次登录已取消");
}
export function signIn() {
  if (!loginFlight) loginFlight = login().finally(() => { loginFlight = null; });
  return loginFlight;
}
// Sign out locally; this plugin does not invalidate other NetEase clients.
export function signOut() { clearSession(); }
export async function account() {
  if (!savedSession()) return null;
  const generation = epoch;
  let body;
  try { body = await api("/api/w/nuser/account/get"); }
  catch (error) { if (error.code === "signIn") return null; throw error; }
  if (generation !== epoch) return savedSession()?.account || null;
  if (!body.profile?.userId) { clearSession(); return null; }
  const me = toAccount(body.profile);
  host.storage.set("session", { ...savedSession(), account: me });
  return me;
}

const SEARCH = {
  tracks: [1, "songs", "songCount", toTrack], albums: [10, "albums", "albumCount", toAlbum],
  artists: [100, "artists", "artistCount", toArtist], playlists: [1000, "playlists", "playlistCount", toPlaylist],
  shows: [1009, "djRadios", "djRadiosCount", toShow],
};
export async function search({ query, kind = "tracks", offset = 0 }) {
  const config = SEARCH[kind];
  if (!config) throw fail("unsupported", "不支持此搜索类型");
  offset = offsetOf(offset);
  if (!String(query || "").trim()) return { [kind]: [], more: false };
  const [type, key, count, convert] = config;
  const result = (await api("/api/search/get", { s: query, type, limit: PAGE, offset })).result || {};
  const items = list(result[key]);
  return { [kind]: items.map((item) => convert(item)), more: offset + items.length < Number(result[count] || 0) || (!!result.hasMore && items.length > 0) };
}
export async function suggest({ query }) {
  if (!String(query || "").trim()) return [];
  const result = (await api("/api/search/suggest/keyword", { s: query })).result || {};
  return [...new Set(list(result.allMatch).map((item) => item.keyword).filter(Boolean))].slice(0, 10);
}

async function songDetails(ids) {
  const ordered = ids.map(idOf);
  const unique = [...new Set(ordered)];
  const found = new Map();
  // At most four concurrent requests; restore order after the service reorders it.
  for (let base = 0; base < unique.length; base += 2000) {
    const tasks = [];
    for (let at = base; at < Math.min(base + 2000, unique.length); at += 500) {
      const batch = unique.slice(at, at + 500);
      tasks.push(api("/api/v3/song/detail", { c: JSON.stringify(batch.map((id) => ({ id }))) }));
    }
    for (const body of await Promise.all(tasks)) {
      const privileges = new Map(list(body.privileges).map((p) => [String(p.id), p]));
      for (const song of list(body.songs)) found.set(String(song.id), toTrack(song, privileges.get(String(song.id))));
    }
  }
  return ordered.map((id) => found.get(id) || { id, name: "歌曲 " + id + "（暂不可用）", artists: [], album: { id: "", name: "" }, duration: 0, unavailable: true });
}
async function rawPlaylist(id) {
  const body = await api("/api/v6/playlist/detail", { id: idOf(id), n: 100000, s: 0 });
  if (!body.playlist) throw fail("unavailable", "歌单不存在或无权访问");
  return body.playlist;
}
export async function playlist({ id }) {
  const raw = await rawPlaylist(id);
  const ids = list(raw.trackIds).map((item) => String(item.id));
  const tracks = ids.length ? await songDetails(ids) : list(raw.tracks).map((song) => toTrack(song));
  return { ...toPlaylist(raw), tracks, trackCount: Number(raw.trackCount ?? tracks.length) };
}
export async function playlists() {
  const me = requireAccount();
  const result = [];
  for (let offset = 0; ; offset += 1000) {
    const body = await api("/api/user/playlist", { uid: me.id, limit: 1000, offset, includeVideo: false }, { auth: true });
    const rows = list(body.playlist);
    result.push(...rows.map(toPlaylist));
    if (!body.more || rows.length < 1000) return result;
  }
}
export async function album({ id }) {
  const body = await api("/api/v1/album/" + idOf(id));
  if (!body.album) throw fail("unavailable", "专辑不存在");
  return { album: toAlbum(body.album), tracks: list(body.songs).map((song) => toTrack(song)) };
}
export async function artist({ id }) {
  id = idOf(id);
  const [body, albums] = await Promise.all([
    api("/api/v1/artist/" + id), api("/api/artist/albums/" + id, { offset: 0, limit: 100, total: true }),
  ]);
  if (!body.artist) throw fail("unavailable", "歌手不存在");
  return { artist: toArtist(body.artist), tracks: list(body.hotSongs).map((song) => toTrack(song)), albums: list(albums.hotAlbums).map(toAlbum) };
}

async function likedIDs() {
  const me = requireAccount();
  if (likeCache?.uid === me.id && Date.now() - likeCache.time < 60000) return likeCache.ids;
  const generation = epoch;
  const body = await api("/api/song/like/get", { uid: me.id }, { auth: true });
  const ids = list(body.ids).map(String);
  if (generation === epoch) likeCache = { uid: me.id, time: Date.now(), ids };
  return ids;
}
export async function liked({ offset = 0 }) {
  offset = offsetOf(offset);
  const ids = await likedIDs();
  return { tracks: await songDetails(ids.slice(offset, offset + PAGE)), more: offset + PAGE < ids.length };
}
export async function isLiked({ ids }) {
  const liked = savedSession() ? new Set(await likedIDs()) : new Set();
  return Object.fromEntries(ids.map((id) => [String(id), liked.has(String(id))]));
}
export async function like({ id, on }) {
  await api("/api/radio/like", { trackId: idOf(id), like: !!on, alg: "itembased", time: "3" }, { auth: true });
  likeCache = null;
}
export async function recent() {
  const body = await api("/api/play-record/song/list", { limit: 100 }, { auth: true });
  return list(body.data?.list).filter((row) => row.data?.id).map((row) => toTrack(row.data));
}

export async function createPlaylist({ name }) {
  const body = await api("/api/playlist/create", { name: textOf(name, "歌单名称"), privacy: "0", type: "NORMAL" }, { auth: true });
  if (!body.playlist) throw new Error("网易云未返回新建的歌单");
  return { ...toPlaylist(body.playlist), mine: true };
}
export async function renamePlaylist({ id, name }) {
  await api("/api/playlist/update/name", { id: idOf(id), name: textOf(name, "歌单名称") }, { auth: true });
}
export async function deletePlaylist({ id }) {
  await api("/api/playlist/remove", { ids: JSON.stringify([idOf(id)]) }, { auth: true });
}
async function changeTracks(id, trackIds, op) {
  if (!trackIds.length) return;
  await api("/api/playlist/manipulate/tracks", { pid: idOf(id), trackIds: JSON.stringify(trackIds.map(idOf)), op, imme: "true" }, { auth: true });
}
export async function addToPlaylist({ id, trackIds }) { await changeTracks(id, trackIds, "add"); }
export async function removeFromPlaylist({ id, trackIds }) { await changeTracks(id, trackIds, "del"); }
export async function movePlaylistTrack({ id, from, to }) {
  requireAccount();
  const raw = await rawPlaylist(id);
  const ids = list(raw.trackIds).map((item) => idOf(item.id));
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < 0 || from >= ids.length || to >= ids.length) throw new Error("歌曲位置无效，请刷新歌单");
  if (from === to) return;
  ids.splice(to, 0, ids.splice(from, 1)[0]);
  await api("/api/playlist/manipulate/tracks", { pid: idOf(id), trackIds: JSON.stringify(ids), op: "update" }, { auth: true });
}
export async function saveAlbum({ id, on }) {
  await api("/api/album/" + (on ? "sub" : "unsub"), { id: idOf(id) }, { auth: true });
}
export async function followArtist({ id, on }) {
  id = idOf(id);
  await api("/api/artist/" + (on ? "sub" : "unsub"), { artistId: id, artistIds: JSON.stringify([id]) }, { auth: true });
}
export async function savedAlbums({ offset = 0 }) {
  offset = offsetOf(offset);
  const body = await api("/api/album/sublist", { offset, limit: PAGE, total: true }, { auth: true });
  const albums = list(body.data).map((value) => ({ ...toAlbum(value), saved: true }));
  return { albums, more: typeof body.hasMore === "boolean" ? body.hasMore : offset + albums.length < Number(body.count || 0) };
}

export async function home() {
  const body = await api("/api/personalized/playlist", { limit: 24, total: true, n: 1000 });
  return {
    title: "发现音乐", subtitle: "网易云音乐",
    sections: [
      { title: "为你发现", links: [
        { title: "每日推荐", subtitle: "根据你的听歌偏好推荐", page: "daily" },
        { title: "排行榜", subtitle: "发现正在流行的音乐", page: "charts" },
      ] },
      { title: "推荐歌单", playlists: list(body.result).map(toPlaylist) },
    ],
  };
}
export async function browse({ page }) {
  if (page === "charts") {
    const body = await api("/api/toplist");
    return { title: "排行榜", sections: [{ playlists: list(body.list).map(toPlaylist) }] };
  }
  if (page === "daily") {
    const [songs, resources] = await Promise.all([
      api("/api/v3/discovery/recommend/songs", {}, { auth: true }),
      api("/api/v1/discovery/recommend/resource", {}, { auth: true }),
    ]);
    return { title: "每日推荐", sections: [
      { title: "今日歌曲", tracks: list(songs.data?.dailySongs || songs.recommend).map((song) => toTrack(song)) },
      { title: "推荐歌单", playlists: list(resources.recommend).map(toPlaylist) },
    ] };
  }
  throw fail("unavailable", "没有这个页面");
}
export function stations() {
  return [{ id: "personal-fm", name: "私人 FM", description: "根据你的口味持续推荐，需要登录" }];
}
export async function stationNext({ id }) {
  if (id !== "personal-fm") throw fail("unavailable", "电台不存在");
  return list((await api("/api/v1/radio/get", {}, { auth: true })).data).map((song) => toTrack(song));
}
export async function shows() {
  const result = [];
  for (let offset = 0; ; offset += 1000) {
    const body = await api("/api/djradio/get/subed", { offset, limit: 1000, total: true }, { auth: true });
    const rows = list(body.djRadios);
    result.push(...rows.map((row) => ({ ...toShow(row), subscribed: true })));
    if (!body.hasMore || rows.length < 1000) return result;
  }
}
export async function show({ id, offset = 0 }) {
  id = idOf(id);
  offset = offsetOf(offset);
  const [detail, body] = await Promise.all([
    api("/api/djradio/v2/get", { id }),
    api("/api/dj/program/byradio", { radioId: id, offset, limit: PAGE, asc: false }),
  ]);
  if (!detail.data) throw fail("unavailable", "播客不存在");
  const episodes = list(body.programs).map(toEpisode);
  return { show: toShow(detail.data), episodes, more: typeof body.more === "boolean" ? body.more : offset + episodes.length < Number(body.count || 0) };
}
export async function subscribeShow({ id, on }) {
  await api("/api/djradio/" + (on ? "sub" : "unsub"), { id: idOf(id) }, { auth: true });
}

// Read-only comments: adding comments / saving third-party playlists currently
// need extra anti-abuse tokens. Their exports are deliberately absent.
export async function comments({ trackId, offset = 0 }) {
  trackId = idOf(trackId);
  offset = offsetOf(offset);
  const body = await api("/api/v1/resource/comments/R_SO_4_" + trackId, { rid: trackId, offset, limit: PAGE, beforeTime: 0 });
  return { comments: list(body.comments).map(toComment), total: Number(body.total || 0), more: !!body.more };
}
export async function likeComment({ trackId, commentId, on }) {
  await api("/api/v1/comment/" + (on ? "like" : "unlike"), { threadId: "R_SO_4_" + idOf(trackId), commentId: idOf(commentId) }, { auth: true });
}

function parseYRC(text) {
  const lines = [];
  for (const row of String(text || "").split(/\r?\n/)) {
    const line = /^\[(\d+),(\d+)\](.*)$/.exec(row);
    if (!line) continue;
    const words = [];
    const pattern = /\((\d+),(\d+),[^)]*\)/g;
    const markers = [];
    let match;
    while ((match = pattern.exec(line[3]))) markers.push({ at: match.index, after: pattern.lastIndex, start: Number(match[1]), length: Number(match[2]) });
    for (let i = 0; i < markers.length; i++) {
      const marker = markers[i];
      words.push({ start: marker.start, end: marker.start + marker.length, text: line[3].slice(marker.after, markers[i + 1]?.at) });
    }
    if (words.length) lines.push({ start: Number(line[1]), end: Number(line[1]) + Number(line[2]), text: words.map((word) => word.text).join(""), words });
  }
  return lines;
}
function translations(lrc) {
  const lines = [];
  for (const row of String(lrc || "").split(/\r?\n/)) {
    const text = row.replace(/\[\d+:\d+(?:\.\d+)?\]/g, "").trim();
    const pattern = /\[(\d+):(\d+)(?:\.(\d+))?\]/g;
    let match;
    while ((match = pattern.exec(row))) lines.push({ start: Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number((match[3] || "").padEnd(3, "0").slice(0, 3)), text });
  }
  return lines;
}
export async function lyrics({ id }) {
  const body = await api("/api/song/lyric/v1", { id: idOf(id), cp: false, lv: 0, tv: 0, rv: 0, kv: 0, yv: 0, ytv: 0, yrv: 0 });
  const lines = parseYRC(body.yrc?.lyric);
  const translation = body.ytlyric?.lyric || body.tlyric?.lyric || "";
  if (lines.length) {
    const translated = translations(translation);
    for (const line of lines) {
      const nearby = translated.filter((item) => Math.abs(item.start - line.start) <= 500).sort((a, b) => Math.abs(a.start - line.start) - Math.abs(b.start - line.start))[0];
      if (nearby) line.translation = nearby.text;
    }
    return { lines, lrc: body.lrc?.lyric || "", translation };
  }
  return { lrc: body.lrc?.lyric || "", translation };
}
export async function stream({ id, quality = "standard" }) {
  id = idOf(id);
  const levels = { standard: "standard", high: "exhigh", lossless: "lossless" };
  const body = await api("/api/song/enhance/player/url/v1", { ids: JSON.stringify([id]), level: levels[quality] || "standard", encodeType: "flac" });
  const item = list(body.data).find((value) => String(value.id) === id);
  if (!item?.url || Number(item.code) !== 200) throw fail("unavailable", "此歌曲当前无法播放，可能需要登录、会员或单独购买，或在当前地区不可用");
  if (item.freeTrialInfo || item.freeTrialPrivilege?.listenType === 1) throw fail("unavailable", "此账号只能试听这首歌，请在网易云确认播放权限");
  const type = String(item.type || "").toLowerCase();
  if (!["mp3", "flac", "ogg"].includes(type)) throw fail("unavailable", "宿主暂不支持网易云返回的音频格式：" + (type || "未知"));
  const url = new URL(item.url);
  if (!["https:", "http:"].includes(url.protocol)) throw fail("unavailable", "网易云返回了无效播放地址");
  const gain = Number(item.gain), peak = Number(item.peak);
  return {
    url: url.href, type, quality: item.level || levels[quality] || "standard",
    bitRate: Number(item.br || 0), size: Number(item.size || 0),
    ...(item.gain != null && Number.isFinite(gain) ? { gain } : {}),
    ...(item.peak != null && Number.isFinite(peak) ? { peak } : {}),
    headers: { "User-Agent": UA, Referer: ORIGIN + "/" },
  };
}
