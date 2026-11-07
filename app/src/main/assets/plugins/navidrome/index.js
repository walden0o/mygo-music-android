// Navidrome exposes the Subsonic API. All IDs below are server IDs.
const PAGE_SIZE = 50;
const RADIO_PREFIX = "navidrome:radio:";
const radioId = id => RADIO_PREFIX + encodeURIComponent(String(id));
const radioStationId = id => String(id).startsWith(RADIO_PREFIX) ? decodeURIComponent(String(id).slice(RADIO_PREFIX.length)) : null;
const tr = (chinese, en) => typeof host.language === "function" && host.language() === "en" ? en : chinese;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function config() {
  const server = (host.setting("server") || "").trim().replace(/\/+$/, "");
  const username = (host.setting("username") || "").trim();
  const password = host.setting("password") || "";
  if (!server || !username || !password) throw fail("setup", tr("请在插件设置中填写服务器地址、用户名和密码", "Enter the server URL, username and password in plugin settings"));
  let url;
  try { url = new URL(server); } catch { throw fail("setup", tr("服务器地址无效，请填写完整的 HTTP 或 HTTPS 地址", "Enter a valid HTTP or HTTPS server URL")); }
  if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw fail("setup", tr("服务器地址须为不含凭据、查询参数或片段的 HTTP 或 HTTPS 地址", "Server URL must be HTTP or HTTPS without credentials, query or fragment"));
  }
  return { server, username, password };
}

function endpoint(method, params = {}) {
  const { server, username, password } = config();
  const salt = Math.random().toString(36).slice(2) + Date.now().toString(36);
  const url = new URL(`${server}/rest/${method}`);
  const entries = { u: username, t: host.crypto.md5(password + salt), s: salt, v: "1.16.1", c: "mygo-music-navidrome", f: "json", ...params };
  for (const [key, value] of Object.entries(entries)) {
    if (value === undefined || value === null) continue;
    for (const item of Array.isArray(value) ? value : [value]) url.searchParams.append(key, String(item));
  }
  return url.toString();
}

async function request(method, params) {
  const response = await fetch(endpoint(method, params));
  if (!response.ok) throw new Error(tr(`Navidrome 请求失败（HTTP ${response.status}）`, `Navidrome request failed (HTTP ${response.status})`));
  const data = (await response.json())["subsonic-response"];
  if (!data || data.status !== "ok") {
    const error = data?.error;
    throw fail(error?.code === 40 || error?.code === 41 ? "setup" : "unavailable", error?.message || tr("Navidrome 返回了无效响应", "Invalid Navidrome response"));
  }
  return data;
}

const list = value => Array.isArray(value) ? value : value ? [value] : [];
const cover = id => id ? endpoint("getCoverArt", { id, size: 800 }) : "";
function toArtist(a) {
  return { id: String(a.id || ""), name: a.name || "", imageUrl: cover(a.coverArt), following: !!a.starred };
}
function toAlbum(a) {
  return { id: String(a.id || ""), name: a.name || a.title || "", imageUrl: cover(a.coverArt), artists: a.artist ? [{ id: String(a.artistId || ""), name: a.artist }] : [], released: a.year ? String(a.year) : "", saved: !!a.starred };
}
function toTrack(s) {
  return { id: String(s.id), name: s.title || "", artists: s.artist ? [{ id: String(s.artistId || ""), name: s.artist }] : [], album: toAlbum({ id: s.albumId, name: s.album, coverArt: s.coverArt, artist: s.artist, artistId: s.artistId, year: s.year }), duration: (Number(s.duration) || 0) * 1000 };
}
function toPlaylist(p) {
  const username = config().username;
  return { id: String(p.id), name: p.name || "", imageUrl: cover(p.coverArt), description: p.comment || "", owner: p.owner || "", trackCount: p.songCount || 0, mine: p.owner === username, saved: !!p.owner && p.owner !== username };
}

export async function signIn() {
  await request("ping");
  const { server, username } = config();
  const account = { id: username, name: username, server };
  host.storage.set("account", account);
  return account;
}
export function account() {
  const saved = host.storage.get("account");
  if (!saved) return null;
  try {
    const { server, username } = config();
    return saved.server === server && saved.id === username ? { id: saved.id, name: saved.name } : null;
  } catch { return null; }
}
export function signOut() {
  host.storage.remove("account");
  host.storage.remove("radioRecent");
  host.storage.remove("radioPending");
  nativeSession = null;
}
export function setSetting({ key }) {
  if (["server", "username", "password"].includes(key)) signOut();
}

export async function home() {
  const [random, newest, recent, frequent, favorites, myPlaylists] = await Promise.all([
    request("getRandomSongs", { size: 24 }),
    request("getAlbumList2", { type: "newest", size: 24 }),
    request("getAlbumList2", { type: "recent", size: 24 }),
    request("getAlbumList2", { type: "frequent", size: 24 }),
    request("getAlbumList2", { type: "starred", size: 24 }),
    playlists(),
  ]);
  const recommendations = await tracksFromAlbums([...list(favorites.albumList2?.album), ...list(frequent.albumList2?.album)]);
  return { title: tr("发现音乐", "Discover"), sections: [
    ...(recommendations.length ? [{ title: tr("为你推荐", "For you"), tracks: recommendations }] : []),
    { title: tr("随机歌曲", "Random songs"), tracks: list(random.randomSongs?.song).map(toTrack) },
    { title: tr("最近添加", "Recently added"), albums: list(newest.albumList2?.album).map(toAlbum) },
    { title: tr("最近播放", "Recently played"), albums: list(recent.albumList2?.album).map(toAlbum) },
    { title: tr("最多播放", "Most played"), albums: list(frequent.albumList2?.album).map(toAlbum) },
    { title: tr("我喜欢的专辑", "Favorite albums"), albums: list(favorites.albumList2?.album).map(toAlbum) },
    { title: tr("歌单", "Playlists"), playlists: myPlaylists.slice(0, 24) },
  ] };
}

let nativeSession = null;

// The native API is the Web UI's internal API and can differ between releases.
// A missing route falls back to the public Subsonic API; authentication and
// other server errors are reported instead of being silently hidden.
async function nativeArtists() {
  const { server, username, password } = config();
  const sessionKey = `${server}\n${username}\n${password}`;
  async function login() {
    const response = await fetch(`${server}/auth/login`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    if (response.status === 404 || response.status === 405) return null;
    if (!response.ok) throw fail(response.status === 401 || response.status === 403 ? "setup" : "unavailable", tr(`Navidrome 登录失败（HTTP ${response.status}）`, `Navidrome sign-in failed (HTTP ${response.status})`));
    const data = await response.json();
    if (!data?.token) throw fail("unavailable", tr("Navidrome 登录响应缺少令牌", "Navidrome sign-in response has no token"));
    nativeSession = { key: sessionKey, token: data.token };
    return nativeSession;
  }
  let session = nativeSession?.key === sessionKey ? nativeSession : await login();
  if (!session) return null;
  let refreshed = false;
  const artists = [];
  for (let offset = 0; ; offset += 500) {
    const url = new URL(`${server}/api/artist`);
    url.searchParams.set("_start", String(offset));
    url.searchParams.set("_end", String(offset + 500));
    url.searchParams.set("_sort", "name");
    url.searchParams.set("_order", "ASC");
    let response = await fetch(url.toString(), { headers: { "X-ND-Authorization": `Bearer ${session.token}` } });
    if (response.status === 401 && !refreshed) {
      session = await login();
      if (!session) return null;
      refreshed = true;
      response = await fetch(url.toString(), { headers: { "X-ND-Authorization": `Bearer ${session.token}` } });
    }
    if (response.status === 404 || response.status === 405) return null;
    if (!response.ok) throw new Error(tr(`Navidrome 艺人列表请求失败（HTTP ${response.status}）`, `Navidrome artist request failed (HTTP ${response.status})`));
    const page = await response.json();
    if (!Array.isArray(page)) throw fail("unavailable", tr("Navidrome 艺人列表响应无效", "Invalid Navidrome artist response"));
    artists.push(...page.map(a => toArtist({ ...a, coverArt: a.imageAbsent ? "" : `ar-${a.id}` })));
    if (page.length < 500) return artists;
  }
}

export async function browse({ page }) {
  if (page === "genres") {
    const genres = list((await request("getGenres")).genres?.genre);
    return { title: tr("流派", "Genres"), sections: [{ links: genres.filter(g => g.value).map(g => ({ title: g.value, page: `genre:${encodeURIComponent(g.value)}:0` })), empty: tr("没有流派", "No genres") }] };
  }
  if (page.startsWith("genre:")) {
    const match = /^genre:([^:]+):(\d+)$/.exec(page);
    if (!match) throw fail("unavailable", tr("页面不存在", "Page not found"));
    let genre;
    try { genre = decodeURIComponent(match[1]); } catch { throw fail("unavailable", tr("页面不存在", "Page not found")); }
    const offset = Number(match[2]);
    if (!Number.isSafeInteger(offset)) throw fail("unavailable", tr("页面不存在", "Page not found"));
    const [songs, albums] = await Promise.all([
      request("getSongsByGenre", { genre, count: PAGE_SIZE + 1, offset }),
      request("getAlbumList2", { type: "byGenre", genre, size: PAGE_SIZE + 1, offset }),
    ]);
    const tracks = list(songs.songsByGenre?.song);
    const records = list(albums.albumList2?.album);
    const links = [];
    if (offset > 0) links.push({ title: tr("上一页", "Previous page"), page: `genre:${match[1]}:${Math.max(0, offset - PAGE_SIZE)}` });
    if (tracks.length > PAGE_SIZE || records.length > PAGE_SIZE) links.push({ title: tr("下一页", "Next page"), page: `genre:${match[1]}:${offset + PAGE_SIZE}` });
    return { title: genre, sections: [
      { title: tr("专辑", "Albums"), albums: records.slice(0, PAGE_SIZE).map(toAlbum) },
      { title: tr("歌曲", "Songs"), tracks: tracks.slice(0, PAGE_SIZE).map(toTrack) },
      ...(links.length ? [{ links }] : []),
    ] };
  }
  if (page === "liked-artists") {
    const artists = list((await starred()).artist).map(toArtist);
    return { title: tr("我喜欢的艺人", "Favorite artists"), sections: [{ artists, empty: tr("还没有喜欢的艺人", "No favorite artists yet") }] };
  }
  if (page === "artists") {
    const native = await nativeArtists();
    if (native) return { title: tr("艺人", "Artists"), sections: [{ artists: native, empty: tr("音乐库中没有艺人", "No artists in the library") }] };
    const data = await request("getArtists");
    const artists = list(data.artists?.index).flatMap(group => list(group.artist)).map(toArtist);
    return { title: tr("艺人", "Artists"), sections: [{ artists, empty: tr("音乐库中没有艺人", "No artists in the library") }] };
  }
  if (page === "albums" || /^albums:\d+$/.test(page)) {
    const offset = page === "albums" ? 0 : Number(page.slice(7));
    if (!Number.isSafeInteger(offset) || offset < 0) throw fail("unavailable", tr("无效的专辑页码", "Invalid album page"));
    const albums = list((await request("getAlbumList2", { type: "alphabeticalByName", size: PAGE_SIZE + 1, offset })).albumList2?.album);
    const links = [];
    if (offset > 0) links.push({ title: tr("上一页", "Previous page"), page: offset <= PAGE_SIZE ? "albums" : `albums:${offset - PAGE_SIZE}` });
    if (albums.length > PAGE_SIZE) links.push({ title: tr("下一页", "Next page"), page: `albums:${offset + PAGE_SIZE}` });
    return { title: tr("专辑", "Albums"), subtitle: tr(`第 ${Math.floor(offset / PAGE_SIZE) + 1} 页`, `Page ${Math.floor(offset / PAGE_SIZE) + 1}`), sections: [
      { albums: albums.slice(0, PAGE_SIZE).map(toAlbum), empty: tr("没有更多专辑", "No more albums") },
      ...(links.length ? [{ links }] : []),
    ] };
  }
  throw fail("unavailable", tr("页面不存在", "Page not found"));
}

export async function search({ query, kind = "tracks", offset = 0 }) {
  if (kind === "playlists") {
    const matches = (await playlists()).filter(p => p.name.toLowerCase().includes(query.toLowerCase()));
    return { playlists: matches.slice(offset, offset + PAGE_SIZE), more: offset + PAGE_SIZE < matches.length };
  }
  const types = { tracks: ["song", toTrack], albums: ["album", toAlbum], artists: ["artist", toArtist] };
  if (!types[kind]) return { [kind]: [], more: false };
  const [type, convert] = types[kind];
  const params = { query, songCount: 0, albumCount: 0, artistCount: 0 };
  params[`${type}Count`] = PAGE_SIZE + 1;
  params[`${type}Offset`] = offset;
  const result = list((await request("search3", params)).searchResult3?.[type]);
  return { [kind]: result.slice(0, PAGE_SIZE).map(convert), more: result.length > PAGE_SIZE };
}

export async function suggest({ query }) {
  if (!String(query || "").trim()) return [];
  const result = (await request("search3", { query, songCount: 10, albumCount: 5, artistCount: 5 })).searchResult3 || {};
  return [...new Set([...list(result.song).map(s => s.title), ...list(result.album).map(a => a.name), ...list(result.artist).map(a => a.name)].filter(Boolean))].slice(0, 10);
}

async function tracksFromAlbums(albums) {
  const seenAlbums = new Set();
  const selected = albums.filter(a => a.id && !seenAlbums.has(String(a.id)) && seenAlbums.add(String(a.id))).slice(0, 4);
  const pages = await Promise.all(selected.map(a => request("getAlbum", { id: a.id })));
  const seen = new Set();
  const tracks = [];
  for (const page of pages) for (const song of list(page.album?.song)) {
    if (!seen.has(String(song.id)) && tracks.length < PAGE_SIZE) { seen.add(String(song.id)); tracks.push(toTrack(song)); }
  }
  return tracks;
}
export async function recent() {
  const data = await request("getAlbumList2", { type: "recent", size: 3 });
  return tracksFromAlbums(list(data.albumList2?.album));
}

export async function album({ id }) {
  const a = (await request("getAlbum", { id })).album;
  if (!a) throw fail("unavailable", tr("专辑不存在", "Album not found"));
  return { album: toAlbum(a), tracks: list(a.song).map(toTrack) };
}
export async function artist({ id }) {
  const a = (await request("getArtist", { id })).artist;
  if (!a) throw fail("unavailable", tr("歌手不存在", "Artist not found"));
  const albums = list(a.album);
  const tracks = await tracksFromAlbums(albums);
  return { artist: toArtist(a), albums: albums.map(toAlbum), tracks };
}
export async function playlists() {
  return list((await request("getPlaylists")).playlists?.playlist).map(toPlaylist);
}
export async function playlist({ id }) {
  const p = (await request("getPlaylist", { id })).playlist;
  if (!p) throw fail("unavailable", "歌单不存在或无权访问");
  return { ...toPlaylist(p), tracks: list(p.entry).map(toTrack) };
}
export async function createPlaylist({ name }) {
  const p = (await request("createPlaylist", { name })).playlist;
  if (!p) throw fail("unavailable", tr("服务器未返回新建歌单", "Server did not return the new playlist"));
  return toPlaylist(p);
}
export async function renamePlaylist({ id, name }) { await request("updatePlaylist", { playlistId: id, name }); }
export async function deletePlaylist({ id }) { await request("deletePlaylist", { id }); }
export async function addToPlaylist({ id, trackIds }) {
  if (trackIds.length) await request("updatePlaylist", { playlistId: id, songIdToAdd: trackIds });
}
export async function removeFromPlaylist({ id, trackIds }) {
  const p = (await request("getPlaylist", { id })).playlist;
  if (!p) throw fail("unavailable", "歌单不存在或无权访问");
  const ids = new Set(trackIds.map(String));
  const indices = list(p.entry).map((song, i) => ids.has(String(song.id)) ? i : -1).filter(i => i >= 0).reverse();
  if (indices.length) await request("updatePlaylist", { playlistId: id, songIndexToRemove: indices });
}

export async function movePlaylistTrack({ id, from, to }) {
  const p = (await request("getPlaylist", { id })).playlist;
  if (!p) throw fail("unavailable", tr("歌单不存在或无权访问", "Playlist not found or inaccessible"));
  const entries = list(p.entry);
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < 0 || from >= entries.length || to >= entries.length) throw fail("unavailable", tr("歌曲位置无效", "Invalid track position"));
  if (from === to) return;
  const ids = entries.map(song => String(song.id));
  ids.splice(to, 0, ids.splice(from, 1)[0]);
  await request("createPlaylist", { playlistId: id, songId: ids });
}

async function starred() { return (await request("getStarred2")).starred2 || {}; }
export async function liked({ offset = 0 } = {}) {
  const songs = list((await starred()).song);
  return { tracks: songs.slice(offset, offset + PAGE_SIZE).map(toTrack), more: offset + PAGE_SIZE < songs.length };
}
export async function isLiked({ ids }) {
  const set = new Set(list((await starred()).song).map(s => String(s.id)));
  return Object.fromEntries(ids.map(id => [id, set.has(String(id))]));
}
export async function like({ id, on }) { await request(on ? "star" : "unstar", { id }); }
export async function saveAlbum({ id, on }) { await request(on ? "star" : "unstar", { albumId: id }); }
export async function followArtist({ id, on }) { await request(on ? "star" : "unstar", { artistId: id }); }
export async function savedAlbums({ offset = 0 } = {}) {
  const albums = list((await starred()).album);
  return { albums: albums.slice(offset, offset + PAGE_SIZE).map(toAlbum), more: offset + PAGE_SIZE < albums.length };
}
const ENDLESS = "navidrome:endless";
async function internetStations() {
  return list((await request("getInternetRadioStations")).internetRadioStations?.internetRadioStation);
}
export async function stations() {
  const radios = await internetStations();
  return [
    { id: ENDLESS, name: tr("随机电台", "Library radio"), description: tr("随机播放音乐库并尽量避免重复", "Shuffle your library and avoid recent repeats") },
    ...radios.map(r => ({ id: radioId(r.id), name: r.name || "", description: r.homePageUrl || "" })),
  ];
}
export async function stationNext({ id }) {
  if (id === ENDLESS) {
    const data = await request("getRandomSongs", { size: 100 });
    const songs = list(data.randomSongs?.song);
    const recent = list(host.storage.get("radioRecent"));
    const pending = list(host.storage.get("radioPending"));
    let seen = new Set([...recent, ...pending].map(String));
    let selected = songs.filter(s => !seen.has(String(s.id)));
    if (!selected.length && songs.length) {
      seen = new Set(pending.map(String));
      selected = songs.filter(s => !seen.has(String(s.id)));
      if (!selected.length) selected = songs;
      host.storage.remove("radioRecent");
    }
    const unique = new Set();
    selected = selected.filter(s => !unique.has(String(s.id)) && unique.add(String(s.id))).slice(0, 20);
    host.storage.set("radioPending", [...pending, ...selected.map(s => String(s.id))].slice(-200));
    return selected.map(toTrack);
  }
  const wanted = radioStationId(id);
  const station = wanted === null ? null : (await internetStations()).find(r => String(r.id) === wanted);
  if (!station) throw fail("unavailable", tr("电台不存在", "Radio station not found"));
  return [{ id, name: station.name || "", artists: [], album: { id, name: tr("电台", "Radio") }, duration: 0 }];
}
export async function played({ id }) {
  if (radioStationId(id) !== null) return;
  const trackId = String(id);
  const recent = list(host.storage.get("radioRecent")).filter(item => item !== trackId);
  const pending = list(host.storage.get("radioPending")).filter(item => item !== trackId);
  host.storage.set("radioRecent", [trackId, ...recent].slice(0, 200));
  host.storage.set("radioPending", pending);
  await request("scrobble", { id, submission: true });
}

function asLrc(item) {
  return list(item.line).map(line => {
    if (!item.synced || line.start == null) return line.value || "";
    const time = Math.floor(Math.max(0, Number(line.start) + (Number(item.offset) || 0)));
    const minutes = Math.floor(time / 60000);
    const seconds = Math.floor(time % 60000 / 1000).toString().padStart(2, "0");
    const milliseconds = (time % 1000).toString().padStart(3, "0");
    return `[${String(minutes).padStart(2, "0")}:${seconds}.${milliseconds}]${line.value || ""}`;
  }).join("\n");
}
function cueText(text, cue) {
  if (typeof cue.value === "string") return cue.value;
  if (!Number.isInteger(cue.byteStart) || !Number.isInteger(cue.byteEnd)) return "";
  let at = 0;
  let result = "";
  for (const char of String(text || "")) {
    const code = char.codePointAt(0);
    const bytes = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
    if (at >= cue.byteStart && at + bytes <= cue.byteEnd) result += char;
    at += bytes;
  }
  return result;
}
function structuredLyrics(items) {
  const main = items.find(item => item.kind === "main" && (list(item.line).length || list(item.cueLine).length)) || items.find(item => item.kind !== "translation" && (list(item.line).length || list(item.cueLine).length));
  if (!main) return null;
  const translation = items.find(item => item.kind === "translation" && list(item.line).length);
  const cues = list(main.cueLine);
  if (cues.length) {
    const offset = Number(main.offset) || 0;
    return { lines: cues.map((line, index) => {
      const words = list(line.cue).map(cue => ({ start: Number(cue.start) + offset, end: Number(cue.end ?? cue.start) + offset, text: cueText(line.value, cue) })).filter(cue => cue.text);
      return { start: Number(line.start || 0) + offset, end: Number(line.end ?? line.start ?? 0) + offset, text: line.value || "", ...(words.length ? { words } : {}), ...(translation?.line?.[index] ? { translation: translation.line[index].value || "" } : {}) };
    }) };
  }
  return { lrc: asLrc(main), ...(translation ? { translation: asLrc(translation) } : {}) };
}
function xmlText(value) {
  return value.replace(/<[^>]*>/g, "").replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (all, entity) => {
    const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
    if (entity[0] !== "#") return named[entity.toLowerCase()] || all;
    const n = entity[1].toLowerCase() === "x" ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
    return Number.isFinite(n) && n >= 0 && n <= 0x10ffff ? String.fromCodePoint(n) : all;
  });
}
function clock(value) {
  if (!value) return null;
  const parts = value.replace(/s$/, "").split(":");
  if (parts.length > 3 || parts.some(p => !p || !Number.isFinite(Number(p)) || Number(p) < 0)) return null;
  return Math.round(parts.reduce((sum, part) => sum * 60 + Number(part), 0) * 1000);
}
function timing(attrs, key) {
  const match = attrs.match(new RegExp(`\\b${key}=["']([^"']+)["']`));
  return match ? clock(match[1]) : null;
}
function parseTtml(ttml) {
  const lines = [];
  for (const match of String(ttml).matchAll(/<p\b([^>]*)>([\s\S]*?)<\/p>/gi)) {
    const words = [];
    for (const span of match[2].matchAll(/<span\b([^>]*)>([\s\S]*?)<\/span>/gi)) {
      const start = timing(span[1], "begin");
      if (start !== null) words.push({ start, end: timing(span[1], "end") ?? start, text: xmlText(span[2]) });
    }
    const start = timing(match[1], "begin") ?? words[0]?.start;
    const text = xmlText(match[2]).trim();
    if (start != null && text) lines.push({ start, end: timing(match[1], "end") ?? words[words.length - 1]?.end ?? start, text, ...(words.length ? { words } : {}) });
  }
  return lines;
}
export async function lyrics({ id }) {
  try {
    const data = await request("getLyricsBySongId", { id, enhanced: true });
    const result = structuredLyrics(list(data.lyricsList?.structuredLyrics));
    if (result) return result;
  } catch (error) {
    if (error.code === "setup") throw error;
  }
  const song = (await request("getSong", { id })).song;
  if (!song) return { lrc: "" };
  try {
    const legacy = (await request("getLyrics", { artist: song.artist || "", title: song.title || "" })).lyrics?.value;
    if (legacy) return { lrc: legacy };
  } catch (error) {
    if (error.code === "setup") throw error;
  }
  if (host.setting("betterLyrics") === "off") return { lrc: "" };
  try {
    const url = new URL("https://api.betterlyrics.org/getLyrics");
    url.searchParams.set("s", song.title || "");
    url.searchParams.set("a", song.artist || "");
    if (song.album) url.searchParams.set("al", song.album);
    if (song.duration) url.searchParams.set("d", String(Math.round(Number(song.duration))));
    const response = await fetch(url.toString());
    if (response.ok) {
      const lines = parseTtml((await response.json()).ttml || "");
      if (lines.length) return { lines };
    }
  } catch { /* No external lyrics; playback is unaffected. */ }
  return { lrc: "" };
}

export async function stream({ id, quality = "high" }) {
  const stationId = radioStationId(id);
  if (stationId !== null) {
    const station = (await internetStations()).find(r => String(r.id) === stationId);
    if (!station?.streamUrl) throw fail("unavailable", tr("电台不存在", "Radio station not found"));
    let url;
    try { url = new URL(station.streamUrl); } catch { throw fail("unavailable", tr("电台地址无效", "Invalid radio URL")); }
    if (!/^https?:$/.test(url.protocol)) throw fail("unavailable", tr("电台地址无效", "Invalid radio URL"));
    const path = url.pathname.toLowerCase();
    const type = /\.ogg$/.test(path) ? "ogg" : /\.aac$/.test(path) ? "aac" : "mp3";
    return { url: url.toString(), type };
  }
  const song = (await request("getSong", { id })).song;
  if (!song) throw fail("unavailable", tr("歌曲不存在或无权访问", "Song not found or inaccessible"));
  const suffix = (song.suffix || "").toLowerCase();
  const types = { mp3: "mp3", flac: "flac", ogg: "ogg", oga: "ogg", wav: "wav", m4a: "m4a", mp4: "m4a", aac: "aac" };
  const lossless = quality === "lossless";
  if (lossless && types[suffix]) {
    return { url: endpoint("stream", { id, format: "raw" }), type: types[suffix] };
  }
  // Request a known output format so the player's decoder matches the stream.
  // Navidrome may serve an already-compatible MP3 directly if it is below the limit.
  const maxBitRate = quality === "standard" ? 128 : 320;
  return { url: endpoint("stream", { id, format: "mp3", maxBitRate }), type: "mp3" };
}
