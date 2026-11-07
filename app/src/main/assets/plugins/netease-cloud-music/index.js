// NetEase Cloud Music for MyGo Music, adapted from mygo-cloudmusic.
// This is deliberately one ES module: MyGo compiles the entry without a bundler.
const SITE = "https://music.163.com";
const EAPI = "https://interfacepc.music.163.com";
const EAPI_KEY = "e82ckenh8dichen8";
const UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
const EAPI_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
const PAGE = 50;
const ARTIST_PAGE = 100;
const LEVELS = ["standard", "higher", "exhigh", "lossless", "hires"];
const load = (key, fallback) => host.storage.get(key) ?? fallback;
const save = (key, value) => host.storage.set(key, value);

// Translate the template before inserting service content; replacements are not re-parsed.
function tr(text, values) {
  const translated = host.t(text);
  if (!values) return translated;
  return translated.replace(/\{(\w+)\}/g, (token, key) =>
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : token);
}
let epoch = 0;
let likedCache = null;
let likedPending = null;
let likesRevision = 0;
let heart = null;
let loginPending = null;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}
function check(data, path, allowed = [200]) {
  if (!allowed.includes(Number(data?.code))) {
    const code = Number(data?.code);
    throw fail(code === 301 || code === 302 || code === 401 ? "signIn" : "",
      tr("NetEase {path}: {message} ({code})", { path, message: data?.message || data?.msg || tr("Request failed"), code }));
  }
  return data;
}
function id(value) {
  const text = String(value);
  if (!/^[1-9]\d*$/.test(text) || !Number.isSafeInteger(Number(text))) {
    throw fail("unavailable", tr("Invalid NetEase ID"));
  }
  return text;
}
function offsetOf(value = 0) {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(tr("Invalid page offset"));
  return value;
}
function https(value = "") {
  return String(value || "").replace(/^http:\/\//, "https://");
}
function date(value) {
  return value > 0 ? new Date(value).toISOString().slice(0, 10) : "";
}
function route(page, params = {}) {
  const query = new URLSearchParams(params).toString();
  return page + (query ? `?${query}` : "");
}
function nextSection(page, params, more) {
  return more ? [{ links: [{ title: tr("Next Page"), page: route(page, params) }] }] : [];
}
function accountNow() { return load("account", null); }
function requireAccount() {
  const me = accountNow();
  if (!me || !load("cookies", {}).MUSIC_U) throw fail("signIn", tr("Sign in to NetEase Cloud Music first"));
  return me;
}
function resetSession() {
  epoch++;
  likesRevision++;
  likedCache = likedPending = heart = null;
  for (const key of ["cookies", "account", "recent", "lastArtists", "stationCovers", "stationFallbacks", "discoveryCovers"]) host.storage.remove(key);
}
function cookieHeader(cookies = load("cookies", {})) {
  return Object.entries(cookies).map(([key, value]) => `${key}=${value}`).join("; ");
}
function storeCookies(headers, generation) {
  if (generation !== epoch) return;
  const values = typeof headers.getSetCookie === "function"
    ? headers.getSetCookie()
    : (headers.get("set-cookie") || "").split(/,(?=\s*[^;,\s]+=)/);
  const cookies = load("cookies", {});
  let changed = false;
  for (const line of values) {
    const match = /^\s*(MUSIC_U|__csrf|NMTID)=([^;]*)/.exec(line);
    if (!match) continue;
    if (!match[2] || /;\s*max-age=0(?:;|$)/i.test(line)) delete cookies[match[1]];
    else cookies[match[1]] = match[2];
    changed = true;
  }
  if (changed) save("cookies", cookies);
}
async function request(url, options, generation = epoch) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 18000);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal, redirect: "error" });
    if (!response.ok) throw new Error(tr("NetEase request failed (HTTP {status})", { status: response.status }));
    const result = await response.json();
    storeCookies(response.headers, generation);
    return result;
  } finally {
    clearTimeout(timeout);
  }
}
async function web(path, data = {}, method = "GET", options = {}) {
  const params = new URLSearchParams(data);
  const csrf = load("cookies", {}).__csrf;
  if (csrf && method === "POST") params.set("csrf_token", csrf);
  const query = params.toString();
  const result = await request(SITE + path + (method === "GET" && query ? `?${query}` : ""), {
    method,
    headers: {
      "User-Agent": UA, Referer: `${SITE}/`,
      Cookie: options.cookie ?? cookieHeader(),
      ...(method === "POST" ? { "Content-Type": "application/x-www-form-urlencoded" } : {}),
    },
    ...(method === "POST" ? { body: query } : {}),
  }, options.generation ?? epoch);
  return check(result, path);
}
function deviceID() {
  let device = load("device", "");
  if (!device) {
    // This is a stable client identifier, not a credential.
    device = Array.from({ length: 52 }, () => Math.floor(Math.random() * 16).toString(16)).join("").toUpperCase();
    save("device", device);
  }
  return device;
}
async function eapi(path, data = {}, allowed = [200]) {
  const cookies = load("cookies", {});
  const header = {
    osver: "Microsoft-Windows-10-Professional-build-19045-64bit",
    deviceId: deviceID(), os: "pc", appver: "3.1.17.204416",
    versioncode: "140", mobilename: "", buildver: String(Math.floor(Date.now() / 1000)),
    resolution: "1920x1080", channel: "netease",
    requestId: `${Date.now()}_${String(Math.floor(Math.random() * 1000)).padStart(4, "0")}`,
    __csrf: cookies.__csrf || "",
  };
  for (const key of ["MUSIC_U", "NMTID"]) if (cookies[key]) header[key] = cookies[key];
  const text = JSON.stringify({ ...data, header, e_r: false });
  const digest = host.crypto.md5(`nobody${path}use${text}md5forencrypt`);
  const plain = `${path}-36cd479b6b5-${text}-36cd479b6b5-${digest}`;
  const params = host.crypto.aesEcbEncrypt(EAPI_KEY, plain).toUpperCase();
  const result = await request(EAPI + path.replace(/^\/api\//, "/eapi/"), {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded", "User-Agent": EAPI_UA,
      Cookie: Object.entries(header).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("; "),
    },
    body: new URLSearchParams({ params }).toString(),
  });
  return check(result, path, allowed);
}

export async function account() {
  if (!load("cookies", {}).MUSIC_U) return null;
  const generation = epoch;
  let result;
  try {
    result = await web("/api/nuser/account/get");
  } catch (error) {
    if (error.code !== "signIn") throw error;
    if (generation === epoch) resetSession();
    return null;
  }
  if (generation !== epoch) return null;
  const p = result.profile;
  if (!p?.userId) { resetSession(); return null; }
  const me = { id: String(p.userId), name: p.nickname, imageUrl: https(p.avatarUrl), page: "profile" };
  save("account", me);
  return me;
}
export function signIn() {
  if (!loginPending) {
    loginPending = login().finally(() => { loginPending = null; });
  }
  return loginPending;
}
async function login() {
  resetSession();
  const generation = epoch;
  const active = () => {
    if (generation !== epoch) throw new Error(tr("Sign-in cancelled"));
  };
  if (host.setting("loginMethod") === "web") {
    const result = await host.webSignIn({
      url: `${SITE}/`, cookiesOf: `${SITE}/`, until: ["MUSIC_U"], userAgent: UA,
    });
    active();
    const cookies = {};
    for (const part of result.cookie.split(";")) {
      const match = /^\s*(MUSIC_U|__csrf|NMTID)=(.*)$/.exec(part);
      if (match) cookies[match[1]] = match[2];
    }
    save("cookies", cookies);
  } else {
    const started = Date.now();
    const key = (await eapi("/api/login/qrcode/unikey", { type: 3 })).unikey;
    if (!key) throw new Error(tr("NetEase did not return a sign-in QR code"));
    active();
    const qr = `${SITE}/login?codekey=${encodeURIComponent(key)}`;
    host.signInPrompt({ qr, text: tr("Scan with the NetEase Cloud Music app to sign in") });
    let confirmed = false;
    while (Date.now() - started < 240000) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      active();
      const result = await eapi("/api/login/qrcode/client/login", { key, type: 3 }, [800, 801, 802, 803]);
      active();
      if (result.code === 800) throw new Error(tr("The QR code expired. Please sign in again."));
      if (result.code === 802) host.signInPrompt({ qr, text: tr("Code scanned. Confirm sign-in on your phone.") });
      if (result.code === 803) { confirmed = true; break; }
    }
    if (!confirmed) throw new Error(tr("Sign-in timed out. Please try again."));
  }
  active();
  const me = await account();
  if (!me) throw new Error(tr("No valid session received. Try again or choose web sign-in in plugin settings."));
  host.signInPrompt({ text: tr("Welcome back, {name}", { name: me.name }) });
  return me;
}
export async function signOut() {
  const cookie = cookieHeader();
  resetSession();
  // A late logout/QR response must never restore cookies after signing out.
  try { await web("/api/logout", {}, "POST", { cookie, generation: -1 }); }
  catch { /* Local sign-out still succeeds while offline. */ }
}
export function setSetting({ key, value }) {
  if (key === "quality" && value && !LEVELS.includes(value)) {
    throw fail("setup", tr("Quality must be standard / higher / exhigh / lossless / hires, or empty"));
  }
  if (key === "loginMethod" && value && !["qr", "web"].includes(value)) {
    throw fail("setup", tr("Sign-in method must be qr / web, or empty"));
  }
}

function toArtist(a = {}) {
  return { id: a.id ? String(a.id) : "", name: a.name || "",
    imageUrl: https(a.img1v1Url || a.picUrl), following: !!a.followed };
}
function toAlbum(a = {}) {
  return {
    id: a.id ? String(a.id) : "", name: a.name || "", imageUrl: https(a.picUrl),
    artists: (a.artists || (a.artist ? [a.artist] : [])).map(toArtist),
    released: date(a.publishTime), saved: !!(a.isSub || a.subscribed),
  };
}
function toTrack(t, privilege = t.privilege) {
  return {
    id: String(t.id), name: t.name || "", artists: (t.ar || t.artists || []).map(toArtist),
    album: toAlbum(t.al || t.album), duration: t.dt ?? t.duration ?? 0,
    url: `${SITE}/song?id=${t.id}`,
    unavailable: privilege ? privilege.st < 0 || privilege.pl <= 0 : t.fee === 1 || t.fee === 4,
  };
}
function toPlaylist(p) {
  return {
    id: String(p.id), name: p.name || "", imageUrl: https(p.coverImgUrl || p.picUrl),
    description: p.description || "", owner: p.creator?.nickname || "",
    trackCount: p.trackCount || 0,
    mine: !!accountNow() && String(p.creator?.userId) === accountNow().id,
    fixed: p.specialType === 5,
    saved: !!p.subscribed,
  };
}
function toShow(s) {
  let publisherPage = "";
  try {
    publisherPage = route("user", { id: id(s.dj?.userId) });
  } catch { /* A missing or invalid publisher ID must not hide the podcast. */ }
  return {
    id: String(s.id), name: s.name, imageUrl: https(s.picUrl),
    description: s.desc || "", publisher: s.dj?.nickname || "", publisherPage,
    episodeCount: s.programCount || 0, subscribed: !!s.subed,
  };
}
function toEpisode(p, show) {
  return {
    id: `program:${id(p.id)}:${id(p.mainSong.id)}`, name: p.name,
    artists: show.dj?.nickname ? [{ id: "", name: show.dj.nickname }] : [],
    album: { id: `show:${show.id}`, name: show.name, imageUrl: https(p.coverUrl || show.picUrl) },
    duration: p.duration || p.mainSong.duration || 0, showId: String(show.id),
    description: p.description || "", published: date(p.createTime),
    url: `${SITE}/program?id=${p.id}`,
  };
}
function trackIdentity(value) {
  const match = /^program:(\d+):(\d+)$/.exec(String(value));
  if (match) return { program: id(match[1]), audio: id(match[2]), thread: `A_DJ_1_${id(match[1])}` };
  const song = id(value);
  return { audio: song, thread: `R_SO_4_${song}` };
}
async function details(ids) {
  const generation = epoch;
  const out = [];
  for (let i = 0; i < ids.length; i += 400) {
    const batch = ids.slice(i, i + 400).map(id);
    const result = await eapi("/api/v3/song/detail", { c: JSON.stringify(batch.map(n => ({ id: Number(n) }))) });
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const privileges = new Map((result.privileges || []).map(p => [String(p.id), p]));
    const tracks = new Map((result.songs || []).map(t => [String(t.id), toTrack(t, privileges.get(String(t.id)))]));
    for (const n of batch) if (tracks.has(n)) out.push(tracks.get(n));
  }
  return out;
}
async function hydrate(songs) {
  if (!songs?.length) return [];
  // Keep unavailable entries visible, but prefer the eapi's account-aware privileges.
  const tracks = await details(songs.map(t => t.id));
  const byID = new Map(tracks.map(t => [t.id, t]));
  return songs.map(t => byID.get(String(t.id)) || toTrack(t));
}
async function daily() {
  const generation = epoch;
  const result = await web("/api/v3/discovery/recommend/songs");
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const songs = result.data?.dailySongs || [];
  let tracks;
  try { tracks = await hydrate(songs); }
  catch { tracks = songs.map(song => toTrack(song)); }
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  return tracks;
}
function dailyBlurb() {
  return load("cookies", {}).MUSIC_U
    ? tr("Picked for your taste, updated daily at 06:00")
    : tr("Songs everyone is listening to. Sign in for your own recommendations");
}
async function pickedForYou() {
  const generation = epoch;
  // The public "personalized" endpoint is not the listener's daily recommendations.
  // Cookies may already be restored while the host is still loading the profile.
  const signedIn = !!load("cookies", {}).MUSIC_U;
  if (!signedIn && accountNow()) requireAccount();
  if (!signedIn) return [];
  const result = await web("/api/v1/discovery/recommend/resource");
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  return result.recommend || [];
}
async function recommendedPlaylists() {
  const generation = epoch;
  const result = await web("/api/personalized/playlist", { limit: 30 });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  return result.result || [];
}
function toChart(playlist) {
  return {
    ...toPlaylist(playlist),
    description: [playlist.updateFrequency, ...(playlist.tracks || []).slice(0, 3).map((track, index) =>
      `${index + 1}. ${track.first} · ${track.second}`)].filter(Boolean).join("\n"),
  };
}
async function charts() {
  return ((await web("/api/toplist/detail")).list || []).map(toChart);
}
export async function home() {
  const generation = epoch;
  const [songs, picks, rankings, playlists] = await Promise.allSettled([
    daily(), pickedForYou(), charts(), recommendedPlaylists(),
  ]);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const results = [songs, picks, rankings, playlists];
  if (!results.some(result => result.status === "fulfilled" && result.value.length)) {
    const failed = results.find(result => result.status === "rejected");
    if (failed) throw failed.reason;
  }
  const sections = [];
  // The host renders the first track section with its native daily recommendation card.
  if (songs.status === "fulfilled" && songs.value.length) {
    sections.push({ title: tr("Daily Recommendations"), pageTitle: tr("Daily Song Recommendations"),
      subtitle: dailyBlurb(), tracks: songs.value });
  } else if (songs.status === "rejected") {
    sections.push(retrySection(tr("Daily Recommendations"), "daily", songs.reason));
  }
  if (picks.status === "fulfilled" && picks.value.length) {
    sections.push({ title: tr("Picked for You Today"), playlists: picks.value.map(toPlaylist) });
  } else if (picks.status === "rejected") {
    sections.push(retrySection(tr("Picked for You Today"), "picked-for-you", picks.reason));
  }
  if (rankings.status === "fulfilled" && rankings.value.length) {
    sections.push({ title: tr("Official Charts"), playlists: rankings.value.slice(0, 4), morePage: "charts" });
  } else if (rankings.status === "rejected") {
    sections.push(retrySection(tr("Official Charts"), "charts", rankings.reason));
  }
  if (playlists.status === "fulfilled" && playlists.value.length) {
    sections.push({ title: tr("Recommended Playlists"), playlists: playlists.value.map(toPlaylist) });
  } else if (playlists.status === "rejected") {
    sections.push(retrySection(tr("Recommended Playlists"), "recommended", playlists.reason));
  }
  return { title: tr("For You"), sections };
}

const SEARCH = {
  tracks: [1, "songs", "songCount", toTrack],
  albums: [10, "albums", "albumCount", toAlbum],
  artists: [100, "artists", "artistCount", toArtist],
  playlists: [1000, "playlists", "playlistCount", toPlaylist],
  shows: [1009, "djRadios", "djRadiosCount", toShow],
};
export async function search({ query, kind = "tracks", offset = 0 }) {
  const shape = SEARCH[kind];
  if (!shape) throw fail("unsupported", tr("This search type is not supported."));
  offsetOf(offset);
  query = query.trim();
  if (!query) return { [kind]: [], more: false };
  const result = (await web("/api/cloudsearch/pc", { s: query, type: shape[0], limit: PAGE, offset, total: true })).result || {};
  const raw = result[shape[1]] || [];
  const items = kind === "tracks" ? await hydrate(raw) : raw.map(shape[3]);
  if (kind === "artists" && offset === 0) save("lastArtists", items.slice(0, 20));
  return { [kind]: items, more: raw.length > 0 && offset + raw.length < (result[shape[2]] || 0) };
}
export async function suggest({ query }) {
  if (!query.trim()) return [];
  const result = (await web("/api/search/suggest/web", { s: query })).result || {};
  return [...new Set((result.order || ["songs", "artists", "albums", "playlists"])
    .flatMap(key => (result[key] || []).map(item => item.name)).filter(Boolean))].slice(0, 12);
}

async function userPlaylists(uid) {
  const generation = epoch;
  const all = [];
  for (let offset = 0; ; offset += 200) {
    const result = await web("/api/user/playlist", { uid: id(uid), limit: 200, offset });
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const page = result.playlist || [];
    all.push(...page);
    if (!result.more || !page.length) return all;
  }
}
export async function playlists() {
  return (await userPlaylists(requireAccount().id)).map(toPlaylist);
}
async function playlistRaw(pid) {
  const generation = epoch, target = id(pid);
  const result = await web("/api/v6/playlist/detail", { id: target, n: 1000 });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  if (String(result.playlist?.id) !== target) throw fail("unavailable", tr("This playlist is missing or private"));
  return result.playlist;
}
async function editablePlaylist(pid) {
  requireAccount();
  const generation = epoch;
  const p = await playlistRaw(pid);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  // Protect source-managed lists even when a caller bypasses the host's fixed controls.
  if (p.specialType === 5) throw fail("unsupported", tr("Use song hearts to change your liked songs"));
  return p;
}
export async function playlist({ id: pid }) {
  const generation = epoch;
  const p = await playlistRaw(pid);
  const original = new Map((p.tracks || []).map(t => [String(t.id), toTrack(t)]));
  const ids = (p.trackIds?.length ? p.trackIds : p.tracks || []).map(t => String(t.id));
  const enriched = new Map((await details(ids)).map(t => [t.id, t]));
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  // Keep the playlist order and any usable original metadata missing from song detail.
  const tracks = ids.map(id => enriched.get(id) || original.get(id)).filter(Boolean);
  return { ...toPlaylist(p), tracks };
}
async function likedIDs(refresh = false) {
  const me = requireAccount();
  if (!likedPending && !refresh && likedCache && Date.now() - likedCache.time < 60000) return likedCache.ids;
  if (!likedPending) {
    const generation = epoch;
    const revision = likesRevision;
    const pending = eapi("/api/song/like/get", { uid: me.id }).then(result => {
      if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
      const ids = (result.ids || []).map(String);
      if (revision === likesRevision) likedCache = { time: Date.now(), ids };
      return ids;
    }).finally(() => { if (likedPending === pending) likedPending = null; });
    likedPending = pending;
  }
  return likedPending;
}
export async function liked({ offset = 0 } = {}) {
  offsetOf(offset);
  // A first-page request is also the host's refresh after changes in the service.
  const ids = await likedIDs(offset === 0);
  return { tracks: await details(ids.slice(offset, offset + PAGE)), more: offset + PAGE < ids.length };
}
export async function isLiked({ ids }) {
  const result = Object.fromEntries(ids.map(n => [n, false]));
  if (!accountNow()) return result;
  const songs = ids.filter(n => !trackIdentity(n).program);
  if (songs.length) {
    const liked = new Set(await likedIDs());
    for (const n of songs) result[n] = liked.has(n);
  }
  const episodes = ids.filter(n => trackIdentity(n).program);
  for (let i = 0; i < episodes.length; i += 100) {
    const batch = episodes.slice(i, i + 100);
    const response = await eapi("/api/resource/commentInfo/list", {
      resourceType: "1", resourceIds: JSON.stringify(batch.map(n => Number(trackIdentity(n).program))),
    });
    const likes = new Map((response.data || []).map(p => [String(p.resourceId), !!p.liked]));
    for (const n of batch) result[n] = likes.get(trackIdentity(n).program) || false;
  }
  return result;
}
export async function like({ id: song, on }) {
  requireAccount();
  if (trackIdentity(song).program) return likeEpisode({ id: song, on });
  await eapi("/api/radio/like", { alg: "itembased", trackId: id(song), like: String(!!on), time: "3" });
  likesRevision++;
  likedCache = likedPending = null;
}
export async function likeEpisode({ id: episode, on }) {
  requireAccount();
  const identity = trackIdentity(episode);
  if (!identity.program) throw fail("unsupported", tr("This is not a podcast episode"));
  await eapi(`/api/resource/${on ? "like" : "unlike"}`, { threadId: identity.thread });
}
export async function recent() {
  return recentTracks(100);
}
async function recentTracks(limit) {
  const generation = epoch;
  const local = load("recent", []).slice(0, limit);
  if (!accountNow()) return local;
  const result = await eapi("/api/play-record/song/list", { limit });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const remote = await hydrate((result.data?.list || []).slice(0, limit).map(row => row.data));
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  return [...local, ...remote].filter((t, i, all) => all.findIndex(x => x.id === t.id) === i).slice(0, limit);
}
export async function played({ id: value }) {
  const generation = epoch;
  const identity = trackIdentity(value);
  let track;
  if (identity.program) {
    const result = await web("/api/dj/program/detail", { id: identity.program });
    if (result.program?.radio && result.program?.mainSong) track = toEpisode(result.program, result.program.radio);
  } else {
    [track] = await details([value]);
  }
  if (generation === epoch && track) rememberTrack(track);
}
function rememberTrack(track) {
  save("recent", [track, ...load("recent", []).filter(t => t.id !== track.id)].slice(0, 100));
}

export async function createPlaylist({ name }) {
  requireAccount();
  if (!name.trim()) throw new Error(tr("Playlist name cannot be empty"));
  const result = await eapi("/api/playlist/create", { name: name.trim(), privacy: "0", type: "NORMAL" });
  const p = result.playlist || { id: result.id, name: name.trim() };
  return { ...toPlaylist(p), mine: true, tracks: [] };
}
export async function renamePlaylist({ id: pid, name }) {
  requireAccount();
  if (!name.trim()) throw new Error(tr("Playlist name cannot be empty"));
  await editablePlaylist(pid);
  await eapi("/api/playlist/update/name", { id: id(pid), name: name.trim() });
}
export async function deletePlaylist({ id: pid }) {
  await editablePlaylist(pid);
  await eapi("/api/playlist/remove", { ids: JSON.stringify([Number(id(pid))]) });
}
async function manipulate(op, pid, trackIds) {
  requireAccount();
  const ids = trackIds.map(n => Number(id(n)));
  if (!ids.length) return;
  await editablePlaylist(pid);
  const send = items => eapi("/api/playlist/manipulate/tracks",
    { op, pid: id(pid), trackIds: JSON.stringify(items), imme: "true" }, [200, 502, 512]);
  let result = await send(ids);
  if (result.code === 512) result = await send([...ids, ...ids]);
  if (result.code === 502) throw new Error(tr("This song is already in the playlist"));
  check(result, "playlist/manipulate/tracks");
}
export function addToPlaylist({ id, trackIds }) { return manipulate("add", id, trackIds); }
export function removeFromPlaylist({ id, trackIds }) { return manipulate("del", id, trackIds); }
export async function movePlaylistTrack({ id: pid, from, to }) {
  const p = await editablePlaylist(pid);
  // Never reorder a partial playlist: missing IDs could discard unseen tracks.
  const ids = (p.trackIds || []).map(t => Number(id(t.id)));
  if (ids.length !== p.trackCount) throw new Error(tr("The track list is incomplete. Reordering was cancelled."));
  if (![from, to].every(n => Number.isSafeInteger(n) && n >= 0 && n < ids.length)) throw new Error(tr("Invalid track position"));
  if (from === to) return;
  ids.splice(to, 0, ids.splice(from, 1)[0]);
  await eapi("/api/playlist/manipulate/tracks", { pid: id(pid), trackIds: JSON.stringify(ids), op: "update" });
}
export async function savePlaylist({ id: pid, on }) {
  await editablePlaylist(pid);
  await eapi(`/api/playlist/${on ? "subscribe" : "unsubscribe"}`, { id: id(pid) });
}
export async function album({ id: aid }) {
  if (String(aid).startsWith("show:")) {
    const page = await show({ id: aid.slice(5) });
    return { album: { id: aid, name: page.show.name, imageUrl: page.show.imageUrl }, tracks: page.episodes };
  }
  const result = await web(`/api/v1/album/${id(aid)}`);
  if (!result.album) throw fail("unavailable", tr("Album not found"));
  return { album: toAlbum(result.album), tracks: await hydrate(result.songs || []) };
}
async function artistDetail(aid) {
  const generation = epoch;
  const result = await web(`/api/v1/artist/${aid}`);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  if (String(result.artist?.id) !== aid) throw fail("unavailable", tr("Artist not found"));
  return result;
}
async function artistAlbums(aid, offset = 0, limit = PAGE) {
  const generation = epoch;
  const result = await web(`/api/artist/albums/${aid}`, { limit, offset });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const albums = (result.hotAlbums || []).map(toAlbum);
  return { albums, more: !!result.more && albums.length > 0 };
}
async function allArtistAlbums(aid) {
  const albums = [];
  for (let offset = 0; ; offset += ARTIST_PAGE) {
    const page = await artistAlbums(aid, offset, ARTIST_PAGE);
    albums.push(...page.albums);
    if (!page.more) return albums;
  }
}
async function artistLatest(aid, offset) {
  const generation = epoch;
  const result = await web("/api/v1/artist/songs", { id: aid, order: "time", limit: ARTIST_PAGE, offset }, "POST");
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const songs = result.songs || [];
  const tracks = await details(songs.map(t => t.id));
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  return { tracks, more: !!result.more && songs.length > 0 };
}
function artistBrowseHeader(a) {
  const aliases = [...new Set([a.trans, ...(a.alias || [])].map(s => s?.trim()).filter(Boolean))];
  const meta = [
    aliases.join(" / "),
    a.musicSize > 0 ? tr("{count} songs", { count: a.musicSize }) : "",
    a.albumSize > 0 ? tr("{count} albums", { count: a.albumSize }) : "",
  ].filter(Boolean).join(" · ");
  return {
    title: a.name, subtitle: tr("Artist"), imageUrl: toArtist(a).imageUrl,
    description: [meta, (a.briefDesc || "").trim().replace(/\r?\n/g, " ")].filter(Boolean).join("\n"),
  };
}
export async function artist({ id: value }) {
  const aid = id(value), generation = epoch;
  const [result, albums] = await Promise.all([artistDetail(aid), allArtistAlbums(aid)]);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const songs = result.hotSongs || [];
  let tracks;
  try { tracks = await hydrate(songs); }
  catch { tracks = songs.map(song => toTrack(song)); }
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const a = toArtist(result.artist);
  save("lastArtists", [a, ...load("lastArtists", []).filter(x => x.id !== a.id)].slice(0, 20));
  return { artist: a, tracks, albums };
}
export async function followArtist({ id: aid, on }) {
  requireAccount();
  // NetEase rejects some eapi follows with -460; the original client uses web.
  await web(`/api/artist/${on ? "sub" : "unsub"}`, { artistId: id(aid), artistIds: `[${id(aid)}]` }, "POST");
}
export async function saveAlbum({ id: aid, on }) {
  requireAccount();
  await eapi(`/api/album/${on ? "sub" : "unsub"}`, { id: id(aid) });
}
export async function savedAlbums({ offset = 0 } = {}) {
  requireAccount();
  const result = await web("/api/album/sublist", { limit: PAGE, offset: offsetOf(offset), total: true });
  return { albums: (result.data || []).map(a => ({ ...toAlbum(a), saved: true })), more: !!result.hasMore };
}
async function followedArtists(offset = 0) {
  requireAccount();
  const generation = epoch;
  const result = await web("/api/artist/sublist", { limit: PAGE, offset: offsetOf(offset), total: true });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const artists = (result.data || []).map(artist => ({ ...toArtist(artist), following: true }));
  return { artists, more: !!result.hasMore && artists.length > 0 };
}
async function allFollowedArtists() {
  const all = [];
  for (let offset = 0; ; offset += PAGE) {
    const result = await followedArtists(offset);
    all.push(...result.artists);
    if (!result.more) return all;
  }
}
export async function shows() {
  requireAccount();
  const generation = epoch;
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const result = await web("/api/djradio/get/subed", { limit: 100, offset, total: true });
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const page = result.djRadios || [];
    all.push(...page.map(s => ({ ...toShow(s), subscribed: true })));
    if (!result.hasMore || !page.length) return all;
  }
}
export async function show({ id: sid, offset = 0 }) {
  const [detail, programs] = await Promise.all([
    web("/api/djradio/v2/get", { id: id(sid) }),
    web("/api/dj/program/byradio", { radioId: id(sid), limit: PAGE, offset: offsetOf(offset), asc: false }),
  ]);
  if (!detail.data) throw fail("unavailable", tr("Podcast not found"));
  return { show: toShow(detail.data), episodes: (programs.programs || []).map(p => toEpisode(p, detail.data)), more: !!programs.more };
}
export async function subscribeShow({ id: sid, on }) {
  requireAccount();
  await eapi(`/api/djradio/${on ? "sub" : "unsub"}`, { id: id(sid) });
}
function coverURLs(urls) {
  return [...new Set(urls.map(https).filter(url => url.startsWith("https://")))].slice(0, 4);
}
export function stations() {
  const covers = load("stationCovers", {});
  return [
    { id: "fm:DEFAULT", name: tr("Recommended"), description: tr("Music picked for your taste, one song after another") },
    { id: "fm:FAMILIAR", name: tr("Familiar"), description: tr("Music picked for your taste, one song after another") },
    { id: "fm:EXPLORE", name: tr("Explore"), description: tr("Music picked for your taste, one song after another") },
    { id: "heart", name: tr("Heart Mode"),
      description: tr("Start with the music you love\nDiscover similar music based on the songs you've liked, mixed with your favourites") },
  ].map(station => ({
    ...station,
    imageUrl: covers[station.id] || "",
  }));
}
function rememberStationCover(station, tracks, generation) {
  if (generation !== epoch) return;
  const [cover] = coverURLs(tracks.filter(track => !track.unavailable).map(track => track.album?.imageUrl));
  if (cover) save("stationCovers", { ...load("stationCovers", {}), [station]: cover });
}
export async function stationNext({ id: station }) {
  const me = requireAccount();
  const generation = epoch;
  if (station === "heart") {
    let state = heart;
    let seed;
    if (!state) {
      const p = (await userPlaylists(me.id)).find(p => p.specialType === 5);
      if (!p) throw fail("unavailable", tr("Your liked-songs playlist was not found"));
      const ids = await likedIDs();
      if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
      if (!ids.length) throw fail("unavailable", tr("Like a few songs before starting Heart mode"));
      const playable = (await details(ids)).filter(t => !t.unavailable);
      if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
      if (!playable.length) throw fail("unavailable", tr("Heart Mode can't start right now. Try again later."));
      seed = playable[Math.floor(Math.random() * playable.length)];
      state = { playlist: String(p.id), start: seed.id, current: seed.id };
    }
    const result = await eapi("/api/playmode/intelligence/list", {
      songId: state.current, startMusicId: state.start, playlistId: state.playlist, type: "fromPlayOne", count: 1,
    });
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const more = (await hydrate((result.data || []).map(x => x.songInfo).filter(Boolean)))
      .filter(t => !t.unavailable && t.id !== seed?.id);
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const tracks = seed ? [seed, ...more] : more;
    heart = { ...state, current: tracks.at(-1)?.id || state.current };
    rememberStationCover(station, tracks, generation);
    return tracks;
  }
  const mode = station.replace(/^fm:/, "");
  if (!["DEFAULT", "FAMILIAR", "EXPLORE"].includes(mode)) throw fail("unavailable", tr("Unknown station"));
  const result = await eapi("/api/v1/radio/get", { mode, limit: 3 });
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  // The host retries empty/unplayable batches up to three times.
  const tracks = (await details((result.data || []).map(t => t.id))).filter(t => !t.unavailable);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  rememberStationCover(station, tracks, generation);
  return tracks;
}
function toComment(c) {
  const quote = (c.beReplied || []).map(r => `${r.user?.nickname || ""}: ${r.content || ""}`).join("\n");
  return {
    id: String(c.commentId), author: c.user?.nickname || "", avatarUrl: https(c.user?.avatarUrl),
    text: c.content + (quote ? "\n\n" + tr("Replying to: {quote}", { quote }) : ""), time: c.time || 0,
    likes: c.likedCount || 0, liked: !!c.liked,
  };
}
export async function comments({ trackId, offset = 0 }) {
  const result = await web(`/api/v1/resource/comments/${trackIdentity(trackId).thread}`,
    { limit: PAGE, offset: offsetOf(offset) });
  // Only latest comments count toward the host's numeric pagination offset.
  // Hot comments have their own browse page so they cannot skip later comments.
  return { comments: (result.comments || []).map(toComment), total: result.total || 0, more: !!result.more };
}
export async function likeComment({ trackId, commentId, on }) {
  requireAccount();
  await eapi(`/api/v1/comment/${on ? "like" : "unlike"}`, { threadId: trackIdentity(trackId).thread, commentId: id(commentId) });
}
export async function postComment({ trackId, text }) {
  requireAccount();
  if (!text.trim()) throw new Error(tr("Comment cannot be empty"));
  const result = await eapi("/api/resource/comments/add", { threadId: trackIdentity(trackId).thread, content: text.trim() });
  if (!result.comment) throw new Error(tr("NetEase did not return the posted comment. Refresh to check before posting again."));
  return toComment(result.comment);
}

function timedTranslations(text) {
  const out = [];
  let offset = 0;
  const offsetMatch = /\[offset:([+-]?\d+)\]/i.exec(text);
  if (offsetMatch) offset = Number(offsetMatch[1]);
  for (const raw of text.split(/\r?\n/)) {
    const tags = [...raw.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
    const words = raw.replace(/\[[^\]]*\]/g, "").trim();
    if (!words) continue;
    for (const tag of tags) out.push({ start: Math.round(Number(tag[1]) * 60000 + Number(tag[2]) * 1000) + offset, text: words });
  }
  return out;
}
function parseYRC(text, translation) {
  const lines = [];
  for (const raw of text.split(/\r?\n/)) {
    const timing = /^\[(\d+),(\d+)\]/.exec(raw);
    if (!timing) continue;
    const words = [];
    for (const m of raw.slice(timing[0].length).matchAll(/\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)/g)) {
      if (!m[3].trim()) {
        if (words.length) words[words.length - 1].text += m[3];
      } else words.push({ start: Number(m[1]), end: Number(m[1]) + Number(m[2]), text: m[3] });
    }
    const lyric = words.map(w => w.text).join("").trim();
    if (lyric) lines.push({ start: Number(timing[1]), end: Number(timing[1]) + Number(timing[2]), text: lyric, words });
  }
  lines.sort((a, b) => a.start - b.start);
  for (const t of timedTranslations(translation)) {
    let best = null, gap = 1500;
    for (const line of lines) {
      const distance = Math.abs(line.start - t.start);
      if (distance < gap) { best = line; gap = distance; }
    }
    if (best && !best.translation) best.translation = t.text;
  }
  return lines;
}
export async function lyrics({ id: value }) {
  const identity = trackIdentity(value);
  if (identity.program) return { lrc: "" };
  const base = await web("/api/song/lyric", { id: identity.audio, lv: 1, tv: -1 });
  let words = {};
  try {
    words = await eapi("/api/song/lyric/v1", { id: identity.audio, cp: false, tv: 0, lv: 0, rv: 0, kv: 0, yv: 0, ytv: 0, yrv: 0 });
  } catch { /* Plain LRC remains usable when word timings are unavailable. */ }
  const lrc = base.lrc?.lyric || "";
  const translation = words.ytlrc?.lyric || base.tlyric?.lyric || "";
  const lines = parseYRC(words.yrc?.lyric || "", translation);
  return lines.length ? { lrc, translation, lines } : { lrc, translation };
}
export async function stream({ id: value, quality = "standard" }) {
  const identity = trackIdentity(value);
  const level = host.setting("quality") || (quality === "high" ? "exhigh" : quality);
  if (!LEVELS.includes(level)) throw fail("setup", tr("Invalid quality setting"));
  const result = await eapi("/api/song/enhance/player/url/v1", {
    ids: `[${identity.audio}]`, level, encodeType: ["lossless", "hires"].includes(level) ? "flac" : "mp3",
  });
  const item = (result.data || []).find(t => String(t.id) === identity.audio) || result.data?.[0];
  if (!item?.url || item.freeTrialInfo != null) throw fail("unavailable", tr("Full playback is unavailable. A subscription or purchase may be required."));
  const type = (item.type || (["lossless", "hires"].includes(item.level) ? "flac" : "mp3")).toLowerCase();
  if (!["mp3", "flac", "ogg", "wav", "m4a", "aac"].includes(type)) throw fail("unsupported", tr("Unsupported audio format: {type}", { type }));
  const url = new URL(https(item.url));
  if (url.protocol !== "https:") throw fail("unavailable", tr("The audio URL is not a secure network address"));
  return {
    url: url.href, type, quality: item.level || level, bitRate: item.br || 0,
    size: item.size || 0, gain: Number(item.gain) || 0, peak: Number(item.peak) || 0,
    headers: { "User-Agent": UA, Referer: `${SITE}/` },
  };
}

export async function browse({ page }) {
  const url = new URL(page, "https://plugin.invalid/");
  const name = url.pathname.slice(1);
  const q = url.searchParams;
  const offset = offsetOf(Number(q.get("offset") || 0));
  if (name === "daily") {
    const tracks = await daily();
    return {
      title: tr("Daily Song Recommendations"),
      subtitle: tr("Daily Recommendations"),
      description: tr("{count} songs · {description}", { count: tracks.length, description: dailyBlurb() }),
      sections: [{ tracks }],
    };
  }
  if (name === "recommended") return { title: tr("Recommended Playlists"), sections: [{ playlists: (await recommendedPlaylists()).map(toPlaylist) }] };
  if (name === "picked-for-you") {
    requireAccount();
    return { title: tr("Picked for You Today"), sections: [{ playlists: (await pickedForYou()).map(toPlaylist) }] };
  }
  if (name === "charts") {
    const lists = await charts();
    return { title: tr("Charts"), subtitle: tr("Updated Daily"), sections: [
      { title: tr("Official Charts"), playlists: lists.slice(0, 4) },
      ...(lists.length > 4 ? [{ title: tr("All Charts"), playlists: lists.slice(4) }] : []),
    ] };
  }
  if (name === "recent") return { title: tr("Recently Played"), sections: [{ tracks: await recent() }] };
  if (name === "saved-albums") {
    const result = await savedAlbums({ offset });
    return { title: tr("Saved Albums"), sections: [
      { albums: result.albums },
      ...nextSection(name, { offset: offset + PAGE }, result.more && result.albums.length),
    ] };
  }
  if (name === "following") {
    const result = await followedArtists(offset);
    return { title: tr("Followed Artists"), sections: [
      { artists: result.artists, empty: tr("No followed artists yet") },
      { title: tr("New Releases"), links: result.artists.map(artistLink) },
      ...nextSection(name, { offset: offset + PAGE }, result.more),
    ] };
  }
  if (name === "subscriptions") return { title: tr("Subscribed Podcasts"), sections: [
    { shows: await shows(), empty: tr("No subscribed podcasts yet") },
  ] };
  if (name === "podcasts") {
    const generation = epoch;
    const [subscriptions, picks, hot] = await Promise.allSettled([
      !offset && accountNow() ? shows() : Promise.resolve([]),
      !offset ? web("/api/djradio/recommend/v1") : Promise.resolve({}),
      web("/api/djradio/hot/v1", { limit: PAGE, offset }),
    ]);
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    const sections = [];
    if (subscriptions.status === "fulfilled" && subscriptions.value.length) {
      sections.push({ title: tr("My Subscriptions"), shows: subscriptions.value });
    } else if (subscriptions.status === "rejected") {
      sections.push(retrySection(tr("My Subscriptions"), "subscriptions", subscriptions.reason));
    }
    if (picks.status === "fulfilled" && picks.value.djRadios?.length) {
      sections.push({ title: tr("Recommended Podcasts"), shows: picks.value.djRadios.map(toShow) });
    } else if (picks.status === "rejected") {
      sections.push(retrySection(tr("Recommended Podcasts"), "podcasts", picks.reason));
    }
    if (hot.status === "fulfilled") {
      if (hot.value.djRadios?.length) sections.push({ title: tr("Popular Podcasts"), shows: hot.value.djRadios.map(toShow) });
      sections.push(...nextSection(name, { offset: offset + PAGE }, hot.value.hasMore && hot.value.djRadios?.length));
    } else sections.push(retrySection(tr("Popular Podcasts"), route("podcasts", { offset }), hot.reason));
    return { title: tr("Podcasts"), sections };
  }
  if (name === "explore") {
    return { title: tr("Explore"), subtitle: tr("More from NetEase Cloud Music"), sections: [
      { title: tr("Recently Viewed or Searched Artists"), links: load("lastArtists", []).map(artistLink) },
      { title: tr("Top Comments"), links: load("recent", []).slice(0, 20).map(t => ({
        title: t.name, subtitle: tr("Read Top Comments"), imageUrl: t.album?.imageUrl, page: route("hot-comments", { id: t.id }),
      })) },
    ] };
  }
  if (name === "profile") return profilePage(requireAccount().id);
  if (name === "profile-playlists") {
    return profilePlaylistsPage(q.has("id") ? id(q.get("id")) : requireAccount().id, q.get("kind"), offset);
  }
  if (name === "user") return profilePage(id(q.get("id")));
  if (name === "artist-extra") {
    const aid = id(q.get("id")), generation = epoch;
    const [person, latest, records] = await Promise.allSettled([
      artistDetail(aid), artistLatest(aid, offset), artistAlbums(aid),
    ]);
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    if (person.status === "rejected") throw person.reason;
    return { ...artistBrowseHeader(person.value.artist), sections: [
      latest.status === "fulfilled"
        ? { title: tr("Latest Songs"), tracks: latest.value.tracks, empty: tr("No songs"),
          morePage: latest.value.more ? route("artist-latest", { id: aid, offset: offset + ARTIST_PAGE }) : "" }
        : retrySection(tr("Latest Songs"), route("artist-latest", { id: aid, offset }), latest.reason),
      records.status === "fulfilled"
        ? { title: tr("Albums"), albums: records.value.albums, empty: tr("No albums"),
          morePage: records.value.more ? route("artist-albums", { id: aid, offset: PAGE }) : "" }
        : retrySection(tr("Albums"), route("artist-albums", { id: aid }), records.reason),
    ] };
  }
  if (name === "artist-latest" || name === "artist-albums") {
    const aid = id(q.get("id")), generation = epoch;
    const latest = name === "artist-latest";
    const [person, result] = await Promise.all([
      artistDetail(aid), latest ? artistLatest(aid, offset) : artistAlbums(aid, offset),
    ]);
    if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
    return { ...artistBrowseHeader(person.artist), sections: [
      latest ? { title: tr("Latest Songs"), tracks: result.tracks, empty: tr("No songs") }
        : { title: tr("Albums"), albums: result.albums, empty: tr("No albums") },
      ...nextSection(name, { id: aid, offset: offset + (latest ? ARTIST_PAGE : PAGE) }, result.more),
    ] };
  }
  if (name === "hot-comments") {
    const result = await web(`/api/v1/resource/comments/${trackIdentity(q.get("id")).thread}`, { limit: 1, offset: 0 });
    return { title: tr("Top Comments"), subtitle: tr("Like and post comments from the Now Playing comments panel"), sections: (result.hotComments || []).map(c => ({
      title: tr("{name} · {likes} likes", { name: c.user?.nickname || tr("Listener"), likes: c.likedCount || 0 }) ,
      links: [{ title: c.content, subtitle: date(c.time), imageUrl: https(c.user?.avatarUrl), page: route("user", { id: c.user.userId }) }],
    })) };
  }
  throw fail("unavailable", tr("Page not found"));
}
let retryID = 0;
function retrySection(title, page, error) {
  // Browse pages are cached by their full route; retry must request a fresh one.
  const target = `${page}${page.includes("?") ? "&" : "?"}retry=${++retryID}`;
  return { title, links: [{ title: tr("Retry"), subtitle: error.message, page: target }] };
}
function profileCount(value = 0) {
  if (host.language() !== "zh-CN") return String(value);
  if (value >= 1e8) return `${(value / 1e8).toFixed(1)}\u4ebf`;
  if (value >= 1e4) return `${(value / 1e4).toFixed(value < 1e6 ? 1 : 0)}\u4e07`;
  return String(value);
}
function profilePlaylistGroups(lists, uid) {
  const own = accountNow()?.id === uid;
  const visible = own ? lists : lists.filter(playlist => playlist.privacy !== 10);
  return {
    created: visible.filter(playlist => String(playlist.creator?.userId) === uid && playlist.privacy !== 10).map(toPlaylist),
    saved: visible.filter(playlist => String(playlist.creator?.userId) !== uid).map(toPlaylist),
  };
}
async function profilePlaylistsPage(uid, kind, offset) {
  if (kind !== "created" && kind !== "saved") throw fail("unavailable", tr("Page not found"));
  const generation = epoch;
  const groups = profilePlaylistGroups(await userPlaylists(uid), uid);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  const playlists = groups[kind];
  const own = accountNow()?.id === uid;
  return {
    title: tr(kind === "created" ? "Created Playlists" : "Saved Playlists"),
    subtitle: playlists.length ? tr("{count} playlists", { count: playlists.length })
      : tr(kind === "created" ? "No public playlists" : "No saved playlists yet"),
    sections: [
      { playlists: playlists.slice(offset, offset + PAGE),
        empty: tr(kind === "created" ? "No public playlists" : "No saved playlists yet") },
      ...nextSection("profile-playlists", { ...(own ? {} : { id: uid }), kind, offset: offset + PAGE },
        offset + PAGE < playlists.length),
    ],
  };
}
async function profilePage(uid) {
  const generation = epoch;
  const own = accountNow()?.id === uid;
  const [person, lists, subscriptions, artists] = await Promise.allSettled([
    web(`/api/v1/user/detail/${uid}`), userPlaylists(uid),
    own ? shows() : Promise.resolve([]),
    own ? allFollowedArtists() : Promise.resolve([]),
  ]);
  if (generation !== epoch) throw new Error(tr("The account changed. Reload this page."));
  if (person.status === "rejected") throw person.reason;
  const p = person.value.profile;
  if (!p) throw fail("unavailable", tr("User not found"));
  const sections = [];
  const retryPage = own ? "profile" : route("user", { id: uid });
  if (lists.status === "fulfilled") {
    const groups = profilePlaylistGroups(lists.value, uid);
    sections.push({ title: tr("Created Playlists"), playlists: groups.created, empty: tr("No public playlists") });
  } else {
    sections.push(retrySection(tr("Created Playlists"), retryPage, lists.reason));
  }
  if (own) {
    sections.push(subscriptions.status === "fulfilled"
      ? { title: tr("Subscribed Podcasts"), shows: subscriptions.value, empty: tr("No subscribed podcasts yet") }
      : retrySection(tr("Subscribed Podcasts"), retryPage, subscriptions.reason));
    sections.push(artists.status === "fulfilled"
      ? { title: tr("Followed Artists"), artists: artists.value, empty: tr("No followed artists yet") }
      : retrySection(tr("Followed Artists"), retryPage, artists.reason));
  }
  // The host places subtitle above the name and description lines below it.
  const stats = tr("Following {following} · Followers {followers}", {
    following: profileCount(p.follows || 0), followers: profileCount(p.followeds || 0),
  });
  return {
    title: p.nickname, imageUrl: https(p.avatarUrl), subtitle: tr("User"),
    description: [stats, p.signature].filter(Boolean).join("\n"), sections,
  };
}
function artistLink(a) {
  return { title: a.name, subtitle: tr("Latest Songs / All Albums"), imageUrl: a.imageUrl, page: route("artist-extra", { id: a.id }) };
}
