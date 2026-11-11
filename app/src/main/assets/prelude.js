// Host prelude for MyGo Music plugins, over flat natives provided by the
// Kotlin side: _log, _storageGet/_storageSet/_storageRemove, _md5, _aesEcb,
// _setting, _translate, _language, _signInPrompt, _openURL, _deliver,
// _nextDelayId/_delayInternal/_cancelDelayInternal, and the async
// _httpInternal(reqJson) -> responseJson.

// ---- timers (pattern from the quickjs-kt openai sample) ---------------------
globalThis.setTimeout = function (callback, timeout) {
  const id = _nextDelayId();
  const args = Array.prototype.slice.call(arguments, 2);
  _delayInternal(timeout || 0, id)
    .then(function () { callback.apply(null, args); })
    .catch(function () {});
  return id;
};
globalThis.clearTimeout = function (id) { _cancelDelayInternal(id); };
globalThis.setInterval = function (callback, timeout) {
  const args = Array.prototype.slice.call(arguments, 2);
  let id = 0;
  const tick = function () {
    callback.apply(null, args);
    id = globalThis.setTimeout(tick, timeout || 0);
  };
  id = globalThis.setTimeout(tick, timeout || 0);
  return id;
};
globalThis.clearInterval = globalThis.clearTimeout;

// ---- TextEncoder / TextDecoder (UTF-8) ---------------------------------------
// Some plugins (e.g. the NetEase one) encode request bodies with TextEncoder;
// QuickJS ships no web platform, so provide the UTF-8 pair here.
if (typeof globalThis.TextEncoder === "undefined") {
  globalThis.TextEncoder = class TextEncoder {
    constructor() { this.encoding = "utf-8"; }
    encode(input) {
      const str = String(input == null ? "" : input);
      const out = [];
      for (let i = 0; i < str.length; i++) {
        let cp = str.codePointAt(i);
        if (cp > 0xFFFF) i++;
        if (cp < 0x80) out.push(cp);
        else if (cp < 0x800) out.push(0xC0 | (cp >> 6), 0x80 | (cp & 63));
        else if (cp < 0x10000) out.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
        else out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      }
      return new Uint8Array(out);
    }
  };
}
if (typeof globalThis.TextDecoder === "undefined") {
  globalThis.TextDecoder = class TextDecoder {
    constructor(encoding) { this.encoding = encoding || "utf-8"; }
    decode(buf) {
      if (!buf) return "";
      const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
      let out = "";
      let i = 0;
      while (i < bytes.length) {
        const b0 = bytes[i++];
        let cp;
        if (b0 < 0x80) cp = b0;
        else if (b0 < 0xE0) cp = ((b0 & 31) << 6) | (bytes[i++] & 63);
        else if (b0 < 0xF0) cp = ((b0 & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
        else cp = ((b0 & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
        out += String.fromCodePoint(cp);
      }
      return out;
    }
  };
}

// ---- btoa / atob (base64, Latin-1 binary strings) ------------------------------
// The Kugou plugins base64-encode device-registration parameters with btoa and
// decode base64 lyrics payloads with atob; QuickJS ships neither.
if (typeof globalThis.btoa === "undefined") {
  const __b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  globalThis.btoa = function (input) {
    const str = String(input);
    let out = "";
    for (let i = 0; i < str.length; i += 3) {
      const hasB = i + 1 < str.length, hasC = i + 2 < str.length;
      const a = str.charCodeAt(i), b = hasB ? str.charCodeAt(i + 1) : 0, c = hasC ? str.charCodeAt(i + 2) : 0;
      if (a > 255 || b > 255 || c > 255) {
        throw new Error("InvalidCharacterError: btoa input has characters outside the Latin-1 range");
      }
      const n = (a << 16) | (b << 8) | c;
      out += __b64chars[(n >> 18) & 63] + __b64chars[(n >> 12) & 63] +
        (hasB ? __b64chars[(n >> 6) & 63] : "=") + (hasC ? __b64chars[n & 63] : "=");
    }
    return out;
  };
  globalThis.atob = function (input) {
    const cleaned = String(input).replace(/[\t\n\f\r ]/g, "");
    if (cleaned.length % 4 === 1 || /[^A-Za-z0-9+/=]/.test(cleaned)) {
      throw new Error("InvalidCharacterError: malformed base64 input to atob");
    }
    const str = cleaned + "===".slice(0, (4 - (cleaned.length % 4)) % 4);
    let out = "";
    let chunk = [];
    for (let i = 0; i < str.length; i += 4) {
      const pad2 = str[i + 2] === "=", pad3 = str[i + 3] === "=";
      const n = (__b64chars.indexOf(str[i]) << 18) |
        (__b64chars.indexOf(str[i + 1]) << 12) |
        ((pad2 ? 0 : __b64chars.indexOf(str[i + 2])) << 6) |
        (pad3 ? 0 : __b64chars.indexOf(str[i + 3]));
      chunk.push((n >> 16) & 255);
      if (!pad2) chunk.push((n >> 8) & 255);
      if (!pad3) chunk.push(n & 255);
      if (chunk.length >= 32768) { out += String.fromCharCode.apply(null, chunk); chunk = []; }
    }
    if (chunk.length) out += String.fromCharCode.apply(null, chunk);
    return out;
  };
}

// ---- AbortController ----------------------------------------------------------
globalThis.AbortController = class AbortController {
  constructor() {
    this.signal = {
      aborted: false,
      onabort: null,
      addEventListener: function () {},
      removeEventListener: function () {},
    };
  }
  abort() {
    if (!this.signal.aborted) {
      this.signal.aborted = true;
      if (this.signal.onabort) this.signal.onabort();
    }
  }
};

// ---- URLSearchParams ------------------------------------------------------------
function __formEncode(s) {
  return encodeURIComponent(String(s))
    .replace(/%20/g, "+")
    .replace(/!/g, "%21").replace(/'/g, "%27")
    .replace(/\(/g, "%28").replace(/\)/g, "%29").replace(/\*/g, "%2A");
}
function __formDecode(s) {
  try { return decodeURIComponent(String(s).replace(/\+/g, " ")); } catch (e) { return String(s); }
}
class URLSearchParams {
  constructor(init) {
    this._pairs = [];
    if (init == null) return;
    if (typeof init === "string") {
      const q = init.startsWith("?") ? init.slice(1) : init;
      if (!q) return;
      for (const part of q.split("&")) {
        if (!part) continue;
        const eq = part.indexOf("=");
        if (eq < 0) this._pairs.push([__formDecode(part), ""]);
        else this._pairs.push([__formDecode(part.slice(0, eq)), __formDecode(part.slice(eq + 1))]);
      }
    } else if (Array.isArray(init)) {
      for (const pair of init) this._pairs.push([String(pair[0]), String(pair[1])]);
    } else if (init instanceof URLSearchParams) {
      this._pairs = init._pairs.map(p => [p[0], p[1]]);
    } else {
      for (const k of Object.keys(init)) this._pairs.push([k, String(init[k])]);
    }
  }
  append(k, v) { this._pairs.push([String(k), String(v)]); }
  set(k, v) {
    const key = String(k); let done = false;
    this._pairs = this._pairs.filter(p => {
      if (p[0] !== key) return true;
      if (!done) { p[1] = String(v); done = true; return true; }
      return false;
    });
    if (!done) this._pairs.push([key, String(v)]);
  }
  get(k) { const p = this._pairs.find(p => p[0] === String(k)); return p ? p[1] : null; }
  getAll(k) { return this._pairs.filter(p => p[0] === String(k)).map(p => p[1]); }
  has(k) { return this._pairs.some(p => p[0] === String(k)); }
  delete(k) { const key = String(k); this._pairs = this._pairs.filter(p => p[0] !== key); }
  toString() { return this._pairs.map(p => __formEncode(p[0]) + "=" + __formEncode(p[1])).join("&"); }
  entries() { return this._pairs[Symbol.iterator](); }
  [Symbol.iterator]() { return this._pairs[Symbol.iterator](); }
}
globalThis.URLSearchParams = URLSearchParams;

// ---- URL (subset: absolute parse + base resolution for plugin routing) ---------
class URL {
  constructor(url, base) {
    let u = String(url);
    if (base !== undefined && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(u)) {
      const b = new URL(base);
      if (u.startsWith("/")) u = b.origin + u;
      else if (u.startsWith("?")) u = b.origin + b.pathname + u;
      else u = b.origin + b.pathname.replace(/[^/]*$/, "") + u;
    }
    const m = /^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(u);
    if (!m) throw new TypeError("Invalid URL: " + u);
    this.protocol = m[1];
    const authority = m[2];
    const colon = authority.lastIndexOf(":");
    if (colon > 0 && /^\d+$/.test(authority.slice(colon + 1))) {
      this.hostname = authority.slice(0, colon);
      this.port = authority.slice(colon + 1);
    } else { this.hostname = authority; this.port = ""; }
    this.host = this.hostname + (this.port ? ":" + this.port : "");
    this.pathname = m[3] || "/";
    this.search = m[4] || "";
    this.hash = m[5] || "";
    this.searchParams = new URLSearchParams(this.search);
  }
  get origin() { return this.protocol + "//" + this.host; }
  get href() { return this.origin + this.pathname + this.search + this.hash; }
  toString() { return this.href; }
}
globalThis.URL = URL;

// ---- fetch over the async native --------------------------------------------------
globalThis.fetch = async function (url, options) {
  options = options || {};
  const headers = {};
  if (options.headers) {
    if (Array.isArray(options.headers)) {
      for (const pair of options.headers) headers[String(pair[0])] = String(pair[1]);
    } else {
      for (const k of Object.keys(options.headers)) headers[k] = String(options.headers[k]);
    }
  }
  const req = {
    method: (options.method || "GET").toUpperCase(),
    url: String(url),
    headers: headers,
    body: options.body == null ? null : String(options.body),
  };
  const res = JSON.parse(await _httpInternal(JSON.stringify(req)));
  if (res.error) throw new TypeError("fetch failed: " + res.error);
  if (options.redirect === "error" && res.status >= 300 && res.status < 400) {
    throw new TypeError("fetch failed: redirect blocked (" + res.status + ")");
  }
  if (options.signal && options.signal.aborted) {
    const e = new Error("The operation was aborted");
    e.name = "AbortError";
    throw e;
  }
  const pairs = res.headers; // [[name, value], ...] duplicates preserved
  const headersObj = {
    get: function (name) {
      const n = String(name).toLowerCase();
      const vs = pairs.filter(p => p[0].toLowerCase() === n).map(p => p[1]);
      return vs.length ? vs.join(", ") : null;
    },
    getSetCookie: function () {
      return pairs.filter(p => p[0].toLowerCase() === "set-cookie").map(p => p[1]);
    },
    has: function (name) {
      const n = String(name).toLowerCase();
      return pairs.some(p => p[0].toLowerCase() === n);
    },
  };
  return {
    ok: res.status >= 200 && res.status < 300,
    status: res.status,
    headers: headersObj,
    json: function () { return JSON.parse(res.body); },
    text: function () { return res.body; },
    // Kugou's device registration AES-decrypts the raw response body, and
    // download-music/qqmusic read binary payloads too. The native side sends
    // bodyBase64 alongside the text body for exactly this.
    arrayBuffer: function () {
      if (typeof res.bodyBase64 === "string") {
        const bin = atob(res.bodyBase64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return bytes.buffer;
      }
      return new TextEncoder().encode(res.body).buffer;
    },
  };
};

// ---- console ----------------------------------------------------------------------
const __logArgs = function (args) { _log(Array.prototype.map.call(args, String).join(" ")); };
globalThis.console = {
  log: function () { __logArgs(arguments); },
  info: function () { __logArgs(arguments); },
  warn: function () { __logArgs(arguments); },
  error: function () { __logArgs(arguments); },
};

// ---- host ----------------------------------------------------------------------------
globalThis.host = {
  platform: "android",
  arch: "arm64",
  dir: "",
  dataDir: "",
  storage: {
    get: function (k) {
      const s = _storageGet(String(k));
      return s == null ? undefined : JSON.parse(s);
    },
    set: function (k, v) { _storageSet(String(k), JSON.stringify(v)); },
    remove: function (k) { _storageRemove(String(k)); },
  },
  language: function () { return _language(); },
  t: function (k) { return _translate(String(k)); },
  setting: function (k) { return _setting(String(k)); },
  openURL: function (u) { _openURL(String(u)); },
  signInPrompt: function (p) { _signInPrompt(JSON.stringify(p || {})); },
  crypto: {
    md5: function (s) { return _md5(String(s)); },
    aesEcbEncrypt: function (k, s) { return _aesEcb(String(k), String(s)); },
  },
  removeDataDir: function () {},
  webSignIn: function () { return Promise.reject(new Error("webSignIn is not available yet")); },
  waitForRedirect: function () { return Promise.reject(new Error("waitForRedirect is not available")); },
};

// ---- call driver: Kotlin invokes __invoke, result arrives via _deliver ----------------
// The id is echoed back so the host can route each result to its own caller.
// Without it, concurrent calls shared one delivery slot and stole each other's
// results (see BUGS.md C1).
//
// The QuickJS<->Kotlin bridge corrupts astral characters (emoji — valid
// UTF-16 surrogate pairs) in strings delivered to the host: results came back
// mangled/truncated and failed to parse on the Kotlin side (bisected with a
// synthetic plugin, 2026-11-11: a 35 KB ASCII result passes, a 1.6 KB result
// containing emoji fails). Escape every code unit outside printable ASCII to
// \uXXXX in the delivery envelope. The envelope is JSON whose non-ASCII
// characters only ever occur inside string literals, so the host parser
// restores the exact original text while the wire form stays pure ASCII.
function __deliverSafe(callId, envelopeJson) {
  _deliver(callId, envelopeJson.replace(/[\u007F-\uFFFF]/g, function (c) {
    return "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0");
  }));
}
globalThis.__invoke = function (name, argsJson, callId) {
  // Synthetic, side-effect-free warm-up used once after module load to absorb
  // the engine's first-result hiccup without hitting a plugin export (the old
  // warm-up called `account`, which is a network call and delayed readiness).
  if (name === "__warmup") {
    __deliverSafe(callId, JSON.stringify({ ok: true, valueJson: "null" }));
    return;
  }
  (async function () {
    try {
      const fn = __exports[name];
      if (typeof fn !== "function") throw new Error("plugin has no export " + name);
      const v = await fn(JSON.parse(argsJson));
      __deliverSafe(callId, JSON.stringify({ ok: true, valueJson: JSON.stringify(v === undefined ? null : v) }));
    } catch (e) {
      __deliverSafe(callId, JSON.stringify({
        ok: false,
        error: String((e && e.message) || e),
        code: (e && e.code) || null,
      }));
    }
  })();
};
