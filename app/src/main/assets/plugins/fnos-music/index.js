// fnOS Music (飞牛音乐) plugin for MyGo Music.
//
// fnOS Music is the music app of fnOS (飞牛 OS). It exposes a JSON API under
// /music/api/v1, with two things worth knowing:
//
//   * every request carries an `authx` header, an MD5 signature over the
//     request path, a nonce, a timestamp and the request data;
//   * after signing in, the session token travels in the `music-token` cookie.
//
// Both are handled here. The listener only has to fill in the server address,
// a username and a password.

const SIGN_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
const API_KEY = "6D5602D4-A342-4799-A0F0-BB795E7167D0";
const API_PREFIX = "/music/api/v1";
const COVER_PATH = "/static/cover";
const PAGE = 50;

// --- small helpers ---------------------------------------------------------

function fail(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

function setting(key) {
  const v = host.setting(key);
  return v == null ? "" : String(v);
}

function save(key, value) {
  host.storage.set(key, value);
}

function load(key) {
  return host.storage.get(key);
}

function drop(key) {
  host.storage.remove(key);
}

function sessionCookie(token = load("token")) {
  return token ? "music-token=" + token : "";
}

// The listener may type either an origin or a full page such as
// https://host/music/login. The API always lives at the origin under /music.
function serverOrigin() {
  let s = setting("server").trim();
  if (!s) throw fail("setup", "Enter the fnOS Music server address in the plugin settings.");
  if (!/^https?:\/\//i.test(s)) s = "https://" + s;
  let u;
  try {
    u = new URL(s);
  } catch {
    throw fail("setup", "The server address is not a web address.");
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") {
    throw fail("setup", "The server address must start with http:// or https://.");
  }
  return u.origin;
}

function apiURL(path) {
  return serverOrigin() + API_PREFIX + path;
}

function coverURL(id) {
  if (!id) return "";
  return apiURL(COVER_PATH) + "?coverId=" + encodeURIComponent(id);
}

function deviceId() {
  let id = load("device");
  if (typeof id === "string" && id.length >= 16) return id;
  const alphabet = "0123456789abcdef";
  let out = "";
  for (let i = 0; i < 32; i++) out += alphabet[Math.floor(Math.random() * 16)];
  save("device", out);
  return out;
}

// --- sha256 ----------------------------------------------------------------
//
// The login endpoint wants the password as a SHA-256 hex digest. The host
// offers MD5 only, so this is a small pure JavaScript SHA-256.

const SHA256_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
  0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
  0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
  0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
  0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

function rotr(x, n) {
  return (x >>> n) | (x << (32 - n));
}

function utf8Bytes(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    let c = text.codePointAt(i);
    if (c > 0xffff) i++;
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    } else if (c < 0x10000) {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    } else {
      out.push(
        0xf0 | (c >> 18),
        0x80 | ((c >> 12) & 63),
        0x80 | ((c >> 6) & 63),
        0x80 | (c & 63),
      );
    }
  }
  return out;
}

function sha256Hex(text) {
  const bytes = utf8Bytes(text);
  const bitLength = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  const hi = Math.floor(bitLength / 0x100000000) >>> 0;
  const lo = bitLength >>> 0;
  for (const word of [hi, lo]) {
    bytes.push((word >>> 24) & 255, (word >>> 16) & 255, (word >>> 8) & 255, word & 255);
  }

  const h = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const w = new Uint32Array(64);

  for (let offset = 0; offset < bytes.length; offset += 64) {
    for (let i = 0; i < 16; i++) {
      const p = offset + i * 4;
      w[i] = ((bytes[p] << 24) | (bytes[p + 1] << 16) | (bytes[p + 2] << 8) | bytes[p + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }

    let a = h[0], b = h[1], c = h[2], d = h[3];
    let e = h[4], f = h[5], g = h[6], k = h[7];

    for (let i = 0; i < 64; i++) {
      const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (k + s1 + ch + SHA256_K[i] + w[i]) >>> 0;
      const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (s0 + maj) >>> 0;
      k = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }

    h[0] = (h[0] + a) >>> 0;
    h[1] = (h[1] + b) >>> 0;
    h[2] = (h[2] + c) >>> 0;
    h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0;
    h[5] = (h[5] + f) >>> 0;
    h[6] = (h[6] + g) >>> 0;
    h[7] = (h[7] + k) >>> 0;
  }

  return h.map((x) => x.toString(16).padStart(8, "0")).join("");
}

// --- requests --------------------------------------------------------------

function signRequest(method, pathname, params, bodyText) {
  let data;
  if (method === "GET") {
    data = Object.keys(params || {})
      .filter((k) => params[k] != null)
      .sort()
      .map((k) => k + "=" + params[k])
      .join("&");
  } else {
    data = bodyText || "";
  }
  const nonce = String(Math.floor(Math.random() * 900000) + 100000);
  const timestamp = String(Date.now());
  const digest = host.crypto.md5(data);
  const signature = host.crypto.md5(
    [SIGN_KEY, pathname, nonce, timestamp, digest, API_KEY].join("_"),
  );
  return `nonce=${nonce}&timestamp=${timestamp}&sign=${signature}`;
}

async function rawRequest(method, path, params, body, withSession) {
  const url = new URL(apiURL(path));
  if (params) {
    for (const key of Object.keys(params)) {
      if (params[key] == null) continue;
      url.searchParams.set(key, String(params[key]));
    }
  }

  const bodyText = body === undefined ? "" : JSON.stringify(body);
  const headers = {
    authx: signRequest(method, url.pathname, params, bodyText),
  };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (withSession !== false) {
    const cookie = sessionCookie();
    if (cookie) headers.Cookie = cookie;
  }

  let res;
  try {
    res = await fetch(url.toString(), {
      method,
      headers,
      body: body === undefined ? undefined : bodyText,
    });
  } catch (e) {
    throw new Error("Can't reach " + serverOrigin() + ": " + (e && e.message ? e.message : e));
  }

  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error("The server answered with something that isn't song data.");
  }

  if (json && json.code === 0) return json.data;

  const message = json && json.msg ? String(json.msg) : `HTTP ${res.status}`;
  if (res.status === 401 || (json && json.code === 99999) || /token/i.test(message)) {
    const e = fail("signIn", "The fnOS session has expired. Sign in again.");
    e.auth = true;
    throw e;
  }
  throw new Error(message);
}

async function login() {
  const username = setting("username").trim();
  const password = setting("password");
  if (!username || !password) {
    throw fail("setup", "Set your fnOS username and password in the plugin's settings.");
  }
  const data = await rawRequest(
    "POST",
    "/user/password-login",
    null,
    { username, password: sha256Hex(password), deviceId: deviceId() },
    false,
  );
  if (!data || !data.userToken) {
    throw fail("signIn", "The server didn't return a session. Check your username and password.");
  }
  save("token", data.userToken);
  const user = data.user || {};
  const account = {
    id: user.guid || username,
    name: user.name || username,
  };
  save("account", account);
  return account;
}

function forgetSession() {
  drop("token");
  drop("account");
}

async function api(method, path, params, body) {
  if (!load("token")) await ensureSession();
  try {
    return await rawRequest(method, path, params, body);
  } catch (e) {
    if (e && e.auth) {
      forgetSession();
      await ensureSession();
      return rawRequest(method, path, params, body);
    }
    throw e;
  }
}

// One login at a time: the first page asks for home, albums, artists and
// playlists at once, and without this each would sign in by itself.
let authPending = null;
async function ensureSession() {
  if (load("token")) return;
  if (!authPending) {
    authPending = login().finally(() => {
      authPending = null;
    });
  }
  await authPending;
}

// --- shapes ----------------------------------------------------------------

function artistOf(a) {
  if (!a) return null;
  return {
    id: a.guid,
    name: a.name,
    imageUrl: coverURL(a.coverId),
  };
}

function albumOf(a) {
  if (!a) return { id: "", name: "", imageUrl: "", artists: [], released: "" };
  return {
    id: a.guid,
    name: a.name,
    imageUrl: coverURL(a.coverId),
    artists: (a.artists || []).map(artistOf).filter(Boolean),
    released: a.releaseDate || "",
  };
}

function trackOf(t) {
  const album = t.album || {};
  return {
    id: t.guid,
    name: t.title || "Untitled",
    artists: (t.artists || []).map(artistOf).filter(Boolean),
    album: {
      ...albumOf(album),
      imageUrl: coverURL(album.coverId || t.coverId),
    },
    duration: typeof t.duration === "number" ? t.duration : 0,
    unavailable: !!(t.accessStatus && t.accessStatus !== 0),
  };
}

function playlistOf(p, trackCount) {
  return {
    id: p.guid,
    name: p.name,
    imageUrl: coverURL(p.coverId),
    trackCount: typeof trackCount === "number" ? trackCount : p.trackCount || 0,
    mine: true,
  };
}

function trackList(data) {
  return ((data && data.list) || []).map(trackOf);
}

function albumList(data) {
  return ((data && data.list) || []).map(albumOf);
}

function artistList(data) {
  return ((data && data.list) || []).map(artistOf).filter(Boolean);
}

function pageOf(data, offset, size) {
  const all = (data && data.list) || [];
  const total = typeof data?.total === "number" ? data.total : all.length;
  const list = all.slice(offset, offset + size);
  return { list, more: offset + list.length < total };
}

// --- account ---------------------------------------------------------------

export async function signIn() {
  forgetSession();
  return await login();
}

export function signOut() {
  const token = load("token");
  if (token) {
    // Let the server forget the session too, without waiting for it.
    rawRequest("POST", "/user/logout", null, undefined, true).catch(() => {});
  }
  forgetSession();
}

export function account() {
  if (!load("token")) return null;
  return load("account") || null;
}

export function setSetting({ key }) {
  if (key === "server" || key === "username" || key === "password") {
    forgetSession();
  }
}

// --- home and pages --------------------------------------------------------

async function listPlaylists() {
  const data = await api("GET", "/playlist/list");
  const rows = (data && data.list) || [];
  if (rows.length === 0) return [];
  const guids = rows.map((p) => p.guid);
  let counts = {};
  try {
    const detail = await api("GET", "/playlist/batch-detail", { guids: guids.join(",") });
    counts = Object.fromEntries(
      ((detail && detail.list) || []).map((p) => [p.guid, p.trackCount || 0]),
    );
  } catch {
    counts = {};
  }
  return rows.map((p) => playlistOf(p, counts[p.guid]));
}

export async function home() {
  const [tracks, albums, artists, playlists] = await Promise.all([
    api("GET", "/track/list", { page: 1, size: 20 }),
    api("GET", "/album/list", { page: 1, size: 12 }),
    api("GET", "/artist/list", { page: 1, size: 12 }),
    listPlaylists(),
  ]);

  return {
    title: host.t("fnOS Music"),
    sections: [
      {
        title: host.t("Recently Added"),
        tracks: trackList(tracks),
        morePage: "songs",
      },
      {
        title: host.t("Albums"),
        albums: albumList(albums),
        morePage: "albums",
        empty: host.t("No albums"),
      },
      {
        title: host.t("Artists"),
        artists: artistList(artists),
        morePage: "artists",
        empty: host.t("No artists"),
      },
      {
        title: host.t("Playlists"),
        playlists,
        morePage: "playlists",
        empty: host.t("No playlists yet"),
      },
    ],
  };
}

export async function browse({ page }) {
  if (page === "songs") {
    const data = await api("GET", "/track/list", { page: 1, size: 1000 });
    return {
      title: host.t("Songs"),
      sections: [{ title: host.t("All Songs"), tracks: trackList(data) }],
    };
  }
  if (page === "albums") {
    const data = await api("GET", "/album/list", { page: 1, size: 1000 });
    return {
      title: host.t("Albums"),
      sections: [{ title: host.t("All Albums"), albums: albumList(data) }],
    };
  }
  if (page === "artists") {
    const data = await api("GET", "/artist/list", { page: 1, size: 1000 });
    return {
      title: host.t("Artists"),
      sections: [{ title: host.t("All Artists"), artists: artistList(data) }],
    };
  }
  if (page === "playlists") {
    return {
      title: host.t("Playlists"),
      sections: [{ title: host.t("My Playlists"), playlists: await listPlaylists() }],
    };
  }
  throw fail("unavailable", "No such page.");
}

// --- search ----------------------------------------------------------------

export async function search({ query, kind, offset = 0 }) {
  const q = (query || "").trim();
  if (!q) return { [kind]: [], more: false };

  if (kind === "tracks") {
    const data = await api("GET", "/search/track", { q });
    const page = pageOf(data, offset, PAGE);
    return { tracks: page.list.map(trackOf), more: page.more };
  }
  if (kind === "albums") {
    const data = await api("GET", "/search/album", { q });
    const page = pageOf(data, offset, PAGE);
    return { albums: page.list.map(albumOf), more: page.more };
  }
  if (kind === "artists") {
    const data = await api("GET", "/search/artist", { q });
    const page = pageOf(data, offset, PAGE);
    return { artists: page.list.map(artistOf).filter(Boolean), more: page.more };
  }
  if (kind === "playlists") {
    const data = await api("GET", "/search/playlist", { q });
    const page = pageOf(data, offset, PAGE);
    return { playlists: page.list.map((p) => playlistOf(p)), more: page.more };
  }
  return { [kind]: [], more: false };
}

export async function suggest({ query }) {
  const q = (query || "").trim();
  if (!q) return [];
  const data = await api("GET", "/search/suggest", { q });
  const names = [];
  const add = (v) => {
    if (typeof v === "string" && v && !names.includes(v)) names.push(v);
  };
  for (const group of ["track", "album", "artist", "playlist"]) {
    const items = (data && data[group] && data[group].items) || [];
    for (const item of items) add(item.title || item.name);
  }
  return names.slice(0, 12);
}

// --- library ---------------------------------------------------------------

export async function recent() {
  try {
    const data = await api("GET", "/play-history/list", { page: 1, size: 50 });
    return trackList(data);
  } catch {
    return [];
  }
}

export async function liked({ offset = 0 }) {
  const page = Math.floor(offset / 100) + 1;
  const data = await api("GET", "/favorite-track/list", {
    page,
    size: 100,
    sort: "favoriteAt,desc",
  });
  const list = trackList(data).slice(offset % 100);
  const total = typeof data?.total === "number" ? data.total : list.length;
  return { tracks: list, more: offset + list.length < total };
}

export async function isLiked({ ids }) {
  const wanted = new Set(ids || []);
  const found = new Set();
  let total = Infinity;
  for (let page = 1; page <= 5 && found.size < wanted.size; page++) {
    const data = await api("GET", "/favorite-track/list", {
      page,
      size: 300,
      sort: "favoriteAt,desc",
    });
    const rows = (data && data.list) || [];
    for (const row of rows) found.add(row.guid);
    total = typeof data?.total === "number" ? data.total : rows.length;
    if (rows.length === 0 || page * 300 >= total) break;
  }
  return Object.fromEntries((ids || []).map((id) => [id, found.has(id)]));
}

export async function like({ id, on }) {
  await api("POST", on ? "/favorite-track/create" : "/favorite-track/delete", null, {
    trackGUID: id,
  });
}

// --- albums and artists ----------------------------------------------------

export async function album({ id }) {
  const [detail, tracks] = await Promise.all([
    api("GET", "/album/detail", { guid: id }),
    api("GET", "/track/album-detail/list", {
      albumGUID: id,
      page: 1,
      size: 500,
      sort: "trackNo,asc",
    }),
  ]);
  const list = trackList(tracks);
  const a = albumOf(detail);
  if (a.artists.length === 0) {
    const seen = new Set();
    for (const track of list) {
      for (const artist of track.artists) {
        if (!seen.has(artist.id)) {
          seen.add(artist.id);
          a.artists.push(artist);
        }
      }
    }
  }
  return { album: a, tracks: list };
}

export async function artist({ id }) {
  const [detail, tracks, albums] = await Promise.all([
    api("GET", "/artist/detail", { guid: id }),
    api("GET", "/track/artist-detail/list", {
      artistGUID: id,
      page: 1,
      size: 200,
      sort: "newTrackAddedAt,desc",
    }),
    api("GET", "/album/artist-detail/list", {
      artistGUID: id,
      page: 1,
      size: 100,
      sort: "newTrackAddedAt,desc",
    }),
  ]);
  return {
    artist: artistOf(detail),
    tracks: trackList(tracks),
    albums: albumList(albums),
  };
}

// --- playlists -------------------------------------------------------------

export async function playlists() {
  return await listPlaylists();
}

export async function playlist({ id }) {
  const [detail, tracks] = await Promise.all([
    api("GET", "/playlist/detail", { guid: id }),
    api("GET", "/track/playlist-detail/list", {
      playlistGUID: id,
      page: 1,
      size: 1000,
      sort: "trackAddedAt,asc",
    }),
  ]);
  const list = trackList(tracks);
  const p = playlistOf(detail, list.length);
  return { ...p, tracks: list };
}

export async function createPlaylist({ name }) {
  const data = await api("POST", "/playlist/create", null, { name, coverId: null });
  return playlistOf(data);
}

export async function renamePlaylist({ id, name }) {
  let detail = null;
  try {
    detail = await api("GET", "/playlist/detail", { guid: id });
  } catch {
    // Editing still works without the old cover.
  }
  await api("POST", "/playlist/edit", null, {
    guid: id,
    name,
    coverId: detail ? detail.coverId || null : null,
  });
}

export async function deletePlaylist({ id }) {
  await api("POST", "/playlist/delete", null, { guid: id });
}

export async function addToPlaylist({ id, trackIds }) {
  await api("POST", "/playlist/add-track", null, {
    guid: id,
    trackGUIDs: trackIds,
  });
}

export async function removeFromPlaylist({ id, trackIds }) {
  await api("POST", "/playlist/remove-track", null, {
    guid: id,
    trackGUIDs: trackIds,
  });
}

// --- lyrics ----------------------------------------------------------------

export async function lyrics({ id }) {
  let data;
  try {
    data = await api("GET", "/lyric/list", { trackGUID: id });
  } catch {
    return { lrc: "" };
  }
  const rows = (data && data.list) || [];
  if (rows.length === 0) return { lrc: "" };
  const row = rows.find((r) => r.isLRC && r.content) || rows.find((r) => r.content);
  if (!row) return { lrc: "" };
  let content = String(row.content);
  // fnOS appends an extra LRC (or translation) in a base64 marker block.
  content = content.replace(/\n*\[awlrc:[\s\S]*$/, "").trimEnd();
  if (row.offset) content = content.replace(/\[offset:[^\]]*\]/g, `[offset:${row.offset}]`);
  return { lrc: content };
}

// --- playback --------------------------------------------------------------

function audioType(spec) {
  const f = String((spec && (spec.format || spec.container || spec.codec)) || "").toLowerCase();
  if (f === "mp3") return "mp3";
  if (f === "flac") return "flac";
  if (f === "wav" || f === "wave") return "wav";
  if (f === "m4a" || f === "mp4" || f === "alac") return "m4a";
  if (f === "aac") return "aac";
  if (f === "ogg" || f === "oga" || f === "vorbis" || f === "opus") return "ogg";
  return "";
}

export async function stream({ id }) {
  const meta = await api("GET", "/track/metadata", { guid: id });
  const spec = (meta && meta.audioSpec) || {};
  const type = audioType(spec);
  if (!type) {
    throw fail("unavailable", "This file's format isn't supported by MyGo Music.");
  }
  const cookie = sessionCookie();
  return {
    url: apiURL("/track/stream") + "?guid=" + encodeURIComponent(id),
    type,
    headers: cookie ? { Cookie: cookie } : undefined,
    size: typeof spec.size === "number" ? spec.size : undefined,
  };
}
