const api = "https://api.spotify.com/v1";
const accounts = "https://accounts.spotify.com";
const redirectPort = 43110;
const redirectURI = `http://127.0.0.1:${redirectPort}/callback`;
const scopes = [
  "user-read-playback-state",
  "user-modify-playback-state",
  "user-read-recently-played",
  "user-read-playback-position",
  "user-top-read",
  "user-library-read",
  "user-library-modify",
  "playlist-read-private",
  "playlist-read-collaborative",
  "playlist-modify-private",
  "playlist-modify-public",
].join(" ");

function fail(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

// Sign-in: Authorization Code with PKCE, so no client secret is needed.

function clientId() {
  const id = (host.setting("clientId") || "").trim();
  if (!id) throw fail("setup", "Paste the Client ID of your Spotify app in the plugin's settings");
  return id;
}

const session = () => host.storage.get("session");

function endSession() {
  host.storage.remove("session");
  host.storage.remove("me");
}

async function tokenRequest(params) {
  const res = await fetch(`${accounts}/api/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: clientId(), ...params }).toString(),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const e = new Error(body.error_description || body.error || `token: ${res.status}`);
    e.oauth = body.error;
    throw e;
  }
  return body;
}

function keep(tokens, previous = {}) {
  const s = {
    access: tokens.access_token,
    refresh: tokens.refresh_token || previous.refresh,
    expiresAt: Date.now() + tokens.expires_in * 1000,
  };
  host.storage.set("session", s);
  return s;
}

// Requests made together find the token expired together. One refresh serves
// them all: Spotify may rotate the refresh token and turn the old one down.
let refreshing = null;
function refresh(s) {
  refreshing ||= tokenRequest({ grant_type: "refresh_token", refresh_token: s.refresh })
    .then(
      (tokens) => keep(tokens, s),
      (e) => {
        if (e.oauth !== "invalid_grant") throw e;
        endSession();
        throw fail("signIn", "Your Spotify session has ended: sign in again");
      },
    )
    .finally(() => (refreshing = null));
  return refreshing;
}

async function accessToken(force) {
  let s = session();
  if (!s) throw fail("signIn");
  if (force || Date.now() > s.expiresAt - 60000) s = await refresh(s);
  return s.access;
}

const unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

function randomString(length) {
  let out = "";
  for (let i = 0; i < length; i++) out += unreserved[Math.floor(Math.random() * unreserved.length)];
  return out;
}

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];

// The plugin runtime has no crypto.subtle, and PKCE needs SHA-256.
function sha256(input) {
  const bytes = [...input];
  const bits = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bits >>> (i * 8)) & 0xff);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  const h = [...H];
  const w = new Array(64);
  for (let at = 0; at < bytes.length; at += 64) {
    for (let i = 0; i < 16; i++) {
      w[i] = (bytes[at + i * 4] << 24) | (bytes[at + i * 4 + 1] << 16) | (bytes[at + i * 4 + 2] << 8) | bytes[at + i * 4 + 3];
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, k] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (k + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      [k, g, f, e, d, c, b, a] = [g, f, e, (d + t1) | 0, c, b, a, (t1 + t2) | 0];
    }
    [a, b, c, d, e, f, g, k].forEach((v, i) => (h[i] = (h[i] + v) | 0));
  }
  return h.flatMap((v) => [v >>> 24, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]);
}

function base64url(bytes) {
  const abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    const chars = Math.ceil((Math.min(3, bytes.length - i) * 4) / 3);
    for (let j = 0; j < chars; j++) out += abc[(n >>> (18 - 6 * j)) & 63];
  }
  return out;
}

export async function signIn() {
  const id = clientId();
  const verifier = randomString(64);
  const state = randomString(16);
  // Listen before the browser opens: Spotify redirects an app it already
  // approved straight back.
  const back = host.waitForRedirect({
    port: redirectPort,
    path: "/callback",
    timeout: 3 * 60 * 1000,
    page: "Signed in to Spotify. You can close this page.",
  });
  const query = new URLSearchParams({
    client_id: id,
    response_type: "code",
    redirect_uri: redirectURI,
    scope: scopes,
    state,
    code_challenge_method: "S256",
    code_challenge: base64url(sha256(new TextEncoder().encode(verifier))),
  });
  host.signInPrompt({ text: "Approve access in your browser" });
  host.openURL(`${accounts}/authorize?${query}`);
  const answer = await back;
  if (answer.state !== state) throw new Error("That sign-in wasn't this one: try again");
  if (answer.error) {
    throw new Error(answer.error === "access_denied" ? "Spotify sign-in was cancelled" : `Spotify: ${answer.error}`);
  }
  keep(await tokenRequest({ grant_type: "authorization_code", code: answer.code, redirect_uri: redirectURI, code_verifier: verifier }));
  return account();
}

export function signOut() {
  endSession();
  host.storage.remove("device");
}

export function setSetting({ key }) {
  if (key === "clientId") signOut();
}

export async function account() {
  if (!session()) return null;
  let me;
  try {
    me = await call("GET", "/me");
  } catch (e) {
    if (e.status !== 403) throw e;
    endSession();
    throw fail(
      "signIn",
      "Spotify won't let this account in: add it under User Management in your app's dashboard, and check the app's owner has Premium",
    );
  }
  host.storage.set("me", me.id);
  return { id: me.id, name: me.display_name || me.id, imageUrl: imageOf(me.images) };
}

// Requests.

const noDevice = "Open Spotify on this computer, your phone or a speaker, then play again";

function apiError(res, data, path) {
  const err = data?.error || {};
  let e;
  if (res.status === 429) {
    e = new Error("Spotify is limiting requests: try again in a moment");
    e.retryAfter = Number(res.headers.get("retry-after")) || 5;
  } else if (err.reason === "PREMIUM_REQUIRED") {
    e = new Error("Playing through Spotify needs Premium");
  } else if (err.reason === "NO_ACTIVE_DEVICE") {
    e = new Error(noDevice);
  } else {
    e = new Error(err.message ? `Spotify: ${err.message}` : `${path}: ${res.status}`);
  }
  e.status = res.status;
  return e;
}

async function call(method, path, { query, body } = {}) {
  const url = new URL(path.startsWith("https://") ? path : api + path);
  for (const [k, v] of Object.entries(query || {})) {
    if (v !== undefined) url.searchParams.set(k, String(v));
  }
  let res;
  for (let attempt = 0; attempt < 2; attempt++) {
    const headers = { authorization: `Bearer ${await accessToken(attempt > 0)}` };
    if (body) headers["content-type"] = "application/json";
    res = await fetch(url.href, { method, headers, body: body ? JSON.stringify(body) : undefined });
    if (res.status !== 401) break;
  }
  if (res.status === 401) {
    endSession();
    throw fail("signIn", "Your Spotify session has ended: sign in again");
  }
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (e) {
    // Some writes answer with plain text, which says nothing more than the status.
  }
  if (!res.ok) throw apiError(res, data, url.pathname);
  return data;
}

async function pages(path, query, pick, atMost) {
  const out = [];
  let next = path;
  for (let i = 0; next && i < atMost; i++) {
    const page = await call("GET", next, i === 0 ? { query } : {});
    out.push(...page.items.map(pick).filter(Boolean));
    next = page.next;
  }
  return out;
}

async function contains(uris) {
  const out = [];
  for (let i = 0; i < uris.length; i += 40) {
    out.push(...(await call("GET", "/me/library/contains", { query: { uris: uris.slice(i, i + 40).join(",") } })));
  }
  return out;
}

const setSaved = (uri, on) => call(on ? "PUT" : "DELETE", "/me/library", { query: { uris: uri } });

// What Spotify answers, as the app has it. A podcast episode is a track whose
// ID says so, as playing it takes its URI.

const imageOf = (images) => images?.[0]?.url;
const artistsOf = (list) => (list || []).map((a) => ({ id: a.id || "", name: a.name }));
const episodeId = (id) => `episode:${id}`;
const uriOf = (id) => (id.startsWith("episode:") ? `spotify:${id}` : `spotify:track:${id}`);

const artistOf = (a) => (a ? { id: a.id, name: a.name, imageUrl: imageOf(a.images) } : null);

function albumOf(a) {
  if (!a) return null;
  return { id: a.id, name: a.name, imageUrl: imageOf(a.images), artists: artistsOf(a.artists), released: a.release_date };
}

function trackOf(t, album) {
  if (!t?.id) return null;
  if (t.type === "episode") return episodeOf(t);
  return {
    id: t.id,
    name: t.name,
    artists: artistsOf(t.artists),
    album: albumOf(album || t.album) || { id: "", name: "" },
    duration: t.duration_ms,
    url: t.external_urls?.spotify,
    unavailable: t.is_playable === false || undefined,
  };
}

function episodeOf(e, show = e?.show || {}) {
  if (!e?.id) return null;
  return {
    id: episodeId(e.id),
    name: e.name,
    artists: [{ id: "", name: show.name || "" }],
    album: { id: "", name: show.name || "", imageUrl: imageOf(e.images) || imageOf(show.images) },
    duration: e.duration_ms,
    url: e.external_urls?.spotify,
    showId: show.id,
    description: e.description,
    published: e.release_date,
  };
}

function playlistOf(p) {
  if (!p) return null;
  const mine = p.owner?.id === host.storage.get("me") || p.collaborative;
  return {
    id: p.id,
    name: p.name,
    imageUrl: imageOf(p.images),
    description: p.description || undefined,
    owner: p.owner?.display_name || p.owner?.id,
    trackCount: (p.items || p.tracks)?.total,
    mine: mine || undefined,
  };
}

const showOf = (s) =>
  s ? { id: s.id, name: s.name, imageUrl: imageOf(s.images), description: s.description, episodeCount: s.total_episodes } : null;

const unique = (tracks) => [...new Map(tracks.map((t) => [t.id, t])).values()];

// Pages.

async function recentTracks() {
  const r = await call("GET", "/me/player/recently-played", { query: { limit: 50 } });
  return unique(r.items.map((i) => trackOf(i.track)).filter(Boolean));
}

// Spotify gives apps like this one no editorial or personalized shelves, so
// Home is made of the listener's own history and library.
export async function home() {
  const [recent, topTracks, topArtists, albums] = await Promise.all([
    recentTracks(),
    call("GET", "/me/top/tracks", { query: { limit: 20, time_range: "short_term" } }),
    call("GET", "/me/top/artists", { query: { limit: 20 } }),
    call("GET", "/me/albums", { query: { limit: 20 } }),
  ]);
  const sections = [
    { title: "Recently Played", tracks: recent.slice(0, 20) },
    { title: "Your Top Tracks", tracks: topTracks.items.map((t) => trackOf(t)).filter(Boolean) },
    { title: "Your Top Artists", artists: topArtists.items.map(artistOf).filter(Boolean) },
    { title: "Saved Albums", albums: albums.items.map((i) => albumOf(i.album)).filter(Boolean) },
  ];
  return { title: "Home", sections: sections.filter((s) => (s.tracks || s.artists || s.albums).length) };
}

export async function recent() {
  return session() ? recentTracks() : [];
}

const searchPick = {
  tracks: (t) => trackOf(t),
  albums: albumOf,
  artists: artistOf,
  playlists: playlistOf,
  shows: showOf,
};

export async function search({ query, kind, offset }) {
  const pick = searchPick[kind];
  if (!pick) return { more: false };
  const type = kind.slice(0, -1);
  const r = await call("GET", "/search", { query: { q: query, type, limit: 10, offset } });
  const page = r[kind];
  return { [kind]: page.items.map(pick).filter(Boolean), more: Boolean(page.next) };
}

export async function album({ id }) {
  const [a, [saved]] = await Promise.all([call("GET", `/albums/${id}`), contains([`spotify:album:${id}`])]);
  const items = [...a.tracks.items];
  for (let next = a.tracks.next, i = 0; next && i < 20; i++) {
    const page = await call("GET", next);
    items.push(...page.items);
    next = page.next;
  }
  return { album: { ...albumOf(a), saved }, tracks: items.map((t) => trackOf(t, a)).filter(Boolean) };
}

export async function artist({ id }) {
  const a = await call("GET", `/artists/${id}`);
  const [albums, found] = await Promise.all([
    pages(`/artists/${id}/albums`, { include_groups: "album,single", limit: 10 }, albumOf, 5),
    // Spotify took an artist's top tracks away from apps like this one. Its
    // search ranks by popularity, which comes close.
    call("GET", "/search", { query: { q: `artist:"${a.name}"`, type: "track", limit: 10 } }),
  ]);
  const tracks = found.tracks.items.filter((t) => t?.artists?.some((x) => x.id === id)).map((t) => trackOf(t));
  return { artist: artistOf(a), tracks, albums };
}

// Playlists and the library.

export async function playlists() {
  if (!session()) return [];
  const queue = host.storage.get("queue")?.id;
  return pages("/me/playlists", { limit: 50 }, (p) => {
    const out = p?.id !== queue && playlistOf(p);
    return out && { ...out, saved: !out.mine || undefined };
  }, 20);
}

export async function playlist({ id }) {
  const p = await call("GET", `/playlists/${id}`);
  let tracks;
  try {
    tracks = await pages(`/playlists/${id}/items`, { limit: 50, additional_types: "track,episode" }, (i) => trackOf(i.item || i.track), 40);
  } catch (e) {
    if (e.status !== 403) throw e;
    throw new Error("Spotify only lets this plugin open playlists you own or collaborate on");
  }
  return { ...playlistOf(p), trackCount: tracks.length, tracks };
}

export async function createPlaylist({ name }) {
  return playlistOf(await call("POST", "/me/playlists", { body: { name, public: false } }));
}

export async function renamePlaylist({ id, name }) {
  await call("PUT", `/playlists/${id}`, { body: { name } });
}

export async function deletePlaylist({ id }) {
  await setSaved(`spotify:playlist:${id}`, false);
}

export async function savePlaylist({ id, on }) {
  await setSaved(`spotify:playlist:${id}`, on);
}

export async function addToPlaylist({ id, trackIds }) {
  for (let i = 0; i < trackIds.length; i += 100) {
    await call("POST", `/playlists/${id}/items`, { body: { uris: trackIds.slice(i, i + 100).map(uriOf) } });
  }
}

export async function removeFromPlaylist({ id, trackIds }) {
  for (let i = 0; i < trackIds.length; i += 100) {
    const items = trackIds.slice(i, i + 100).map((t) => ({ uri: uriOf(t) }));
    await call("DELETE", `/playlists/${id}/items`, { body: { items } });
  }
}

// Spotify inserts before an index of the list as it was, the app names where
// the track ends up.
export async function movePlaylistTrack({ id, from, to }) {
  await call("PUT", `/playlists/${id}/items`, { body: { range_start: from, insert_before: to > from ? to + 1 : to } });
}

export async function liked({ offset }) {
  const r = await call("GET", "/me/tracks", { query: { limit: 50, offset } });
  return { tracks: r.items.map((i) => trackOf(i.track)).filter(Boolean), more: Boolean(r.next) };
}

export async function isLiked({ ids }) {
  const saved = session() ? await contains(ids.map(uriOf)) : [];
  return Object.fromEntries(ids.map((id, i) => [id, Boolean(saved[i])]));
}

export async function like({ id, on }) {
  await setSaved(uriOf(id), on);
}

export async function savedAlbums({ offset }) {
  const r = await call("GET", "/me/albums", { query: { limit: 50, offset } });
  return { albums: r.items.map((i) => albumOf(i.album)).filter(Boolean).map((a) => ({ ...a, saved: true })), more: Boolean(r.next) };
}

export async function saveAlbum({ id, on }) {
  await setSaved(`spotify:album:${id}`, on);
}

// Podcasts.

export async function shows() {
  if (!session()) return [];
  return pages("/me/shows", { limit: 50 }, (i) => {
    const s = showOf(i.show);
    return s && { ...s, subscribed: true };
  }, 10);
}

export async function show({ id, offset }) {
  const [s, page, [subscribed]] = await Promise.all([
    call("GET", `/shows/${id}`),
    call("GET", `/shows/${id}/episodes`, { query: { limit: 50, offset } }),
    contains([`spotify:show:${id}`]),
  ]);
  return {
    show: { ...showOf(s), subscribed },
    episodes: page.items.map((e) => episodeOf(e, s)).filter(Boolean),
    more: Boolean(page.next),
  };
}

export async function subscribeShow({ id, on }) {
  await setSaved(`spotify:show:${id}`, on);
}

// Lyrics, from LRCLIB: the Web API has none.

export async function lyrics({ id }) {
  if (id.startsWith("episode:")) return {};
  const t = await call("GET", `/tracks/${id}`);
  const query = new URLSearchParams({
    track_name: t.name,
    artist_name: t.artists[0]?.name || "",
    album_name: t.album?.name || "",
    duration: String(Math.round(t.duration_ms / 1000)),
  });
  const res = await fetch(`https://lrclib.net/api/get?${query}`, { headers: { "user-agent": "mygo-music-spotify/0.1.1" } });
  if (!res.ok) return {};
  const found = await res.json();
  return found.syncedLyrics ? { lrc: found.syncedLyrics } : {};
}

// Playing, on a Spotify Connect device: Spotify's own apps, a speaker, or
// anything else the account can play on.

// librespot, once installed and allowed, plays on this computer as its own
// device, so Spotify's app needn't be open. Its first start opens the browser
// to sign in; it keeps the sign-in in the plugin's data.
const localName = "MyGo Music";
const librespotPaths = ["/opt/homebrew/bin/librespot", "/usr/local/bin/librespot"];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let local = null;

function startLocal() {
  if (local) return local;
  local = (async () => {
    let p;
    for (const path of librespotPaths) {
      try {
        p = host.spawn(path, [
          "--name", localName, "--device-type", "computer", "--bitrate", "320",
          "--disable-discovery", "--enable-oauth", "--system-cache", `${host.dataDir}/librespot`,
        ]);
        break;
      } catch {}
    }
    if (!p) return;
    let ended = false;
    p.wait().then(() => {
      ended = true;
      local = null;
    });
    // The app gives up on a call after 30 seconds. A sign-in that takes longer
    // still lands: the device shows on the next play.
    const until = Date.now() + 20000;
    try {
      while (!ended && Date.now() < until) {
        const { devices } = await call("GET", "/me/player/devices");
        if (devices.some((d) => d.name === localName)) return;
        await sleep(1000);
      }
    } catch {}
  })();
  return local;
}

async function deviceId() {
  await startLocal();
  const { devices } = await call("GET", "/me/player/devices");
  const usable = devices.filter((d) => d.id && !d.is_restricted);
  const chosen = host.storage.get("device");
  const d =
    usable.find((x) => x.is_active) ||
    usable.find((x) => x.id === chosen) ||
    usable.find((x) => x.name === localName) ||
    usable[0];
  if (!d) throw new Error(noDevice);
  return d.id;
}

// Spotify goes on through the songs it's given by itself. A window of them
// keeps the request small; when it runs out, the app asks for the next ones.
const playWindow = 100;

// Spotify's desktop app takes a list of songs to play (`uris`) and then plays
// nothing, while it plays a playlist from any of its songs. So the queue is
// written to a playlist of the listener's own and played from there. It stays
// in their library: Spotify turns down changes to one they don't follow.
async function queuePlaylist() {
  const me = host.storage.get("me");
  const known = host.storage.get("queue");
  if (known?.owner === me) return known.id;
  const p = await call("POST", "/me/playlists", {
    body: { name: "MyGo Music Queue", public: false, description: "What MyGo Music plays. It's rewritten each time you play." },
  });
  host.storage.set("queue", { owner: me, id: p.id });
  return p.id;
}

// Once the listener deletes it, Spotify answers 404 to changing it, so a new
// one takes its place.
async function fillQueue(uris) {
  const write = async () => {
    const id = await queuePlaylist();
    await call("PUT", `/playlists/${id}/items`, { body: { uris } });
    return id;
  };
  try {
    return await write();
  } catch (e) {
    if (e.status !== 404) throw e;
    host.storage.remove("queue");
    return write();
  }
}

// The last answer about what plays, for while Spotify asks to wait.
let last = null;
let quietUntil = 0;

export async function remotePlay({ ids, start, position }) {
  last = null;
  const [queue, device] = await Promise.all([fillQueue(ids.slice(start, start + playWindow).map(uriOf)), deviceId()]);
  await call("PUT", "/me/player/play", {
    query: { device_id: device },
    body: { context_uri: `spotify:playlist:${queue}`, offset: { position: 0 }, position_ms: position },
  });
}

export async function remotePause() {
  last = null;
  await call("PUT", "/me/player/pause");
}

export async function remoteResume() {
  last = null;
  await call("PUT", "/me/player/play");
}

export async function remoteSeek({ position }) {
  last = null;
  await call("PUT", "/me/player/seek", { query: { position_ms: position } });
}

export async function remoteVolume({ percent }) {
  await call("PUT", "/me/player/volume", { query: { volume_percent: percent } });
}

function guess() {
  const { state, at } = last;
  if (!state.playing) return state;
  return { ...state, position: Math.min(state.position + Date.now() - at, state.duration || Infinity) };
}

// The app asks about once a second, and stops after a few failed answers,
// though Spotify plays on. While Spotify asks to wait, the last answer goes on
// by the clock.
export async function remoteState() {
  if (last && Date.now() < quietUntil) return guess();
  let p;
  try {
    p = await call("GET", "/me/player", { query: { additional_types: "episode" } });
  } catch (e) {
    if (!e.retryAfter || !last) throw e;
    quietUntil = Date.now() + e.retryAfter * 1000;
    return guess();
  }
  if (!p) {
    last = null;
    return null;
  }
  const item = p.item;
  const state = {
    playing: p.is_playing,
    trackId: item ? (p.currently_playing_type === "episode" ? episodeId(item.id) : item.id) : "",
    position: p.progress_ms ?? 0,
    duration: item?.duration_ms ?? 0,
    volume: p.device?.volume_percent ?? 100,
    device: p.device?.name,
  };
  last = { state, at: Date.now() };
  return state;
}

export async function remoteDevices() {
  await startLocal();
  const { devices } = await call("GET", "/me/player/devices");
  return devices.filter((d) => d.id && !d.is_restricted).map((d) => ({ id: d.id, name: d.name, active: d.is_active }));
}

export async function remoteUse({ deviceId: id }) {
  await call("PUT", "/me/player", { body: { device_ids: [id] } });
  host.storage.set("device", id);
}
