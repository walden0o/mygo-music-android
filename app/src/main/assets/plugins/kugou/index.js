// A direct client for Kugou Concept Edition (酷狗概念版), following the
// protocol used by KuGouMusicApi. No external API service or process is needed.

const GATEWAY = "https://gateway.kugou.com";
const LYRICS = "https://lyrics.kugou.com";
const LOGIN = "https://login-user.kugou.com";
const EXTRA = "https://expendablekmr.kugou.com";
const APP_ID = 3116;
const SOURCE_APP_ID = 2919;
const CLIENT_VERSION = 11440;
const SIGN_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
const UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
const PAGE_SIZE = 30;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function deviceID() {
  let value = host.storage.get("mid");
  if (!value) {
    value = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join("").toUpperCase();
    host.storage.set("mid", value);
  }
  return value;
}

function md5(value) { return host.crypto.md5(String(value)); }

function webSignature(values) {
  const canonical = Object.keys(values).sort().map((key) => `${key}=${values[key]}`).join("");
  return md5(`NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt${canonical}NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt`);
}

async function loginAPI(path, values) {
  const params = {
    dfid: "-", mid: deviceID(), uuid: "-", appid: APP_ID,
    clientver: CLIENT_VERSION, clienttime: Math.floor(Date.now() / 1000),
    ...values,
  };
  params.signature = webSignature(params);
  const url = new URL(`${LOGIN}${path}`);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
  const response = await fetch(url, {
    headers: {
      "User-Agent": UA, dfid: params.dfid, clienttime: String(params.clienttime), mid: params.mid,
      "kg-rc": "1", "kg-thash": "5d816a0", "kg-rec": "1",
      "kg-rf": "B9EDA08A64250DEFFBCADDEE00F8F25F",
    },
    redirect: "error",
  });
  if (!response.ok) throw new Error(`酷狗登录请求失败（HTTP ${response.status}）`);
  const text = await response.text();
  if (!text.trim()) throw new Error("酷狗登录服务返回空响应");
  const result = JSON.parse(text);
  if (Number(result.status) === 0 || (result.error_code && Number(result.error_code) !== 0)) {
    throw new Error(result.error || result.error_msg || result.errmsg || result.msg || JSON.stringify(result));
  }
  return result;
}

function signedParams(values, body = "") {
  const params = { ...values };
  const canonical = Object.keys(params).sort().map((key) => {
    const value = params[key];
    return `${key}=${typeof value === "object" ? JSON.stringify(value) : value}`;
  }).join("");
  const data = typeof body === "string" ? body : body ? JSON.stringify(body) : "";
  params.signature = md5(`${SIGN_SALT}${canonical}${data}${SIGN_SALT}`);
  return params;
}

function commonParams(extra = {}) {
  const clienttime = Math.floor(Date.now() / 1000);
  const auth = host.storage.get("auth") || {};
  return {
    dfid: "-", mid: deviceID(), uuid: "-", appid: APP_ID,
    clientver: CLIENT_VERSION, clienttime,
    ...(auth.token ? { token: auth.token } : {}),
    ...(auth.userid ? { userid: auth.userid } : {}),
    ...extra,
  };
}

async function request(path, params, router, options = {}) {
  const method = options.method || "GET";
  const body = options.body;
  const query = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)])).toString();
  const response = await fetch(`${GATEWAY}${path}?${query}`, {
    method,
    headers: {
      "User-Agent": UA,
      ...(router ? { "x-router": router } : {}),
      "dfid": String(params.dfid || "-"),
      "clienttime": String(params.clienttime || ""),
      "mid": String(params.mid || ""),
      "kg-rc": "1", "kg-thash": "5d816a0", "kg-rec": "1",
      "kg-rf": "B9EDA08A64250DEFFBCADDEE00F8F25F",
      ...(options.headers || {}),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    ...(body !== undefined ? { body: typeof body === "string" ? body : JSON.stringify(body) } : {}),
    redirect: "error",
  });
  if (!response.ok) throw new Error(`酷狗请求失败（HTTP ${response.status}）`);
  const result = await response.json();
  if ((Number(result.status) === 0 || (result.error_code && Number(result.error_code) !== 0)) && Number(result.error_code) !== 20028) {
    const message = result.error || result.error_msg || result.errmsg || result.msg;
    if (Number(result.errcode) === 20006) throw fail("unavailable", "酷狗要求完成设备验证后才能取流；当前插件还不能处理这类验证");
    throw new Error(`${message || JSON.stringify(result)} (${path})`);
  }
  return result?.data ?? result;
}

function namesOf(raw) {
  const value = raw.singerinfo || raw.authors || raw.author_name || raw.SingerName || raw.singername || raw.singer || [];
  if (Array.isArray(value)) return value.map((artist) => {
    if (typeof artist === "string") return { id: artist, name: artist };
    const name = artist.author_name || artist.singername || artist.SingerName || artist.name || "";
    return { id: String(artist.author_id || artist.singerid || artist.SingerId?.[0] || artist.id || name), name };
  }).filter((artist) => artist.name);
  return String(value || "未知歌手").split(/[、&,，]/).map((name) => name.trim()).filter(Boolean).map((name) => ({ id: name, name }));
}

function toTrack(raw) {
  const track = { ...raw, ...(raw.base || {}), ...(raw.audio_info || {}) };
  const albumInfo = raw.album_info || raw.albuminfo || {};
  const id = String(track.hash || track.HASH || track.FileHash || track.fileHash || track.audio_id || track.MixSongID || track.mixsongid || track.album_audio_id || "");
  const duration = Number(track.duration || track.Duration || track.timelength || track.timelen || track.time_length || track.timeLength || 0);
  const image = String(raw.album_img_9 || raw.album_img || raw.album_image || raw.albumImg || raw.sizable_cover || raw.imgurl || raw.img || raw.image || raw.Image || raw.AlbumImage || raw.cover || albumInfo.cover || raw.trans_param?.union_cover || "").replace("{size}", "400");
  const albumName = String(raw.album_name || raw.AlbumName || raw.albumname || albumInfo.album_name || albumInfo.name || "未知专辑");
  let name = String(track.songname || track.SongName || track.song_name || track.audio_name || track.FileName || track.filename || track.name || "未知歌曲");
  const separator = name.search(/\s[-–—]\s/);
  if (separator > 0) {
    const prefix = name.slice(0, separator).trim();
    const artists = namesOf(track).map((artist) => artist.name);
    const prefixNames = prefix.split(/[、&,，]/).map((artist) => artist.trim());
    if (prefixNames.some((artist) => artists.includes(artist))) name = name.slice(separator + 3).trim();
  }
  return {
    id,
    name,
    artists: namesOf(track),
    album: { id: String(track.album_id || track.AlbumID || track.albumid || albumName), name: albumName, imageUrl: image || undefined },
    duration: duration > 1000 ? duration : duration * 1000,
    url: `https://www.kugou.com/song/#${id}`,
  };
}

function toArtist(raw = {}) {
  const id = String(raw.author_id || raw.authorid || raw.singerid || raw.singer_id || raw.AuthorId || raw.id || "");
  const name = String(raw.author_name || raw.authorname || raw.AuthorName || raw.singername || raw.singer_name || raw.name || "");
  const image = String(raw.imgurl || raw.author_img || raw.singer_img || raw.image || raw.pic || raw.avatar || raw.Avatar || "").replace("{size}", "400");
  return { id, name, imageUrl: image || undefined };
}

function toAlbum(raw = {}) {
  const id = String(raw.album_id || raw.albumid || raw.AlbumID || raw.id || "");
  const name = String(raw.album_name || raw.albumname || raw.AlbumName || raw.name || "");
  const authors = raw.authors || raw.author || raw.singers || raw.singerinfo || [];
  const artists = Array.isArray(authors) ? authors.map(toArtist).filter((artist) => artist.name) :
    (raw.singername ? [{ id: String(raw.singerid || raw.singername), name: String(raw.singername) }] : namesOf({ author_name: raw.author_name }));
  const image = String(raw.sizable_cover || raw.cover || raw.imgurl || raw.album_img || raw.image || raw.pic || "").replace("{size}", "400");
  return { id, name, artists, imageUrl: image || undefined, released: String(raw.publish_date || raw.publishDate || raw.released || "") };
}

function moreOf(data, page, size, count) {
  const total = Number(data.total || data.total_count || data.totalnum || data.totalPages || 0);
  return total ? (total > page ? true : page * size < total) : count >= size;
}

async function searchData(kind, query, page) {
  const size = kind === "albums" ? 20 : PAGE_SIZE;
  const type = { tracks: "song", albums: "album", artists: "author", playlists: "special" }[kind];
  if (!type) return { items: [], more: false };
  const params = commonParams(type === "song" ? {
    keyword: query, page, pagesize: size, platform: "AndroidFilter", iscorrection: 1,
    privilegefilter: 0, area_code: 1, dopicfull: 1, clientver: 201,
  } : type === "album" ? {
    keyword: query, page, pagesize: size, platform: "AndroidFilter", iscorrection: 1,
    category: 1, sorttype: 0, searchsong: 0, clientver: 201,
  } : {
    albumhide: 0, iscorrection: 1, keyword: query, nocollect: 0, page, pagesize: size, platform: "AndroidFilter",
  });
  const path = type === "song" ? "/v2/search/song" : `/v1/search/${type}`;
  const result = await request(path, signedParams(params), "complexsearch.kugou.com");
  const items = recordsOf(result);
  return { items, page, size, more: moreOf(result, page, size, items.length) };
}

async function artistList(page = 1) {
  const params = signedParams(commonParams({ musician: 0, sextype: 0, showtype: 2, type: 0, hotsize: 30, page }));
  const data = await request("/ocean/v6/singer/list", params, "");
  return recordsOf(data).map(toArtist).filter((artist) => artist.id && artist.name).slice(0, 30);
}

async function chartList() {
  const data = await request("/ocean/v6/rank/list", signedParams(commonParams({ plat: 2, withsong: 1, parentid: 0 })), "");
  return recordsOf(data);
}

async function newAlbums(page = 1) {
  const body = { apiver: 20, token: String((host.storage.get("auth") || {}).token || ""), page, pagesize: 30, withpriv: 1 };
  const data = await request("/musicadservice/v1/mobile_newalbum_sp", signedParams(commonParams(), body), "", { method: "POST", body });
  return recordsOf(data).map(toAlbum).filter((album) => album.id && album.name);
}

async function artistTracks(id, page = 1) {
  const params = signedParams(commonParams({ author_id: id, area_code: "all", sort: 1, page, pagesize: 30,
    replace_api_version: 1, mvdata_need: 1, show_audio_honor: 1, show_audio_tag: 1, replace_need: 1 }));
  const data = await request("/openapi/kmr/v2/audio_group/author", params, "", { headers: { "kg-tid": "36" } });
  return songItems(data).map(toTrack).filter((track) => track.id && track.name);
}

function recordsOf(data) {
  if (Array.isArray(data?.chn) || Array.isArray(data?.eur)) return [...(data.chn || []), ...(data.eur || [])];
  if (Array.isArray(data?.info) && Array.isArray(data.info[0]?.singer)) return data.info.flatMap((group) => group.singer);
  const items = data.info || data.lists || data.songs || data.albums || data.authors || data.special_list || data.data?.info || data.data?.lists || data.data?.list || data.data?.albums || data.data?.authors || data.data || data.list || data;
  return Array.isArray(items) ? items : [];
}

async function addAlbumImages(tracks) {
  const missing = tracks.filter((track) => track.id && !track.album.imageUrl);
  if (!missing.length) return tracks;
  const paramsMap = {
    album_image_type: "-3", appid: APP_ID, clientver: CLIENT_VERSION,
    author_image_type: "3,4,5", count: 5,
    data: missing.map((track) => ({ album_id: Number(track.album.id) || 0, hash: track.id, album_audio_id: 0 })),
    isCdn: 1, publish_time: 1,
  };
  const canonical = Object.keys(paramsMap).sort().map((key) => `${key}=${typeof paramsMap[key] === "object" ? JSON.stringify(paramsMap[key]) : paramsMap[key]}`).join("");
  const query = Object.keys(paramsMap).sort().map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(typeof paramsMap[key] === "object" ? JSON.stringify(paramsMap[key]) : String(paramsMap[key]))}`).join("&");
  const url = `${EXTRA}/container/v2/image?${query}&signature=${md5(`${SIGN_SALT}${canonical}${SIGN_SALT}`)}`;
  const response = await fetch(url, { headers: { "User-Agent": UA }, redirect: "error" });
  if (!response.ok) return tracks;
  const result = await response.json();
  const images = result.data || result.info || result;
  const byHash = new Map();
  for (const item of Array.isArray(images) ? images : Object.values(images || {})) {
    const hash = String(item.hash || item.FileHash || item.audio_hash || "").toUpperCase();
    const image = String(item.album_img || item.album_image || item.imgurl || item.image || item.cover || "").replace("{size}", "400");
    if (hash && image) byHash.set(hash, image);
  }
  for (const track of missing) track.album.imageUrl = byHash.get(track.id.toUpperCase());
  return tracks;
}

function toPlaylist(raw) {
  const id = String(raw.global_collection_id || raw.globalCollectionId || raw.specialid || raw.listid || raw.id || "");
  const image = String(raw.imgurl || raw.pic || raw.cover || raw.image || raw.img || raw.pic_url || raw.imageUrl || "").replace("{size}", "400");
  return {
    id,
    name: String(raw.specialname || raw.name || raw.title || raw.collection_name || "酷狗歌单"),
    imageUrl: image || undefined,
    description: String(raw.intro || raw.description || raw.desc || ""),
    owner: String(raw.username || raw.nickname || raw.owner_name || ""),
    trackCount: Number(raw.songcount || raw.count || raw.track_count || raw.trackCount || raw.total || 0),
  };
}

function playlistItems(data) {
  const items = data.special_list || data.specialList || data.playlists || data.list || data.info || data.data?.special_list || data.data?.list || data.data?.info || data;
  return Array.isArray(items) ? items : [];
}

function songItems(data) {
  const items = data.song_list || data.songs || data.info || data.list || data.data?.song_list || data.data?.songs || data.data?.info || data.data?.list || data;
  return Array.isArray(items) ? items : [];
}

async function dailyTracks() {
  const params = signedParams(commonParams({ platform: "ios" }));
  const data = await request("/everyday_song_recommend", params, "everydayrec.service.kugou.com", { method: "POST" });
  return addAlbumImages(songItems(data).map(toTrack).filter((track) => track.id && track.name));
}

async function recommendedPlaylists(page = 1) {
  const clienttime = String(Math.floor(Date.now() / 1000));
  const key = md5(`${APP_ID}${SIGN_SALT}${CLIENT_VERSION}${clienttime}`);
  const body = {
    appid: APP_ID, mid: deviceID(), clientver: CLIENT_VERSION, platform: "android",
    clienttime, userid: String((host.storage.get("auth") || {}).userid || 0), module_id: 1,
    page, pagesize: PAGE_SIZE, key,
    special_recommend: {
      withtag: 1, withsong: 1, sort: 1, ugc: 1, is_selected: 0,
      withrecommend: 1, area_code: 1, categoryid: 0,
    },
    req_multi: 1, retrun_min: 5, return_special_falg: 1,
  };
  const params = signedParams(commonParams(), body);
  const data = await request("/v2/special_recommend", params, "specialrec.service.kugou.com", { method: "POST", body });
  const result = playlistItems(data).map(toPlaylist).filter((playlist) => playlist.id);
  const cached = host.storage.get("playlistMetadata") || {};
  for (const playlist of result) cached[playlist.id] = playlist;
  host.storage.set("playlistMetadata", cached);
  return result;
}

export async function home() {
  const sections = [];
  try {
    const tracks = await dailyTracks();
    if (tracks.length) sections.push({ title: "每日推荐", tracks, morePage: "daily" });
  } catch (error) {
    sections.push({ title: "每日推荐暂不可用", links: [{ title: String(error.message || error), page: "daily" }] });
  }
  try {
    const playlists = await recommendedPlaylists();
    if (playlists.length) sections.push({ title: "推荐歌单", playlists });
  } catch (_) {}
  sections.push({ title: "搜索", links: [{ title: "搜索酷狗歌曲", subtitle: "打开 MyGo Music 的搜索入口", page: "help" }] });
  return {
    title: "酷狗概念版",
    subtitle: "酷狗音乐曲库",
    sections,
  };
}

export async function signIn() {
  const device = deviceID();
  const keyResponse = await loginAPI("/v2/qrcode", {
    appid: 1001, type: 1, plat: 4,
    qrcode_txt: `https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${APP_ID}&`,
    srcappid: SOURCE_APP_ID,
  });
  const key = keyResponse.data?.qrcode || keyResponse.data?.key || keyResponse.qrcode;
  if (!key) throw new Error("酷狗没有返回登录二维码，请稍后重试");
  const qr = `https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${encodeURIComponent(key)}`;
  host.signInPrompt({ qr, text: "请使用酷狗音乐或酷狗概念版 App 扫码登录" });

  const until = Date.now() + 180000;
  while (Date.now() < until) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    const result = await loginAPI("/v2/get_userinfo_qrcode", {
      plat: 4, appid: APP_ID, srcappid: SOURCE_APP_ID, qrcode: key, dev: device,
    });
    const data = result.data || {};
    if (Number(data.status) === 0) throw new Error("酷狗登录二维码已过期，请重新登录");
    if (Number(data.status) === 2) host.signInPrompt({ qr, text: "已扫码，请在手机上确认登录" });
    if (Number(data.status) === 4 && data.token && data.userid) {
      const auth = { token: String(data.token), userid: String(data.userid) };
      const account = { id: auth.userid, name: String(data.nickname || data.username || `酷狗用户 ${auth.userid}`), imageUrl: data.avatar || undefined };
      host.storage.set("auth", auth);
      host.storage.set("account", account);
      host.signInPrompt({ text: `已登录：${account.name}` });
      return account;
    }
    host.signInPrompt({ qr, text: "等待使用酷狗 App 扫码登录…" });
  }
  throw new Error("酷狗登录超时，请重试");
}

export function account() { return host.storage.get("account") || null; }

export function signOut() {
  host.storage.remove("auth");
  host.storage.remove("account");
}

export async function playlists() {
  const auth = host.storage.get("auth") || {};
  if (!auth.userid || !auth.token) return recommendedPlaylists();
  const body = { userid: String(auth.userid), token: String(auth.token), total_ver: 979, type: 2, page: 1, pagesize: 100 };
  const params = signedParams(commonParams({ plat: 1, userid: Number(auth.userid), token: auth.token }), body);
  const data = await request("/v7/get_all_list", params, "cloudlist.service.kugou.com", { method: "POST", body });
  const lists = recordsOf(data).map(toPlaylist).filter((item) => item.id);
  const cached = host.storage.get("playlistMetadata") || {};
  for (const item of lists) cached[item.id] = item;
  host.storage.set("playlistMetadata", cached);
  return lists;
}

export function browse({ page }) {
  if (page === "help") return { title: "搜索酷狗歌曲", subtitle: "打开 MyGo Music 侧栏的搜索入口，输入歌名或歌手。" };
  if (page === "daily") return dailyTracks().then((tracks) => ({ title: "每日推荐", sections: [{ title: "为你推荐", tracks }] }));
  if (page === "playlists") return recommendedPlaylists().then((playlists) => ({ title: "推荐歌单", sections: [{ title: "酷狗精选", playlists }] }));
  if (page === "artists") return artistList().then((artists) => ({ title: "歌手", sections: [{ title: "热门歌手", artists }] }));
  if (page === "charts") return chartList().then((ranks) => ({ title: "排行榜", sections: [{ title: "酷狗榜单", links: ranks.map((rank) => ({
    title: String(rank.rankname || rank.rank_name || rank.name || "酷狗榜单"),
    subtitle: String(rank.intro || rank.update_frequency || ""),
    imageUrl: String(rank.imgurl || rank.img_9 || rank.album_img_9 || rank.image || "").replace("{size}", "400"),
    page: `rank:${rank.rankid || rank.rank_id || rank.id}`,
  })).filter((link) => link.page !== "rank:undefined") }] }));
  if (page === "new") return newAlbums().then((albums) => ({ title: "新专辑", sections: [{ title: "最新发行", albums }] }));
  if (page.startsWith("rank:")) {
    const rankID = page.slice(5);
    const body = { show_portrait_mv: 1, show_type_total: 1, filter_original_remarks: 1, area_code: 1,
      pagesize: PAGE_SIZE, rank_cid: 0, type: 1, page: 1, rank_id: rankID };
    return request("/openapi/kmr/v2/rank/audio", signedParams(commonParams(), body), "", { method: "POST", body, headers: { "kg-tid": "369" } })
      .then((data) => ({ title: "排行榜", sections: [{ title: "榜单歌曲", tracks: songItems(data).map(toTrack).filter((track) => track.id) }] }));
  }
  throw fail("unavailable", "没有这个页面");
}

export async function playlist({ id }) {
  const pageSize = 100;
  const params = signedParams(commonParams({
    area_code: 1, begin_idx: 0, plat: 1, type: 1, mode: 1, personal_switch: 1,
    extend_fields: "abtags,hot_cmt,popularization", pagesize: pageSize,
    global_collection_id: id,
  }));
  const data = await request("/pubsongs/v2/get_other_list_file_nofilt", params, "");
  const tracks = songItems(data).map(toTrack).filter((track) => track.id && track.name);
  let meta = (host.storage.get("playlistMetadata") || {})[id] || {};
  try {
    const auth = host.storage.get("auth") || {};
    const detailBody = {
      data: [{ global_collection_id: id }],
      userid: String(auth.userid || 0),
      token: String(auth.token || ""),
    };
    const detailParams = signedParams(commonParams(), detailBody);
    const detail = await request("/v3/get_list_info", detailParams, "pubsongs.kugou.com", { method: "POST", body: detailBody });
    const candidates = [detail.info?.[0], detail.list?.[0], detail.data?.[0], detail.data?.info?.[0],
      detail.data?.list?.[0], detail.data?.info, detail.data?.list, detail.data, detail];
    const detailMeta = candidates.find((item) => item && typeof item === "object" && !Array.isArray(item) &&
      (item.specialname || item.name || item.title || item.imgurl || item.pic || item.cover || item.image || item.img || item.pic_url));
    if (detailMeta) meta = { ...meta, ...detailMeta };
  } catch (_) {}
  const result = toPlaylist({ ...meta, global_collection_id: id });
  result.tracks = tracks;
  if (!result.trackCount) result.trackCount = tracks.length;
  return result;
}

export async function artist({ id }) {
  const params = signedParams(commonParams(), { author_id: id });
  const detail = await request("/kmr/v3/author", params, "openapi.kugou.com", { method: "POST", body: { author_id: id }, headers: { "kg-tid": "36" } });
  const rawArtist = detail.author || detail.info || detail.data?.author || detail.data?.info || detail;
  const artist = toArtist(Array.isArray(rawArtist) ? rawArtist[0] || {} : rawArtist);
  artist.id ||= String(id);
  const [tracks, albumsData] = await Promise.all([
    artistTracks(id),
    request("/kmr/v1/author/albums", signedParams(commonParams(), { author_id: id, pagesize: 30, page: 1, sort: 1, category: 1, area_code: "all" }),
      "openapi.kugou.com", { method: "POST", body: { author_id: id, pagesize: 30, page: 1, sort: 1, category: 1, area_code: "all" }, headers: { "kg-tid": "36" } }),
  ]);
  const albums = recordsOf(albumsData).map(toAlbum).filter((album) => album.id && album.name);
  return { artist, tracks: await addAlbumImages(tracks), albums };
}

export async function album({ id }) {
  const detailBody = { data: [{ album_id: id }], is_buy: 0,
    fields: "album_id,album_name,publish_date,sizable_cover,intro,language,is_publish,heat,type,quality,authors,exclusive,author_name,trans_param" };
  const [detailData, tracksData] = await Promise.all([
    request("/kmr/v2/albums", signedParams(commonParams(), detailBody), "openapi.kugou.com", { method: "POST", body: detailBody, headers: { "kg-tid": "255" } }),
    request("/v1/album_audio/lite", signedParams(commonParams(), { album_id: id, is_buy: "", page: 1, pagesize: 30 }), "openapi.kugou.com",
      { method: "POST", body: { album_id: id, is_buy: "", page: 1, pagesize: 30 }, headers: { "kg-tid": "255" } }),
  ]);
  const raw = recordsOf(detailData)[0] || detailData.album || detailData;
  const tracks = songItems(tracksData).map(toTrack).filter((track) => track.id && track.name);
  const albumInfo = toAlbum(raw);
  if (tracks.length && !albumInfo.imageUrl) albumInfo.imageUrl = tracks.find((track) => track.album.imageUrl)?.album.imageUrl;
  return { album: albumInfo, tracks: await addAlbumImages(tracks) };
}

export async function search({ query, kind, offset = 0 }) {
  const supportedKind = ["tracks", "albums", "artists", "playlists"].includes(kind) ? kind : "tracks";
  const page = Math.floor(offset / (supportedKind === "albums" ? 20 : PAGE_SIZE)) + 1;
  const result = await searchData(supportedKind, query, page);
  const items = result.items;
  if (supportedKind === "tracks") return { tracks: await addAlbumImages(items.map(toTrack).filter((track) => track.id)), more: result.more };
  if (supportedKind === "albums") return { albums: items.map(toAlbum).filter((album) => album.id), more: result.more };
  if (supportedKind === "artists") return { artists: items.map(toArtist).filter((artist) => artist.id), more: result.more };
  return { playlists: items.map(toPlaylist).filter((playlist) => playlist.id), more: result.more };
}

export async function suggest({ query }) {
  if (!query.trim()) return [];
  const data = await request("/v2/search/song", signedParams(commonParams({
    keyword: query, page: 1, pagesize: 8, platform: "AndroidFilter",
    iscorrection: 1, privilegefilter: 0, area_code: 1, dopicfull: 1, clientver: 201,
  })), "complexsearch.kugou.com");
  return (data.info || data.lists || []).map((item) => String(item.songname || item.SongName || item.FileName || item.filename || "")).filter(Boolean).slice(0, 8);
}

export async function stream({ id, quality }) {
  const level = quality === "lossless" ? "flac" : quality === "high" ? "320" : "128";
  const params = commonParams({
    album_id: 0, area_code: 1, hash: String(id).toLowerCase(), ssa_flag: "is_fromtrack",
    version: 11430, page_id: 967177915, quality: level, album_audio_id: 0,
    behavior: "play", pid: 411, cmd: 26, pidversion: 3001, IsFreePart: 0,
    ppage_id: "356753938,823673182,967485191", cdnBackup: 1, module: "", clientver: 11430,
  });
  params.key = md5(`${params.hash}185672dd44712f60bb1736df5a377e82${params.appid}${params.mid}${params.userid || 0}`);
  const data = await request("/v5/url", signedParams(params), "trackercdn.kugou.com");
  const urls = Array.isArray(data.url) ? data.url : [data.url, data.backup_url].filter(Boolean);
  const url = urls.find((candidate) => typeof candidate === "string" && candidate.startsWith("http"));
  if (!url) throw fail("unavailable", "酷狗没有返回可播放的音频，可能受版权或账号权限限制");
  const ext = String(data.ext || url.split(/[?#]/)[0].split(".").pop() || "mp3").toLowerCase();
  return { url, type: ext === "flac" ? "flac" : ext === "ogg" ? "ogg" : "mp3", quality: level, bitRate: Number(data.bitrate || 0) || undefined };
}

export async function lyrics({ id }) {
  const searchURL = new URL("http://krcs.kugou.com/search");
  for (const [key, value] of Object.entries({ ver: 1, man: "yes", client: "mobi", keyword: "", duration: 0, hash: id, album_audio_id: "" })) {
    searchURL.searchParams.set(key, String(value));
  }
  const searchResponse = await fetch(searchURL, { headers: { "User-Agent": UA }, redirect: "error" });
  if (!searchResponse.ok) throw new Error(`酷狗歌词请求失败（HTTP ${searchResponse.status}）`);
  const searchText = await searchResponse.text();
  if (!searchText.trim()) throw fail("unavailable", "酷狗歌词服务返回空响应");
  const found = JSON.parse(searchText);
  const candidate = (found.candidates || found.data?.candidates || [])[0];
  if (!candidate) throw fail("unavailable", "没有找到这首歌的歌词");

  const lyricURL = new URL(`${LYRICS}/download`);
  for (const [key, value] of Object.entries({ ver: 1, client: "android", id: candidate.id, accesskey: candidate.accesskey, fmt: "lrc", charset: "utf8" })) {
    lyricURL.searchParams.set(key, String(value));
  }
  const lyricResponse = await fetch(lyricURL, { headers: { "User-Agent": UA }, redirect: "error" });
  if (!lyricResponse.ok) throw new Error(`酷狗歌词请求失败（HTTP ${lyricResponse.status}）`);
  const lyricText = await lyricResponse.text();
  if (!lyricText.trim()) throw fail("unavailable", "酷狗歌词服务返回空响应");
  const result = JSON.parse(lyricText);
  if (!result.content) throw fail("unavailable", "酷狗没有返回歌词");
  let lrc;
  try {
    const binary = atob(result.content);
    const escaped = Array.from(binary, (character) => `%${character.charCodeAt(0).toString(16).padStart(2, "0")}`).join("");
    lrc = decodeURIComponent(escaped);
  } catch { throw new Error("酷狗返回了无法识别的歌词数据"); }
  return { lrc };
}
