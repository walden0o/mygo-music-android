// Ampache JSON API v6. No Node APIs or external dependencies in the plugin.
const pageSize = 100;
function fail(code, message) { return Object.assign(new Error(message), { code }); }
function config() {
  const server = (host.setting("server") || "").trim();
  const username = host.setting("username") || "";
  const password = host.setting("password") || "";
  if (!server || !username || !password) throw fail("setup", "Set the Ampache URL, username and API password first");
  let url;
  try { url = new URL(server); } catch { throw fail("setup", "Invalid Ampache URL"); }
  if (!/^https?:$/.test(url.protocol) || url.username || url.password) throw fail("setup", "Use an HTTP or HTTPS URL without embedded credentials");
  if (!/\/server\/(xml|json)\.server\.php\/?$/.test(url.pathname)) throw fail("setup", "Enter the full Ampache API URL, ending in /server/xml.server.php");
  url.pathname = url.pathname.replace(/xml\.server\.php/, "json.server.php").replace(/\/$/, "");
  url.search = ""; url.hash = "";
  return { url, username, password };
}

// SHA-256 is needed even on hosts without Web Crypto.
function sha256(text) {
  const primes = [], h = [], k = [];
  for (let n = 2; primes.length < 64; n++) {
    if (primes.some(p => n % p === 0)) continue;
    primes.push(n);
    if (h.length < 8) h.push((Math.sqrt(n) * 0x100000000) | 0);
    k.push((Math.cbrt(n) * 0x100000000) | 0);
  }
  const bytes = [...new TextEncoder().encode(text)], bits = bytes.length * 8;
  bytes.push(128);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bits >>> (i * 8)) & 255);
  const r = (v, n) => (v >>> n) | (v << (32 - n));
  for (let at = 0; at < bytes.length; at += 64) {
    const w = [];
    for (let i = 0; i < 16; i++) w[i] = (bytes[at+i*4]<<24)|(bytes[at+i*4+1]<<16)|(bytes[at+i*4+2]<<8)|bytes[at+i*4+3];
    for (let i = 16; i < 64; i++) {
      const x = w[i-15], y = w[i-2];
      w[i] = (w[i-16] + (r(x,7)^r(x,18)^(x>>>3)) + w[i-7] + (r(y,17)^r(y,19)^(y>>>10))) | 0;
    }
    let [a,b,c,d,e,f,g,j] = h;
    for (let i = 0; i < 64; i++) {
      const t = (j + (r(e,6)^r(e,11)^r(e,25)) + ((e&f)^(~e&g)) + k[i] + w[i]) | 0;
      const u = ((r(a,2)^r(a,13)^r(a,22)) + ((a&b)^(a&c)^(b&c))) | 0;
      [a,b,c,d,e,f,g,j] = [(t+u)|0,a,b,c,(d+t)|0,e,f,g];
    }
    [a,b,c,d,e,f,g,j].forEach((v,i) => h[i] = (h[i]+v)|0);
  }
  return h.map(v => (v>>>0).toString(16).padStart(8,"0")).join("");
}
async function request(action, params = {}) {
  const { url } = config();
  url.searchParams.set("action", action);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
  const res = await fetch(url.href);
  if (!res.ok) throw fail(res.status === 401 ? "signIn" : "unavailable", `Ampache HTTP ${res.status}`);
  let data;
  try { data = await res.json(); } catch { throw fail("unavailable", "Ampache returned invalid JSON. Check the API URL."); }
  if (data.error) {
    const error = Array.isArray(data.error) ? data.error[0] : data.error;
    const code = Number(error.code ?? data.errorCode);
    // Do not echo server error text, which may contain credentials or URLs.
    throw fail([401,403,470,4701,4703].includes(code) ? "signIn" : "unavailable", `Ampache API error ${code || "unknown"}`);
  }
  return data;
}
let pending = null;
let generation = 0;
export function signOut() { generation++; pending = null; host.storage.remove("session"); }
export function setSetting() { signOut(); }
export async function signIn() {
  signOut();
  await session();
  return account();
}
export function account() {
  if (!host.storage.get("session")) return null;
  const { username } = config();
  return { id: username, name: username };
}
async function session() {
  const saved = host.storage.get("session");
  if (saved && saved.until > Date.now()) return saved.token;
  if (pending) return pending;
  const current = generation;
  const job = (async () => {
    const { username, password } = config();
    const timestamp = Math.floor(Date.now()/1000);
    const data = await request("handshake", { user: username, timestamp, auth: sha256(`${timestamp}${sha256(password)}`), version: "600001" });
    if (!data.auth) throw fail("signIn", "Ampache did not return a session");
    if (current !== generation) throw fail("signIn", "Settings changed during sign-in");
    const expires = Date.parse(data.session_expire);
    host.storage.set("session", { token: data.auth, until: Number.isFinite(expires) ? Math.min(expires-60000, Date.now()+1800000) : Date.now()+1800000 });
    return data.auth;
  })();
  pending = job;
  try { return await job; } finally { if (pending === job) pending = null; }
}
async function call(action, params = {}) {
  try { return await request(action, { ...params, auth: await session() }); }
  catch (e) {
    if (e.code !== "signIn") throw e;
    host.storage.remove("session");
    // All callers are read-only, so one reauthentication and retry is safe.
    try { return await request(action, { ...params, auth: await session() }); }
    catch (retry) { if (retry.code === "signIn") host.storage.remove("session"); throw retry; }
  }
}
const list = (data, key) => Array.isArray(data) ? data : Array.isArray(data[key]) ? data[key] : data[key] ? [data[key]] : [];
const ref = x => ({ id: String(x?.id ?? ""), name: x?.name ?? x?.text ?? "" });
const artistOf = x => ({ ...ref(x), imageUrl: x.art || undefined });
const albumOf = x => ({ ...ref(x), imageUrl: x.art || undefined, artists: x.artist ? [ref(x.artist)] : [], released: x.year ? String(x.year) : undefined });
const trackOf = x => ({ id: String(x.id), name: x.title || x.name || "", artists: x.artists?.map(ref) || (x.artist ? [ref(x.artist)] : []), album: { ...ref(x.album), imageUrl: x.art || undefined }, duration: Number(x.time || 0)*1000 });
const playlistOf = x => ({ ...ref(x), owner: x.owner || "", trackCount: Number(x.items ?? x.songs ?? 0) });
const kinds = { tracks: ["songs","song",trackOf], albums: ["albums","album",albumOf], artists: ["artists","artist",artistOf], playlists: ["playlists","playlist",playlistOf] };
export async function search({ query = "", kind = "tracks", offset = 0 }) {
  if (!kinds[kind]) return { [kind]: [], more: false };
  const [action,key,map] = kinds[kind];
  const rows = list(await call(action, { filter: query, offset, limit: pageSize }), key);
  return { [kind]: rows.map(map), more: rows.length === pageSize };
}
async function all(action, key, params = {}) {
  const rows = [];
  for (let offset = 0; ; offset += pageSize) {
    const batch = list(await call(action, { ...params, offset, limit: pageSize }), key);
    rows.push(...batch);
    if (batch.length < pageSize) return rows;
    if (rows.length >= 100000) throw fail("unavailable", "Ampache list exceeded 100000 entries");
  }
}
export async function home() {
  const [tracks, albums, artists] = await Promise.all([search({kind:"tracks"}), search({kind:"albums"}), search({kind:"artists"})]);
  return { title: "Ampache", sections: [{ title:"Songs", tracks:tracks.tracks }, { title:"Albums", albums:albums.albums }, { title:"Artists", artists:artists.artists }] };
}
export async function album({ id }) {
  const data = await call("album", { filter:id });
  const item = list(data,"album")[0] || data;
  return { album:albumOf(item), tracks:(await all("album_songs","song",{filter:id})).map(trackOf) };
}
export async function artist({ id }) {
  const data = await call("artist", { filter:id });
  const item = list(data,"artist")[0] || data;
  return { artist:artistOf(item), tracks:(await all("artist_songs","song",{filter:id})).map(trackOf), albums:(await all("artist_albums","album",{filter:id})).map(albumOf) };
}
export async function playlists() {
  if (!host.storage.get("session")) return [];
  return (await all("playlists","playlist")).map(playlistOf);
}
export async function playlist({ id }) {
  const data = await call("playlist", { filter:id });
  const item = list(data,"playlist")[0] || data;
  const tracks = (await all("playlist_songs","song",{filter:id})).map(trackOf);
  return { ...playlistOf(item), tracks, trackCount:tracks.length };
}
// Better Lyrics lookup and TTML mapping follow egoist/mygo-music-youtube-music.
export async function lyrics({ id }) {
  const data = await call("song", { filter: id });
  const track = trackOf(list(data, "song")[0] || data);
  if (!track.name || !track.artists.some(a => a.name)) return { lines: [] };
  const query = new URLSearchParams({ s: track.name, a: track.artists.map(a => a.name).join(", ") });
  if (track.album.name) query.set("al", track.album.name);
  if (track.duration) query.set("d", String(Math.round(track.duration / 1000)));
  try {
    const res = await fetch(`https://api.betterlyrics.org/getLyrics?${query}`, {
      headers: { "user-agent": "MyGo Music (https://github.com/egoist/mygo-music)" },
    });
    if (!res.ok) return { lines: [] };
    const answer = await res.json();
    return { lines: typeof answer?.ttml === "string" ? linesOfTTML(answer.ttml) : [] };
  } catch {
    return { lines: [] };
  }
}

const entities = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
const unescapeXML = s => s.replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (all, entity) => {
  if (entity[0] !== "#") return entities[entity] ?? all;
  const code = /^#x/i.test(entity) ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
  return code >= 0 && code <= 0x10ffff ? String.fromCodePoint(code) : all;
});
function clockOf(value) {
  if (!value) return null;
  const parts = value.trim().replace(/s$/, "").split(":");
  if (parts.length > 3 || parts.some(p => p === "" || !Number.isFinite(Number(p)) || Number(p) < 0)) return null;
  return Math.round(parts.reduce((sum, p) => sum * 60 + Number(p), 0) * 1000);
}
const attrOf = (attrs, name) => attrs.match(new RegExp(`\\s${name}=["']([^"']*)["']`))?.[1];
function linesOfTTML(ttml) {
  const lines = [];
  for (const [, pAttrs, body] of ttml.matchAll(/<p\b([^>]*)>([\s\S]*?)<\/p>/g)) {
    const words = [];
    let text = "", lead = "", word = null, depth = 0;
    for (const [, close, name, attrs, chars] of body.matchAll(/<(\/?)([\w:.-]+)([^>]*?)\/?>|([^<]+)/g)) {
      if (chars !== undefined) {
        const s = unescapeXML(chars);
        text += s;
        if (word) word.text += s;
        else if (words.length) words[words.length - 1].text += s;
        else lead += s;
        continue;
      }
      if (name !== "span") continue;
      if (close) {
        if (word && --depth === 0) { words.push(word); word = null; }
      } else if (word) {
        depth++;
      } else {
        const start = clockOf(attrOf(attrs, "begin"));
        if (start === null) continue;
        word = { start, end: clockOf(attrOf(attrs, "end")) ?? start, text: lead.trimStart() };
        lead = "";
        depth = 1;
      }
    }
    const start = clockOf(attrOf(pAttrs, "begin")) ?? words[0]?.start;
    const end = clockOf(attrOf(pAttrs, "end")) ?? words[words.length - 1]?.end ?? start;
    text = text.replace(/\s+/g, " ").trim();
    if (text && start != null) lines.push({ start, end, text, words: words.length ? words : undefined });
  }
  return lines;
}
export async function stream({ id }) {
  const data = await call("song", { filter:id });
  const item = list(data,"song")[0] || data;
  if (!item.url) throw fail("unavailable", "Ampache returned no audio URL");
  const url = new URL(item.url, config().url);
  if (!/^https?:$/.test(url.protocol)) throw fail("unavailable", "Unsupported audio URL");
  const type = String(item.stream_format || item.format || "").toLowerCase();
  return { url:url.href, ...(type ? {type} : {}), bitRate:Number(item.stream_bitrate || item.bitrate || 0) };
}
