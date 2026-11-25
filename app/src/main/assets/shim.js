/* Shim RunSense Android: dipasang sebelum skrip aplikasi. Tidak mengubah kode aplikasi. */
(function () {
  "use strict";
  var N = window.RunSenseNative;
  if (!N || window.__rsShim) return;
  window.__rsShim = true;

  /* ---- fetch lewat Kotlin (tanpa CORS) untuk Intervals.icu dan Open-Meteo ---- */
  var pending = {}, seq = 0;
  var origFetch = window.fetch ? window.fetch.bind(window) : null;
  var PROXY = /^https:\/\/(intervals\.icu|[a-z0-9-]+\.open-meteo\.com)\//i;

  window.__rsHttp = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) {
      try { p.res(JSON.parse(payload)); } catch (e) { p.rej(new TypeError("Respons tidak valid")); }
    } else {
      p.rej(new TypeError(payload || "Failed to fetch"));
    }
  };

  function headersToObj(h) {
    var o = {};
    if (!h) return o;
    if (typeof Headers !== "undefined" && h instanceof Headers) h.forEach(function (v, k) { o[k] = v; });
    else if (Array.isArray(h)) h.forEach(function (p) { o[p[0]] = p[1]; });
    else for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) o[k] = h[k];
    return o;
  }

  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || String(input);
    if (!PROXY.test(url)) return origFetch ? origFetch(input, init) : Promise.reject(new TypeError("fetch tidak tersedia"));
    init = init || {};
    var method = String(init.method || (typeof input !== "string" && input.method) || "GET").toUpperCase();
    var headers = headersToObj(init.headers || (typeof input !== "string" && input.headers));
    var body = init.body == null ? null : String(init.body);
    var signal = init.signal;
    if (signal && signal.aborted) return Promise.reject(new DOMException("Aborted", "AbortError"));
    return new Promise(function (res, rej) {
      var id = ++seq;
      pending[id] = {
        res: function (r) {
          var empty = r.status === 204 || r.status === 205 || r.status === 304;
          res(new Response(empty ? null : r.body, { status: r.status, statusText: r.statusText || "", headers: r.headers }));
        },
        rej: rej
      };
      if (signal) signal.addEventListener("abort", function () {
        if (pending[id]) { delete pending[id]; N.cancel(id); rej(new DOMException("Aborted", "AbortError")); }
      });
      N.http(id, JSON.stringify({ url: url, method: method, headers: headers, body: body }));
    });
  };

  /* ---- Web Share & Clipboard ---- */
  try {
    Object.defineProperty(navigator, "share", {
      configurable: true,
      value: function (d) { d = d || {}; N.share(String(d.title || ""), String(d.text || d.url || "")); return Promise.resolve(); }
    });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: function (t) { N.copy(String(t == null ? "" : t)); return Promise.resolve(); } }
    });
  } catch (e) {}

  /* ---- Warna status bar mengikuti tema aplikasi ---- */
  function pushTheme() {
    N.setTheme(document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark");
  }
  try {
    new MutationObserver(pushTheme).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  } catch (e) {}
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", pushTheme); else pushTheme();

  /* ---- Tombol kembali: tutup modal, lalu kembali ke tab Hari Ini, baru keluar ---- */
  window.__rsBack = function () {
    var m = document.querySelector(".modal-bg.show");
    if (m) {
      var btns = m.querySelectorAll("button"), closed = false;
      for (var i = btns.length - 1; i >= 0; i--) {
        if ((btns[i].textContent || "").trim() === "Tutup") { btns[i].click(); closed = true; break; }
      }
      if (!closed) m.classList.remove("show");
      return true;
    }
    var nav = document.getElementById("bottomNav"), cur = document.querySelector(".screen.active");
    if (nav && nav.style.display !== "none" && cur && cur.id !== "screen-home") {
      var home = nav.querySelector('[data-screen="home"]');
      if (home) { home.click(); return true; }
    }
    return false;
  };

  /* ---- Health Connect: tulis data Intervals.icu (hanya tulis) ---- */
  var HC_KEY = "runsense_auto_v2", HC_AUTO = "rs_hc_auto", HC_SIG = "rs_hc_sig";
  var hcBusy = false, hcTimer = null, hcLast = "";

  function hcPayload() {
    var raw; try { raw = localStorage.getItem(HC_KEY); } catch (e) { return null; }
    if (!raw) return null;
    var d; try { d = JSON.parse(raw); } catch (e) { return null; }
    var cut = Date.now() - 365 * 864e5, cutDay = new Date(cut).toISOString().slice(0, 10);
    var acts = (d.acts || []).filter(function (a) {
      return a && a[0] >= cut && /^icu_/.test(String(a[6] || "")) && Number(a[2]) > 0;
    }).map(function (a) {
      // [start,kind,min,km,avgHR,elev,lid,name,maxHR,icuId,load,cadence]
      return [a[0], String(a[1] || "other"), a[2], a[3], a[5] || 0, String(a[6]), a[7] || "", String(a[12] || a[1] || "other"), Number(a[13] || 0)];
    });
    var well = (d.wellness || []).filter(function (w) { return w && w.id && String(w.id) >= cutDay; })
      .map(function (w) {
        var x = Object.assign({}, w, { id: String(w.id).slice(0, 10) });
        function pick() {
          for (var i = 0; i < arguments.length; i++) {
            var v = x[arguments[i]];
            if (v !== null && v !== undefined && v !== "" && Number.isFinite(Number(v))) return Number(v);
          }
          return 0;
        }
        x.restingHR = pick("restingHR", "resting_hr");
        x.hrv = pick("hrv");
        x.steps = pick("steps", "Steps", "totalSteps", "stepCount");
        x.spO2 = pick("spO2", "spo2");
        x.weight = pick("weight", "weightKg");
        x.bodyFat = pick("bodyFat", "body_fat");
        x.systolic = pick("systolic", "systolicBP", "systolic_bp");
        x.diastolic = pick("diastolic", "diastolicBP", "diastolic_bp");
        x.vo2max = pick("vo2max", "vo2Max", "vo2_max");
        return x;
      });
    var activityStreams = [];
    var actById = {};
    (d.acts || []).forEach(function (a) {
      if (!a || !a[9]) return;
      var id = String(a[9]), h = d.hx && d.hx[id];
      if (!h || !Array.isArray(h.hrSamples) || !h.hrSamples.length) return;
      activityStreams.push({
        id: id, startMs: Number(a[0]) || 0,
        hrSamples: h.hrSamples.filter(function (p) { return p && Number(p.t) > 0 && Number(p.hr) > 40 && Number(p.hr) < 240; }),
        zoneSeconds: Array.isArray(h.zoneSeconds) ? h.zoneSeconds : null,
        hrCoverageSec: Number(h.hrCoverageSec) || 0
      });
      actById[id] = a;
    });
    var strideRows = Object.keys(d.hx || {}).map(function (id) { return Number(d.hx[id] && d.hx[id].walkStrideM); })
      .filter(function (v) { return v >= .35 && v <= 1.15; }).sort(function (a, b) { return a - b; });
    var stride = strideRows.length ? strideRows[Math.floor(strideRows.length / 2)] : 0;
    var dailyDistance = [];
    well.forEach(function (w) {
      if (!(w.steps > 0) || !stride) return;
      var day = String(w.id).slice(0, 10), rows = (d.acts || []).filter(function (a) {
        if (!a || !a[9]) return false;
        var dt = new Date(Number(a[0]));
        var key = dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0") + "-" + String(dt.getDate()).padStart(2, "0");
        return key === day && /run|walk|hike/i.test(String(a[12] || a[1] || ""));
      });
      var complete = rows.length > 0 && rows.every(function (a) {
        var h = d.hx && d.hx[String(a[9])];
        return h && Number(h.activitySteps) > 0 && Number(h.cadenceCoverageSec) >= Math.min(180, Number(a[2]) * 60 * .35);
      });
      if (!complete) return;
      var stepsInActivities = rows.reduce(function (n, a) { return n + Number(d.hx[String(a[9])].activitySteps || 0); }, 0);
      var exactActivityMeters = (d.acts || []).filter(function (a) {
        if (!a || !a[9]) return false;
        var dt = new Date(Number(a[0]));
        var key = dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0") + "-" + String(dt.getDate()).padStart(2, "0");
        return key === day;
      }).reduce(function (n, a) { return n + Math.max(0, Number(a[3]) || 0) * 1000; }, 0);
      var meters = exactActivityMeters + Math.max(0, Number(w.steps) - stepsInActivities) * stride;
      if (meters > 0) dailyDistance.push({ date: day, distanceMeters: Math.round(meters), method: "activity_distance_plus_residual_steps_x_personal_walk_stride" });
    });
    if (!acts.length && !well.length) return null;
    return { acts: acts, wellness: well, activityStreams: activityStreams, dailyDistance: dailyDistance };
  }
  function hcSig(p) {
    // Hash seluruh payload agar perubahan data historis ikut memicu sinkron ulang.
    var s = JSON.stringify(p), h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16) + "|" + s.length;
  }
  function hcSet(msg) { var e = document.getElementById("rsHcState"); if (e) e.textContent = msg; }
  function hcSync(manual) {
    if (hcBusy) return;
    var p = hcPayload();
    if (!p) { if (manual) hcSet("Belum ada data"); return; }
    var sig = hcSig(p);
    if (!manual) { try { if (localStorage.getItem(HC_SIG) === sig) return; } catch (e) {} }
    hcBusy = true; hcLast = sig;
    if (manual) hcSet("Menulis…");
    N.hcWrite(JSON.stringify(p), !!manual);
  }
  window.__rsHcDone = function (ok, msg) {
    hcBusy = false;
    if (ok) { try { localStorage.setItem(HC_SIG, hcLast); } catch (e) {} }
    hcSet(msg);
  };
  window.__rsHcInfo = function (json) {
    var i; try { i = JSON.parse(json); } catch (e) { return; }
    hcSet(i.state === "available" ? (i.granted ? "Siap" : "Belum diizinkan") : i.state === "update" ? "Perlu pembaruan" : "Tidak tersedia");
  };
  function hcAutoOn() { try { return localStorage.getItem(HC_AUTO) === "1"; } catch (e) { return false; } }

  /* Tulis otomatis (bila dinyalakan) setelah data aplikasi disimpan; dirapatkan 8 detik. */
  try {
    var _set = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k, v) {
      _set.apply(this, arguments);
      if (k === HC_KEY && hcAutoOn()) {
        clearTimeout(hcTimer);
        hcTimer = setTimeout(function () { hcSync(false); }, 8000);
      }
    };
  } catch (e) {}

  function mountHc() {
    var anchor = document.getElementById("meSync");
    if (!anchor) return;
    var group = anchor.closest(".sgroup"), actions = group && group.querySelector(".sactions");
    if (!group || !actions) return;
    var row = document.createElement("div");
    row.className = "srow";
    row.innerHTML = '<span class="sic n"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.8 4.6a5.5 5.5 0 00-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 00-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 000-7.8z"/></svg></span>' +
      '<span class="srow-t">Health Connect<span class="srow-s">Tulis lari &amp; wellness dari Intervals.icu</span></span><b class="srow-v" id="rsHcState">—</b>';
    group.insertBefore(row, actions);
    function mk(label, fn) {
      var b = document.createElement("button");
      b.type = "button"; b.className = "btn btn-outline sm"; b.textContent = label; b.onclick = fn;
      actions.appendChild(b); return b;
    }
    mk("Tulis ke Health Connect", function () { hcSync(true); });
    var auto = mk("", function () {
      try { localStorage.setItem(HC_AUTO, hcAutoOn() ? "0" : "1"); } catch (e) {}
      auto.textContent = "Otomatis: " + (hcAutoOn() ? "nyala" : "mati");
    });
    auto.textContent = "Otomatis: " + (hcAutoOn() ? "nyala" : "mati");
    mk("Buka Health Connect", function () { N.hcOpen(); });
    N.hcCheck();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mountHc); else mountHc();

  /* ---- Notifikasi: rsNotify/rsBadge milik halaman (service worker) diganti jalur native ---- */
  try {
    var nativeNotify = function (title, body) {
      try { N.notify(String(title || "RunSense"), String(body || "")); return Promise.resolve(true); }
      catch (e) { return Promise.resolve(false); }
    };
    Object.defineProperty(window, "rsNotify", { configurable: true, get: function () { return nativeNotify; }, set: function () {} });
    Object.defineProperty(window, "rsBadge", { configurable: true, get: function () { return function () {}; }, set: function () {} });
  } catch (e) {}
})();
