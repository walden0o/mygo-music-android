// 网易云音乐, for personal use. The catalog is the API its website calls: most
// of it is plain, and what changes your library, or asks for it, goes as the
// website sends it, encrypted with a key made up here once rather than per
// request, as the service only takes the key back out of the RSA that carried
// it. A track plays from the address its player is handed, so nothing is
// converted: the app plays that as it is. A session, signed in by scanning a
// code the app shows with the phone, is what answers your library, and what
// makes VIP songs play whole rather than their first 45 seconds.

const site = "https://music.163.com";
const clientSite = "https://interfacepc.music.163.com";
const webUA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

// What its website says with every request, so the service answers as the
// desktop site does.
const clientCookie = "os=pc; appver=8.9.70";

function fail(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

// The session's cookies, as a Cookie header, kept in the plugin's storage.
const cookie = () => host.storage.get("cookie") || "";

function cookieValue(name) {
  for (const part of cookie().split(";")) {
    const at = part.indexOf("=");
    if (at > 0 && part.slice(0, at).trim() === name) return part.slice(at + 1).trim();
  }
  return "";
}

function needAccount() {
  if (!cookie()) throw fail("signIn", "请先登录网易云音乐：在设置 > 插件里点登录。");
}

function headers(extra) {
  return {
    "user-agent": webUA,
    referer: `${site}/`,
    origin: site,
    cookie: cookie() ? `${cookie()}; ${clientCookie}` : clientCookie,
    ...extra,
  };
}

// Reading an answer, and saying what its error means.

function check(body, what) {
  const code = body.code;
  if (code === undefined || code === 200) return body;
  if (code === 301) throw fail("signIn", "网易云音乐需要登录后再做这件事");
  const said = body.message || body.msg;
  if (said && said !== "系统错误") throw new Error(`${what}：${said}`);
  throw new Error(`${what}：网易云音乐说 ${code}`);
}

// The plain API the website uses for most of what it reads.
async function api(path, params) {
  const url = new URL(site + path);
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  const res = await fetch(url.href, { headers: headers() });
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return check(await res.json(), path);
}

// The encrypted half of it, for what the plain API won't do: liking, your
// library, playlists, comments, saving.

const presetKey = "0CoJUm6Qyw8W8jud";
const signKey = "mygomusicnetease";
const cbcIv = "0102030405060708";

// Its RSA of signKey, padded the way its website pads it and worked out once:
// the service doesn't care that the key is the same every time.
const encSecKey =
  "45444455c61d00fca7cbcefe0c02b432c6b5bba3596f088d887948034bdfaf041c51cd6d7796ac9a542e5f1456f11c47f9228ca81d71cf5bfd40756553fb531f3c1dda387a166ac5ea362b5b53cccdad1145689d1add3b0af61b17e1aea903c49dc0f8cb2a6e1f234f7df2a506063a31d123f3825220461ca6ef29141f9bf33e";

// AES-128-CBC, which the runtime doesn't have, in the small way its requests
// need: a block at a time, with the chaining around it.

const xtime = (x) => ((x << 1) ^ (x & 0x80 ? 0x1b : 0)) & 0xff;

// GF(2^8) as a log table, so the S-box is worked out instead of typed in:
// 256 numbers are easy to get one wrong.
const gfExp = new Uint8Array(512);
const gfLog = new Uint8Array(256);
{
  let field = 1;
  for (let i = 0; i < 255; i++) {
    gfExp[i] = field;
    gfLog[field] = i;
    field ^= xtime(field);
  }
  for (let i = 255; i < 512; i++) gfExp[i] = gfExp[i - 255];
}

const sbox = new Uint8Array(256);
for (let a = 0; a < 256; a++) {
  const inverse = a ? gfExp[255 - gfLog[a]] : 0;
  const rot = (n) => ((inverse << n) | (inverse >>> (8 - n))) & 0xff;
  sbox[a] = inverse ^ rot(1) ^ rot(2) ^ rot(3) ^ rot(4) ^ 0x63;
}

const mul = (a, b) => {
  let product = 0;
  for (let i = 0; i < 8; i++) {
    if (b & 1) product ^= a;
    a = xtime(a);
    b >>= 1;
  }
  return product;
};

function keySchedule(key) {
  const words = new Uint8Array(176);
  words.set(key);
  let round = 1;
  for (let at = 16; at < 176; at += 4) {
    let t = [words[at - 4], words[at - 3], words[at - 2], words[at - 1]];
    if (at % 16 === 0) {
      t = [sbox[t[1]] ^ round, sbox[t[2]], sbox[t[3]], sbox[t[0]]];
      round = xtime(round);
    }
    for (let i = 0; i < 4; i++) words[at + i] = words[at - 16 + i] ^ t[i];
  }
  return words;
}

function encryptBlock(schedule, input) {
  const s = new Uint8Array(input);
  for (let i = 0; i < 16; i++) s[i] ^= schedule[i];
  for (let at = 1; at <= 10; at++) {
    for (let i = 0; i < 16; i++) s[i] = sbox[s[i]];
    // Rows shift left by their number, the state being column by column.
    for (let row = 1; row < 4; row++) {
      const line = [s[row], s[4 + row], s[8 + row], s[12 + row]];
      for (let col = 0; col < 4; col++) s[4 * col + row] = line[(col + row) % 4];
    }
    if (at !== 10) {
      for (let col = 0; col < 4; col++) {
        const [a0, a1, a2, a3] = [s[4 * col], s[4 * col + 1], s[4 * col + 2], s[4 * col + 3]];
        s[4 * col] = mul(a0, 2) ^ mul(a1, 3) ^ a2 ^ a3;
        s[4 * col + 1] = a0 ^ mul(a1, 2) ^ mul(a2, 3) ^ a3;
        s[4 * col + 2] = a0 ^ a1 ^ mul(a2, 2) ^ mul(a3, 3);
        s[4 * col + 3] = mul(a0, 3) ^ a1 ^ a2 ^ mul(a3, 2);
      }
    }
    for (let i = 0; i < 16; i++) s[i] ^= schedule[at * 16 + i];
  }
  return s;
}

const base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function toBase64(bytes) {
  let out = "";
  for (let at = 0; at < bytes.length; at += 3) {
    const a = bytes[at];
    const b = at + 1 < bytes.length ? bytes[at + 1] : 0;
    const c = at + 2 < bytes.length ? bytes[at + 2] : 0;
    const n = (a << 16) | (b << 8) | c;
    out += base64Alphabet[(n >> 18) & 63] + base64Alphabet[(n >> 12) & 63];
    out += at + 1 < bytes.length ? base64Alphabet[(n >> 6) & 63] : "=";
    out += at + 2 < bytes.length ? base64Alphabet[n & 63] : "=";
  }
  return out;
}

const utf8 = new TextEncoder();

function aesCbc(key, text) {
  const schedule = keySchedule(utf8.encode(key));
  const bytes = [...utf8.encode(text)];
  const pad = 16 - (bytes.length % 16);
  for (let i = 0; i < pad; i++) bytes.push(pad);
  let previous = utf8.encode(cbcIv);
  const out = [];
  for (let at = 0; at < bytes.length; at += 16) {
    const block = bytes.slice(at, at + 16).map((b, i) => b ^ previous[i]);
    previous = encryptBlock(schedule, block);
    for (const b of previous) out.push(b);
  }
  return toBase64(new Uint8Array(out));
}

const paramsOf = (payload) => aesCbc(signKey, aesCbc(presetKey, JSON.stringify(payload)));

async function weapi(path, payload) {
  const body = new URLSearchParams();
  body.set("params", paramsOf({ csrf_token: cookieValue("__csrf"), ...payload }));
  body.set("encSecKey", encSecKey);
  const res = await fetch(`${site}${path}`, {
    method: "POST",
    headers: headers({ "content-type": "application/x-www-form-urlencoded" }),
    body: body.toString(),
  });
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return check(await res.json(), path);
}

// The same request, keeping the answer's headers: signing in answers with the
// session as cookies, and with codes of its own rather than an error.
async function weapiAnswer(path, payload, session) {
  const body = new URLSearchParams();
  body.set("params", paramsOf({ csrf_token: "", ...payload }));
  body.set("encSecKey", encSecKey);
  const res = await fetch(`${site}${path}`, {
    method: "POST",
    headers: {
      "user-agent": webUA,
      referer: `${site}/`,
      origin: site,
      cookie: session ? `${session}; ${clientCookie}` : clientCookie,
      "content-type": "application/x-www-form-urlencoded",
    },
    body: body.toString(),
  });
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return { res, body: await res.json() };
}

// The cookies a response sets, by name, as a browser would keep them. The
// separate lines are read where the runtime keeps them; a response that folds
// them into one header is taken apart at the commas a cookie starts after.
function keepCookies(res, jar) {
  let lines = [];
  if (typeof res.headers.getSetCookie === "function") lines = res.headers.getSetCookie() || [];
  if (!lines.length) {
    lines = String(res.headers.get("set-cookie") || "").split(/,(?=\s*[A-Za-z0-9_-]+=)/);
  }
  for (const line of lines) {
    const pair = String(line).split(";")[0].trim();
    const at = pair.indexOf("=");
    if (at > 0) jar[pair.slice(0, at).trim()] = pair.slice(at + 1).trim();
  }
}

const cookiesAsHeader = (jar) => Object.entries(jar).map(([name, value]) => `${name}=${value}`).join("; ");

// The other form, eapi, which its apps use for what the website's encryption
// no longer gets through: AES-ECB once, in hex, with an MD5 of the text
// inside, and the caller's fields in the payload as `header`.

const apiUA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
const eapiKey = "e82ckenh8dichen8";

// Its anti-cheat token, which saving a playlist has to carry.
const checkToken =
  "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d274a790ac8ff54a97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e839a9ea7c14e909da883e83fb692a3abdb6b92adee9e";

// The device the service thinks these come from, kept for as long as the
// plugin is installed.
let device = null;

function deviceId() {
  if (device) return device;
  device = host.storage.get("deviceId") || "";
  if (!device) {
    for (let i = 0; i < 32; i++) device += "0123456789abcdef"[Math.floor(Math.random() * 16)];
    host.storage.set("deviceId", device);
  }
  return device;
}

function eapiHeader() {
  return {
    osver: "Microsoft-Windows-10-Professional-build-19045-64bit",
    deviceId: deviceId(),
    os: "pc",
    appver: "3.1.17.204416",
    versioncode: "140",
    mobilename: "",
    buildver: String(Date.now()).slice(0, 10),
    resolution: "1920x1080",
    __csrf: cookieValue("__csrf"),
    channel: "netease",
    requestId: `${Date.now()}_${String(Math.floor(Math.random() * 1000)).padStart(4, "0")}`,
  };
}

async function eapi(uri, payload, antiCheat) {
  const header = eapiHeader();
  if (antiCheat) header["X-antiCheatToken"] = checkToken;
  // The client sends its session in both the encrypted header and Cookie.
  for (const name of ["MUSIC_U", "MUSIC_A", "NMTID"]) {
    const value = cookieValue(name);
    if (value) header[name] = value;
  }
  const text = JSON.stringify({ ...payload, header, e_r: false });
  const digest = host.crypto.md5(`nobody${uri}use${text}md5forencrypt`);
  const params = host.crypto.aesEcbEncrypt(eapiKey, `${uri}-36cd479b6b5-${text}-36cd479b6b5-${digest}`).toUpperCase();
  const res = await fetch(`${clientSite}/eapi/${uri.slice(5)}`, {
    method: "POST",
    redirect: "error",
    headers: {
      "user-agent": apiUA,
      "content-type": "application/x-www-form-urlencoded",
      cookie: Object.entries(header)
        .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
        .join("; "),
    },
    body: `params=${encodeURIComponent(params)}`,
  });
  if (!res.ok) throw new Error(`${uri}: ${res.status}`);
  return check(await res.json(), uri);
}

// Answers, as the app knows them.

const dateOf = (ms) => {
  if (!ms) return undefined;
  // The service dates things in China's day, which is ours eight hours on.
  return new Date(Number(ms) + 8 * 3600 * 1000).toISOString().slice(0, 10);
};

// Covers come over http from the service, which serves them over https too.
const pictureOf = (url) => (url ? String(url).replace(/^http:\/\//, "https://") : undefined);

const artistOf = (a) => ({
  id: String(a.id ?? ""),
  name: a.name || "",
  imageUrl: pictureOf(a.picUrl),
});

const albumOf = (a) =>
  a ? { id: String(a.id ?? ""), name: a.name || "", imageUrl: pictureOf(a.picUrl) } : { id: "", name: "" };

function albumEntryOf(a) {
  return {
    id: String(a.id ?? ""),
    name: a.name || "",
    imageUrl: pictureOf(a.picUrl || a.blurPicUrl),
    artists: (a.artists || (a.artist ? [a.artist] : [])).map(artistOf),
    released: dateOf(a.publishTime),
  };
}

// A track whose copyright is gone is still listed, greyed out, as its website
// lists it.
function unavailableOf(s) {
  // A catalog restriction does not rule out the listener's uploaded copy.
  if (s.pc) return false;
  return Boolean(s.st < 0 || s.status < 0 || s.noCopyrightRcmd || (s.privilege && s.privilege.st < 0));
}

function trackOf(s) {
  if (!s || !s.id) return null;
  return {
    id: String(s.id),
    name: s.name || "",
    artists: (s.ar || s.artists || []).map(artistOf),
    album: albumOf(s.al || s.album),
    duration: Number(s.dt || s.duration || 0),
    url: `${site}/song?id=${s.id}`,
    unavailable: unavailableOf(s) || undefined,
  };
}

const tracksOf = (list) => (list || []).map(trackOf).filter(Boolean);

// Who is signed in, if anyone: the app asks this to know whether to offer it.

let me = null;
let meAt = 0;
let meId = "";

async function current() {
  if (!cookie()) return null;
  if (me && Date.now() - meAt < 5 * 60 * 1000) return me;
  let r;
  try {
    r = await api("/api/nuser/account/get");
  } catch (e) {
    // A session the service turned down is no session: what asked for it can
    // ask the listener to sign in again.
    if (e.code === "signIn") {
      endSession();
      return null;
    }
    throw e;
  }
  const profile = r.profile;
  if (!profile) return null;
  me = {
    id: String(profile.userId),
    name: profile.nickname || (r.account && r.account.userName) || "",
    imageUrl: pictureOf(profile.avatarUrl),
  };
  meId = me.id;
  meAt = Date.now();
  return me;
}

export async function account() {
  return current();
}

// Signing in is a code the app draws, which the phone scans: the service hands
// out a key, the page that carries the key is the code, and the key is asked
// after until the phone says yes, the answer to that being the session.

// A sign-in started again leaves the one before it to stop: its answers would
// be about a code no dialog shows anymore.
let signInRun = 0;

const wait = (ms) => new Promise((done) => setTimeout(done, ms));

export async function signIn() {
  const run = ++signInRun;
  host.signInPrompt({ text: "正在获取登录二维码…" });
  let asked;
  try {
    asked = await weapiAnswer("/weapi/login/qrcode/unikey", { type: 1 });
  } catch (e) {
    throw fail("signIn", `拿不到登录二维码，请稍后再试：${e.message}`);
  }
  const key = asked.body.unikey;
  if (!key) throw fail("signIn", "网易云音乐没有给出二维码，请再试一次。");
  if (run !== signInRun) return null;
  const qr = `${site}/login?codekey=${encodeURIComponent(key)}`;
  host.signInPrompt({ qr, text: "用网易云音乐手机 App 扫描二维码，然后在手机上确认登录。" });
  const jar = {};
  keepCookies(asked.res, jar);
  const deadline = Date.now() + 5 * 60 * 1000;
  let scanned = false;
  while (Date.now() < deadline && run === signInRun) {
    await wait(2000);
    if (run !== signInRun) return null;
    let answer;
    try {
      answer = await weapiAnswer("/weapi/login/qrcode/client/login", { key, type: 1 }, cookiesAsHeader(jar));
    } catch (e) {
      // A poll the network dropped says nothing of the code: the next one asks
      // after the same key.
      console.warn(`网易云音乐的登录二维码: ${e.message}`);
      continue;
    }
    keepCookies(answer.res, jar);
    if (answer.body.code === 800) throw fail("signIn", "二维码已过期，请重新登录。");
    if (answer.body.code === 802 && !scanned) {
      scanned = true;
      host.signInPrompt({ qr, text: "已经扫码，请在手机上确认登录。" });
    }
    if (answer.body.code === 803) {
      const session = cookiesAsHeader(jar);
      if (!/(^|;\s*)MUSIC_U=/.test(session)) throw fail("signIn", "网易云音乐没有给出登录会话，请重新扫码。");
      host.storage.set("cookie", session);
      me = null;
      meAt = 0;
      meId = "";
      const who = await current();
      if (!who) {
        endSession();
        throw new Error("网易云音乐没有接受这次登录，请再试一次。");
      }
      return who;
    }
  }
  if (run !== signInRun) return null;
  throw fail("signIn", "登录超时，请重新扫码。");
}

export function signOut() {
  endSession();
}

function endSession() {
  host.storage.remove("cookie");
  me = null;
  meAt = 0;
  meId = "";
  likedCache = null;
  savedAlbumCache = null;
}

// Pages.

export async function home() {
  const [daily, mixes, fresh, charts] = await Promise.all([
    quietly(dailySongs(), []),
    quietly(recommendedPlaylists(), []),
    quietly(newSongs(), []),
    quietly(chartPlaylists(8), []),
  ]);
  const sections = [];
  // Signed out, its daily list is still a list of songs worth starting on.
  if (daily.length) sections.push({ title: cookie() ? "每日推荐" : "今日推荐", tracks: daily, morePage: "daily" });
  if (mixes.length) sections.push({ title: "推荐歌单", playlists: mixes });
  if (fresh.length) sections.push({ title: "新歌速递", tracks: fresh });
  if (charts.length) sections.push({ title: "排行榜", playlists: charts, morePage: "charts" });
  return { title: "网易云音乐", sections };
}

async function quietly(work, fallback) {
  try {
    return await work;
  } catch (e) {
    console.warn(`网易云音乐: ${e.message}`);
    return fallback;
  }
}

async function dailySongs() {
  const r = await weapi("/weapi/v3/discovery/recommend/songs", {});
  return tracksOf((r.data && (r.data.dailySongs || r.data.recommend)) || r.recommend);
}

async function recommendedPlaylists() {
  const r = await api("/api/personalized/playlist", { limit: 12 });
  return (r.result || []).map(playlistOf);
}

async function newSongs() {
  const r = await api("/api/personalized/newsong", { type: "recommend" });
  return (r.result || [])
    .map((item) => {
      const track = trackOf(item.song || item);
      if (track && item.picUrl && !track.album.imageUrl) track.album = { ...track.album, imageUrl: pictureOf(item.picUrl) };
      return track;
    })
    .filter(Boolean);
}

// A chart can come without a cover, as 荣耀音乐热歌榜 does once signed in: the
// chart's own page still has one, so that is asked for, and kept a while.
const chartCovers = new Map();

async function chartCoverOf(id) {
  const kept = chartCovers.get(id);
  if (kept && Date.now() - kept.at < 10 * 60 * 1000) return kept.url;
  const r = await quietly(api("/api/v6/playlist/detail", { id, n: 1 }), null);
  const url = pictureOf((r && r.playlist && r.playlist.coverImgUrl) || "") || "";
  chartCovers.set(id, { url, at: Date.now() });
  return url;
}

async function chartPlaylists(limit) {
  const r = await api("/api/toplist/detail");
  const charts = (r.list || []).slice(0, limit).map((l) => ({
    id: String(l.id),
    name: l.name || "",
    imageUrl: pictureOf(l.coverImgUrl),
    trackCount: l.trackCount || undefined,
    description: l.updateFrequency || undefined,
  }));
  for (const chart of charts) {
    if (!chart.imageUrl) chart.imageUrl = await chartCoverOf(chart.id);
  }
  return charts;
}

async function newAlbums(limit) {
  const r = await api("/api/album/new", { area: "ALL", limit, offset: 0 });
  return (r.albums || []).map(albumEntryOf);
}

export async function browse({ page }) {
  if (page === "charts") {
    const charts = await chartPlaylists();
    return {
      title: "排行榜",
      subtitle: "网易云音乐的榜单，跟着它们更新",
      sections: [{ title: "全部榜单", playlists: charts }],
    };
  }
  if (page === "new") {
    const [songs, albums] = await Promise.all([newSongs(), newAlbums(24)]);
    const sections = [];
    if (songs.length) sections.push({ title: "新歌速递", tracks: songs });
    if (albums.length) sections.push({ title: "新碟上架", albums });
    return { title: "新歌新碟", sections };
  }
  if (page === "daily") {
    const songs = await dailySongs();
    return { title: cookie() ? "每日推荐" : "今日推荐", subtitle: "今天为你挑的歌", sections: [{ tracks: songs }] };
  }
  if (page === "playlists") return playlistSquare();
  if (page === "podcasts") return podcastSquare();
  if (page === "my-podcasts" || /^my-podcasts:(created|purchased|liked)(:\d+)?$/.test(page) || /^my-podcasts:subscribed:\d+$/.test(page)) return myPodcasts(page);
  if (page === "cloud" || /^cloud:\d+$/.test(page)) return cloudPage(Number(page.split(":")[1] || 0));
  throw fail("unavailable", `没有这个页面：${page}`);
}

async function playlistSquare() {
  const r = await api("/api/playlist/list", { cat: "全部", order: "hot", limit: 30, offset: 0 });
  return {
    title: "歌单广场",
    sections: [{ title: "热门歌单", playlists: (r.playlists || []).map(playlistOf) }],
  };
}

// Podcasts use program IDs for their pages and comments; audio has a separate song ID.
const programIdOf = (id) => /^dj:(\d+)$/.exec(String(id))?.[1];

function showOf(radio) {
  return {
    id: String(radio.id || radio.voiceListId),
    name: radio.name || radio.voiceListName || "",
    imageUrl: pictureOf(radio.picUrl || radio.coverUrl),
    description: radio.desc || radio.rcmdtext || radio.copywriter || undefined,
    publisher: radio.dj?.nickname || radio.userName || undefined,
    episodeCount: radio.programCount || radio.voiceCount || 0,
    subscribed: Boolean(radio.subed),
  };
}

function episodeOf(program) {
  const song = program.mainSong || {};
  const radio = program.radio || {};
  return {
    id: `dj:${program.id}`,
    name: program.name || song.name || "",
    artists: [{ id: "", name: program.dj?.nickname || radio.dj?.nickname || "" }],
    album: { id: "", name: radio.name || "", imageUrl: pictureOf(program.coverUrl || radio.picUrl) },
    duration: Number(program.duration || song.duration || song.dt || 0),
    showId: String(radio.id || ""),
    description: program.description || program.programDesc || undefined,
    published: dateOf(program.createTime),
    url: `${site}/program?id=${program.id}`,
    unavailable: !song.id || song.status < 0 || song.st < 0 || undefined,
  };
}

async function podcastSquare() {
  const [recommended, hot] = await Promise.all([
    eapi("/api/djradio/recommend/v1", {}),
    eapi("/api/djradio/hot/v1", { limit: 24, offset: 0 }),
  ]);
  return {
    title: "播客",
    sections: [
      { title: "推荐播客", shows: (recommended.djRadios || []).map(showOf) },
      { title: "热门播客", shows: (hot.djRadios || []).map(showOf) },
    ],
  };
}

export async function shows() {
  needAccount();
  const out = [];
  for (let offset = 0; ;) {
    const r = await eapi("/api/djradio/get/subed", { limit: 100, offset, total: true });
    const radios = r.djRadios || [];
    out.push(...radios.map((radio) => ({ ...showOf(radio), subscribed: true })));
    if (!r.hasMore || !radios.length) return out;
    offset += radios.length;
  }
}

export async function show({ id, offset = 0 }) {
  const [detail, episodes] = await Promise.all([
    eapi("/api/djradio/get", { id: Number(id) }),
    eapi("/api/dj/program/byradio", { radioId: Number(id), limit: 30, offset, asc: false }),
  ]);
  if (!detail.djRadio) throw fail("unavailable", "这个播客已不可用。");
  return {
    show: showOf(detail.djRadio),
    episodes: (episodes.programs || []).map(episodeOf),
    more: Boolean(episodes.more),
  };
}

export async function subscribeShow({ id, on }) {
  needAccount();
  await eapi("/api/djradio/sub", { id: Number(id), sub: Boolean(on) });
}

// These are the desktop client's four library tabs, not discovery categories.
async function myPodcasts(page) {
  needAccount();
  const who = await current();
  if (!who) throw fail("signIn", "请先登录网易云音乐。");
  const [, tab = "subscribed", cursor = "0"] = page.split(":");
  const offset = Number(cursor), limit = 100;
  if (!Number.isSafeInteger(offset) || offset < 0) throw fail("unavailable", "无效的播客页码。");
  const purchased = eapi("/api/voicelist/bought/list", { limit: tab === "purchased" ? limit : 1, offset: tab === "purchased" ? offset : 0 });
  const [counts, likedCount, bought] = await Promise.all([
    quietly(eapi("/api/djradio/get/create/sub/count", { userId: Number(who.id) }), {}),
    quietly(eapi("/api/content/my/liked/voice/count", {}), {}),
    tab === "purchased" ? purchased : quietly(purchased, {}),
  ]);
  let entries, section, more;
  if (tab === "subscribed" || tab === "created") {
    const r = await eapi(tab === "created" ? "/api/djradio/get/byuser/v1" : "/api/djradio/get/subed", {
      userId: Number(who.id), limit, offset, total: true,
    });
    entries = r.djRadios || [];
    section = { shows: entries.map(radio => ({ ...showOf(radio), subscribed: tab === "subscribed" || Boolean(radio.subed) })) };
    more = r.hasMore;
  } else if (tab === "liked") {
    const r = await eapi("/api/content/my/liked/voice", { limit, offset });
    entries = r.data?.data || [];
    section = { tracks: entries.map(voiceOf) };
    more = r.data?.hasMore;
  } else {
    entries = bought.data?.list || [];
    section = {
      shows: entries.filter(item => item.productType !== 1).map(showOf),
      tracks: entries.filter(item => item.productType === 1).map(voiceOf),
    };
    more = bought.data?.hasMore;
  }
  const labels = [
    ["subscribed", "收藏播客", counts.subRadioCount],
    ["created", "创建播客", counts.createRadioCount],
    ["purchased", "购买播客", bought.data?.count],
    ["liked", "喜欢的声音", likedCount.data],
  ];
  const pageOf = (kind, at = 0) => kind === "subscribed" && at === 0 ? "my-podcasts" : `my-podcasts:${kind}${at ? `:${at}` : ""}`;
  const links = [];
  if (offset > 0) links.push({ title: "上一页", page: pageOf(tab, Math.max(0, offset - limit)) });
  if (more && entries.length) links.push({ title: "下一页", page: pageOf(tab, offset + entries.length) });
  return {
    title: "我的播客",
    navigationPage: "my-podcasts",
    tabs: labels.map(([kind, title, count]) => ({ title: `${title}${typeof count === "number" ? ` ${count}` : ""}`, page: pageOf(kind), selected: kind === tab })),
    sections: [entries.length ? section : { title: { subscribed: "还没有收藏的播客", created: "还没有创建的播客", purchased: "还没有购买的播客", liked: "还没有喜欢的声音" }[tab] }, ...(links.length ? [{ links }] : [])],
  };
}

function voiceOf(voice) {
  return episodeOf({ ...voice, id: voice.voiceId || voice.id, name: voice.voiceName || voice.name,
    createTime: voice.publishTime || voice.createTime, coverUrl: voice.coverUrl,
    dj: voice.dj || { nickname: voice.userName },
    mainSong: voice.mainSong || { id: voice.trackId, duration: voice.duration },
  });
}

async function cloudPage(offset) {
  needAccount();
  if (!Number.isSafeInteger(offset) || offset < 0) throw fail("unavailable", "无效的云盘页码。");
  const limit = 200;
  const r = await eapi("/api/v1/cloud/get", { limit, offset });
  const entries = r.data || [];
  const tracks = entries.map((entry) => {
    const song = entry.simpleSong || {};
    return trackOf({
      ...song,
      id: entry.songId || song.id,
      name: entry.songName || song.name || entry.fileName,
      ar: song.ar?.length ? song.ar : [{ name: entry.artist || "" }],
      al: song.al || { name: entry.album || "" },
      pc: entry,
    });
  }).filter(Boolean);
  const links = [];
  if (offset > 0) links.push({ title: "上一页", page: `cloud:${Math.max(0, offset - limit)}` });
  if (r.hasMore && entries.length) links.push({ title: "下一页", page: `cloud:${offset + entries.length}` });
  return {
    title: "音乐云盘",
    subtitle: `共 ${r.count || 0} 首已上传的音乐`,
    upload: true,
    sections: [{ tracks }, ...(links.length ? [{ links }] : [])],
  };
}

// The host streams only the file selected in its native picker, without loading it into JavaScript.
export async function upload({ file }) {
  needAccount();
  if (!host.uploadFile || !host.uploadCheckpoint) throw fail("unsupported", "当前播放器没有提供云盘上传能力。");
  const mimeTypes = { mp3: "audio/mpeg", aac: "audio/aac", wma: "audio/x-ms-wma", wav: "audio/wav", ogg: "audio/ogg", m4a: "audio/mp4", ape: "audio/x-ape", flac: "audio/flac" };
  const ext = file?.name?.split(".").pop().toLowerCase();
  if (!file || !/^[a-f0-9]{32}$/i.test(file.md5) || !Number.isSafeInteger(file.size) || file.size <= 0 || !Object.hasOwn(mimeTypes, ext)) {
    throw fail("unavailable", "请选择有效的音乐文件。");
  }
  const checkpoint = () => host.uploadCheckpoint(file.id);
  checkpoint();
  const check = await eapi("/api/cloud/upload/check", { bitrate: "999000", ext: "", length: file.size, md5: file.md5, songId: "0", version: 1 });
  checkpoint();
  const bucket = "jd-musicrep-privatecloud-audio-public";
  const tokenReply = await eapi("/api/nos/token/alloc", {
    bucket, ext, filename: file.name.replace(/\.[^.]+$/, ""),
    local: false, nos_product: 3, type: "audio", md5: file.md5,
  });
  const token = tokenReply.result;
  if (!token?.resourceId || !token.objectKey || !token.token) throw new Error("未能取得云盘上传凭据，请重试。");
  checkpoint();
  if (check.needUpload) {
    const response = await fetch(`https://wanproxy.127.net/lbs?version=1.0&bucketname=${bucket}`, { redirect: "error" });
    if (!response.ok) throw new Error("无法获取云盘上传服务器。");
    const lbs = await response.json();
    const server = String(lbs.upload?.[0] || "").replace(/^http:/, "https:");
    // NOS tokens and selected file bytes must stay on the official upload service.
    if (!/^https:\/\/[a-z0-9.-]+\.127\.net(?::443)?\/?$/i.test(server)) throw new Error("网易云返回了无效的上传服务器。");
    checkpoint();
    const result = await host.uploadFile({ id: file.id,
      url: `${server.replace(/\/$/, "")}/${bucket}/${encodeURIComponent(token.objectKey)}?offset=0&complete=true&version=1.0`,
      headers: { "x-nos-token": token.token, "Content-MD5": file.md5, "Content-Type": mimeTypes[ext] },
    });
    if (typeof result?.offset === "number" && result.offset !== file.size) throw new Error("音频没有完整上传，请重试。");
  }
  checkpoint();
  const info = await eapi("/api/upload/cloud/info/v2", {
    md5: file.md5, songid: check.songId, filename: file.name,
    song: file.title || file.name.replace(/\.[^.]+$/, ""), album: file.album || "未知专辑", artist: file.artist || "未知艺术家",
    bitrate: "999000", resourceId: token.resourceId,
  });
  if (!info.songId) throw new Error("网易云未能保存歌曲信息，请重试。");
  checkpoint();
  await eapi("/api/cloud/pub/v2", { songid: info.songId });
}

// Search. Each kind has its type, which the service numbers rather than names.

const searchTypes = { tracks: 1, albums: 10, artists: 100, playlists: 1000, shows: 1009 };
const searchPage = 30;

export async function search({ query, kind, offset }) {
  const type = searchTypes[kind];
  if (!type) return { [kind]: [], more: false };
  const path = kind === "tracks" ? "/api/cloudsearch/pc" : "/api/search/get";
  const r = await api(path, { s: query, type, limit: searchPage, offset });
  const result = r.result || {};
  const out = { more: false };
  if (kind === "tracks") {
    out.tracks = tracksOf(result.songs);
    out.more = offset + out.tracks.length < (result.songCount || 0);
  } else if (kind === "albums") {
    out.albums = (result.albums || []).map(albumEntryOf);
    out.more = offset + out.albums.length < (result.albumCount || 0);
  } else if (kind === "artists") {
    out.artists = (result.artists || []).map(artistOf);
    out.more = offset + out.artists.length < (result.artistCount || 0);
  } else if (kind === "shows") {
    out.shows = (result.djRadios || []).map(showOf);
    out.more = offset + out.shows.length < (result.djRadiosCount || 0);
  } else {
    out.playlists = (result.playlists || []).map(playlistOf);
    out.more = offset + out.playlists.length < (result.playlistCount || 0);
  }
  return out;
}

export async function suggest({ query }) {
  if (!query) return [];
  try {
    const r = await api("/api/search/suggest/web", { csrf_token: "", s: query });
    const result = r.result || {};
    const out = [];
    for (const kind of result.order || ["songs", "artists", "albums", "playlists"]) {
      for (const item of result[kind] || []) {
        const name = item.name || item.keyword;
        if (name && !out.includes(name)) out.push(name);
        if (out.length >= 8) return out;
      }
    }
    return out;
  } catch (e) {
    const r = await api("/api/search/suggest/keyword", { s: query });
    return (r.result && r.result.allMatch ? r.result.allMatch : []).map((m) => m.keyword).filter(Boolean).slice(0, 8);
  }
}

// Albums and artists.

export async function album({ id }) {
  const [r, saved] = await Promise.all([api(`/api/v1/album/${id}`), savedAlbumIds()]);
  const a = r.album || {};
  return {
    album: { ...albumEntryOf(a), saved: saved.has(String(id)) || undefined },
    tracks: tracksOf(r.songs),
  };
}

export async function artist({ id }) {
  const [detail, albums] = await Promise.all([
    api(`/api/v1/artist/${id}`),
    api(`/api/artist/albums/${id}`, { limit: 12, offset: 0 }),
  ]);
  const a = detail.artist || {};
  return {
    artist: {
      id: String(a.id ?? id),
      name: a.name || "",
      imageUrl: pictureOf(a.picUrl),
      following: Boolean(a.followed) || undefined,
    },
    tracks: tracksOf(detail.hotSongs).slice(0, 20),
    albums: (albums.hotAlbums || []).map(albumEntryOf),
  };
}

// Playlists and the library.

function playlistOf(p) {
  const creator = p.creator || {};
  const ownerId = String(p.userId ?? creator.userId ?? "");
  return {
    id: String(p.id),
    name: p.name || "",
    imageUrl: pictureOf(p.coverImgUrl || p.picUrl),
    description: p.description || undefined,
    owner: creator.nickname || undefined,
    trackCount: p.trackCount || undefined,
    mine: Boolean(meId && ownerId && ownerId === meId) || undefined,
    saved: p.subscribed === true || undefined,
  };
}

// A playlist comes with its first thousand tracks; the rest are asked for by
// id, and the playlist's own order is put back over both.

async function inOrder(playlist) {
  const tracks = tracksOf(playlist.tracks);
  const ids = (playlist.trackIds || []).map((t) => String(t.id));
  if (!ids.length || ids.length <= tracks.length) return tracks;
  const known = new Map(tracks.map((t) => [t.id, t]));
  const missing = ids.filter((id) => !known.has(id));
  for (const track of await songsById(missing.slice(0, 5000))) known.set(track.id, track);
  return ids.map((id) => known.get(id)).filter(Boolean);
}

async function songsById(ids) {
  const out = [];
  const numbers = ids.map(Number).filter((id) => Number.isFinite(id));
  for (let at = 0; at < numbers.length; at += 500) {
    const c = JSON.stringify(numbers.slice(at, at + 500).map((id) => ({ id })));
    const r = await api("/api/v3/song/detail", { c });
    out.push(...tracksOf(r.songs));
  }
  const programs = ids.map(programIdOf).filter(Boolean);
  for (let at = 0; at < programs.length; at += 5) {
    const replies = await Promise.all(programs.slice(at, at + 5).map((id) => eapi("/api/dj/program/detail", { id: Number(id) })));
    for (const reply of replies) if (reply.program) out.push(episodeOf(reply.program));
  }
  return out;
}

export async function playlist({ id }) {
  // Which playlists are the listener's own is known once the account is.
  if (cookie() && !meId) await quietly(current(), null);
  const r = await api("/api/v6/playlist/detail", { id, n: 1000 });
  const p = r.playlist || {};
  return { ...playlistOf(p), tracks: await inOrder(p) };
}

// The app loads this for its sidebar as it starts: no account is no playlists,
// rather than asking someone who's only browsing to sign in.
export async function playlists() {
  if (!cookie()) return [];
  const who = await current();
  if (!who) return [];
  const r = await api("/api/user/playlist", { uid: who.id, limit: 1000, offset: 0 });
  // Liked Music has a page of its own, and its entries are liked songs.
  return (r.playlist || []).filter((p) => p.specialType !== 5).map(playlistOf);
}

// What the listener liked, which the service answers as ids alone.

let likedCache = null;
let likedAt = 0;
const likedFor = 5 * 60 * 1000;

async function likedIds() {
  if (!cookie()) return [];
  if (likedCache && Date.now() - likedAt < likedFor) return likedCache;
  const who = await current();
  if (!who) throw fail("signIn", "登录已经失效，请重新登录。");
  const r = await eapi("/api/song/like/get", { uid: Number(who.id) });
  const ids = (r.ids || (r.data && r.data.ids) || []).map(String);
  const ordered = await quietly((async () => {
    const lists = await api("/api/user/playlist", { uid: who.id, limit: 1000, offset: 0 });
    const liked = (lists.playlist || []).find((p) => p.specialType === 5);
    if (!liked) return [];
    const detail = await api("/api/v6/playlist/detail", { id: liked.id, n: 1 });
    return ((detail.playlist && detail.playlist.trackIds) || []).map((track) => String(track.id));
  })(), []);
  const known = new Set(ids);
  likedCache = [...ordered.filter((id) => known.delete(id)), ...ids.filter((id) => known.delete(id))];
  likedAt = Date.now();
  return likedCache;
}

export async function liked({ offset }) {
  needAccount();
  const ids = await likedIds();
  const page = ids.slice(offset, offset + 50);
  const byId = new Map((await songsById(page)).map((t) => [t.id, t]));
  return { tracks: page.map((id) => byId.get(id)).filter(Boolean), more: offset + page.length < ids.length };
}

export async function isLiked({ ids }) {
  const set = new Set(cookie() ? await likedIds() : []);
  return Object.fromEntries(ids.map((id) => [String(id), set.has(String(id))]));
}

export async function like({ id, on }) {
  needAccount();
  await eapi("/api/radio/like", { alg: "itembased", trackId: Number(id), like: Boolean(on), time: "3" });
  if (likedCache) {
    const key = String(id);
    likedCache = on ? [key, ...likedCache.filter((x) => x !== key)] : likedCache.filter((x) => x !== key);
  }
}

// What was played: the app's own list of it, and the service's when signed in.

export function played({ id }) {
  if (!programIdOf(id)) host.storage.set("lastPlayed", id);
  const stored = host.storage.get("recent");
  const list = (Array.isArray(stored) ? stored : []).filter((x) => x !== id);
  list.unshift(id);
  host.storage.set("recent", list.slice(0, 100));
}

export async function recent() {
  const stored = host.storage.get("recent");
  const local = (Array.isArray(stored) ? stored : []).map(String);
  const out = [];
  const seen = new Set();
  if (local.length) {
    const lately = local.slice(0, 60);
    const byId = new Map((await quietly(songsById(lately), [])).map((t) => [t.id, t]));
    for (const id of lately) {
      const track = byId.get(id);
      if (track && !seen.has(track.id)) {
        seen.add(track.id);
        out.push(track);
      }
    }
  }
  if (cookie()) {
    try {
      const who = await current();
      const r = await weapi("/weapi/v1/play/record", { uid: Number(who.id), type: 1 });
      for (const item of [...(r.allData || []), ...(r.weekData || [])]) {
        if (out.length >= 100) break;
        const track = item.song ? trackOf(item.song) : null;
        if (track && !seen.has(track.id)) {
          seen.add(track.id);
          out.push(track);
        }
      }
    } catch (e) {
      console.warn(`网易云音乐的播放记录: ${e.message}`);
    }
  }
  return out.slice(0, 100);
}

// Radio: what the service thinks follows from what plays, and the songs most
// like it, for when it has nothing to go on.

export function stations() {
  return [
    { id: "radio", name: "私人漫游", description: "按你常听的歌，一直播下去。" },
    { id: "similar", name: "相似歌曲", description: "接着上一首，播和它像的歌。" },
  ];
}

export async function stationNext({ id }) {
  if (id === "radio" && cookie()) {
    try {
      const r = await weapi("/weapi/v1/radio/get", {});
      const songs = tracksOf(r.data).slice(0, 30);
      if (songs.length >= 5) return songs;
      const seed = songs.length ? songs[songs.length - 1].id : await seedOf();
      return [...songs, ...(await similarTo(seed, 30 - songs.length))];
    } catch (e) {
      console.warn(`私人漫游: ${e.message}`);
    }
  }
  return similarTo(await seedOf(), 30);
}

async function seedOf() {
  const last = host.storage.get("lastPlayed");
  if (last) return String(last);
  const songs = await quietly(newSongs(), []);
  if (!songs.length) return "";
  return songs[Math.floor(Math.random() * songs.length)].id;
}

async function similarTo(seed, limit) {
  if (!seed) return [];
  const r = await api("/api/discovery/simiSong", { songid: seed, limit: 50 });
  return tracksOf(r.songs).filter((t) => t.id !== String(seed)).slice(0, limit);
}

// Comments, which is half of what NetEase is to people.

const threadOf = (trackId) => programIdOf(trackId) ? `A_DJ_1_${programIdOf(trackId)}` : `R_SO_4_${trackId}`;

function commentOf(c) {
  return {
    id: String(c.commentId),
    author: (c.user && c.user.nickname) || "",
    avatarUrl: pictureOf(c.user && c.user.avatarUrl),
    text: c.content || "",
    time: c.time || undefined,
    likes: c.likedCount || undefined,
    liked: Boolean(c.liked) || undefined,
  };
}

export async function comments({ trackId, offset }) {
  const r = await api(`/api/v1/resource/comments/${threadOf(trackId)}`, { limit: 20, offset });
  // The hot ones come with every page, and belong at the top of the first.
  const raw = offset === 0 ? [...(r.hotComments || []), ...(r.comments || [])] : r.comments || [];
  const seen = new Set();
  const list = [];
  for (const c of raw) {
    const id = String(c.commentId);
    if (seen.has(id)) continue;
    seen.add(id);
    list.push(commentOf(c));
  }
  return { comments: list, total: r.total || undefined, more: Boolean(r.more) };
}

export async function likeComment({ trackId, commentId, on }) {
  needAccount();
  await weapi("/weapi/v1/comment/like", { threadId: threadOf(trackId), commentId: Number(commentId), like: Boolean(on) });
}

export async function postComment({ trackId, text }) {
  needAccount();
  const r = await weapi("/weapi/resource/comments/add", { threadId: threadOf(trackId), content: text });
  const c = r.comment || {};
  return {
    id: String(c.commentId || Date.now()),
    author: (c.user && c.user.nickname) || (me && me.name) || "",
    text: c.content || text,
    time: c.time || Date.now(),
  };
}

// Lyrics, as LRC and its translation, which the app times itself.

export async function lyrics({ id }) {
  if (programIdOf(id)) return { lrc: "" };
  const r = await api("/api/song/lyric", { id, lv: -1, kv: -1, tv: -1 });
  return {
    lrc: (r.lrc && r.lrc.lyric) || "",
    translation: (r.tlyric && r.tlyric.lyric) || undefined,
  };
}

// Playing.

// The levels the service names its audio by, and what each is here.
const levels = {
  standard: { level: "standard", encode: "mp3" },
  high: { level: "exhigh", encode: "mp3" },
  lossless: { level: "lossless", encode: "flac" },
};

async function audioOf(id, level, encode) {
  const ids = JSON.stringify([Number(id)]);
  const playable = (reply) => (reply.data || []).find((song) =>
    String(song.id) === String(id) && song.url && (song.code === undefined || song.code === 200)) || null;
  let firstError;
  try {
    const found = playable(await eapi("/api/song/enhance/player/url/v1", { ids, level, encodeType: encode }));
    if (found) return found;
  } catch (error) {
    if (error.code === "signIn") throw error;
    firstError = error;
  }
  // The bitrate endpoint can still serve cloud copies when v1 refuses them.
  const br = { standard: 128000, exhigh: 320000, lossless: 999000 }[level];
  const found = playable(await eapi("/api/song/enhance/player/url", { ids, br }));
  if (!found && firstError) throw firstError;
  return found;
}

const qualityOf = (d) =>
  d.level === "lossless" || d.level === "hires" || d.level === "jymaster" ? "lossless" : d.level === "exhigh" ? "high" : "standard";

const finite = (v) => (typeof v === "number" && Number.isFinite(v) ? v : undefined);

export async function stream({ id, quality }) {
  const programId = programIdOf(id);
  if (programId) {
    const r = await eapi("/api/dj/program/detail", { id: Number(programId) });
    id = r.program?.mainSong?.id;
    if (!id) throw fail("unavailable", "这个播客节目没有可播放的音频。");
  }
  const asked = levels[quality] || levels.standard;
  let found = null;
  // A level the listener's membership doesn't reach comes back as a lower one,
  // or as nothing: the one below it is worth trying.
  const choices = [levels.lossless, levels.high, levels.standard];
  for (const step of choices.slice(choices.indexOf(asked))) {
    found = await audioOf(id, step.level, step.encode);
    if (found) break;
  }
  if (!found) throw fail("unavailable", "这首歌没有可播放的地址：可能已下架，或者需要会员。");
  return {
    url: pictureOf(found.url),
    type: String(found.type).toLowerCase() === "flac" ? "flac" : "mp3",
    quality: qualityOf(found),
    bitRate: found.br || undefined,
    size: found.size || undefined,
    gain: finite(found.gain),
    peak: finite(found.peak),
    headers: { "user-agent": webUA, referer: `${site}/` },
  };
}

// Making and keeping playlists, saving albums, following artists: the half of
// the service that takes a session.

export async function createPlaylist({ name }) {
  needAccount();
  const r = await weapi("/weapi/playlist/create", { name, privacy: "0", type: "NORMAL" });
  const p = r.playlist || {};
  return { id: String(p.id || r.id || ""), name: p.name || name, mine: true, trackCount: 0, tracks: [] };
}

export async function renamePlaylist({ id, name }) {
  needAccount();
  await eapi("/api/playlist/update/name", { id: Number(id), name });
}

export async function deletePlaylist({ id }) {
  needAccount();
  await weapi("/weapi/playlist/remove", { ids: JSON.stringify([Number(id)]) });
}

// The ids go as strings, as its own apps send them.
const trackIdsOf = (trackIds) => {
  if (trackIds.some(programIdOf)) throw fail("unsupported", "播客节目不能加入歌曲歌单。");
  return JSON.stringify(trackIds.map(String));
};

export async function addToPlaylist({ id, trackIds }) {
  needAccount();
  await eapi("/api/playlist/manipulate/tracks", {
    op: "add",
    pid: String(id),
    trackIds: trackIdsOf(trackIds),
    imme: "true",
  });
}

export async function removeFromPlaylist({ id, trackIds }) {
  needAccount();
  await eapi("/api/playlist/manipulate/tracks", {
    op: "del",
    pid: String(id),
    trackIds: trackIdsOf(trackIds),
    imme: "true",
  });
}

// Moving a track is the playlist's whole order, written again without it.
export async function movePlaylistTrack({ id, from, to }) {
  needAccount();
  const r = await api("/api/v6/playlist/detail", { id, n: 1000 });
  const ids = ((r.playlist && r.playlist.trackIds) || []).map((t) => t.id);
  if (from < 0 || from >= ids.length || to < 0 || to >= ids.length) throw new Error("这首歌不在这个歌单里。");
  ids.splice(to, 0, ids.splice(from, 1)[0]);
  await weapi("/weapi/playlist/order/update", { ids: JSON.stringify(ids) });
}

export async function savePlaylist({ id, on }) {
  needAccount();
  await eapi(`/api/playlist/${on ? "subscribe" : "unsubscribe"}`, { id: Number(id) }, true);
}

export async function followArtist({ id, on }) {
  needAccount();
  await weapi(`/weapi/artist/${on ? "sub" : "unsub"}`, {
    artistId: Number(id),
    artistIds: JSON.stringify([Number(id)]),
  });
}

export async function saveAlbum({ id, on }) {
  needAccount();
  await weapi(`/weapi/album/${on ? "sub" : "unsub"}`, { id: Number(id) });
  savedAlbumCache = null;
}

// The albums saved are a list of their own, and what a page asks to know
// whether its album is in it.

let savedAlbumCache = null;
let savedAlbumAt = 0;
const savedAlbumFor = 5 * 60 * 1000;

const albumListOf = (r) => (Array.isArray(r.data) ? r.data : Array.isArray(r.albums) ? r.albums : []);

async function savedAlbumIds() {
  if (!cookie()) return new Set();
  if (savedAlbumCache && Date.now() - savedAlbumAt < savedAlbumFor) return savedAlbumCache;
  const set = new Set();
  try {
    const r = await weapi("/weapi/album/sublist", { limit: 1000, offset: 0, total: true });
    for (const a of albumListOf(r)) set.add(String(a.id || a.albumId));
  } catch (e) {
    console.warn(`收藏的专辑: ${e.message}`);
  }
  savedAlbumCache = set;
  savedAlbumAt = Date.now();
  return set;
}

export async function savedAlbums({ offset }) {
  needAccount();
  const r = await weapi("/weapi/album/sublist", { limit: 25, offset, total: true });
  return { albums: albumListOf(r).map(albumEntryOf), more: Boolean(r.hasMore ?? r.more) };
}
