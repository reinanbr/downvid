package com.downvid.downvid.browser

/**
 * Runs at document start in every frame of browser tabs. It reports to the
 * app (object `dvMedia`, a WebMessageListener) the media the page touches:
 *  - fetch/XHR requests to media-looking URLs, and responses whose
 *    Content-Type is a manifest or video (extensionless manifests);
 *  - <video>/<audio>/<source> sources (src setter + MutationObserver);
 *  - MediaSource use (blob: playback, resolved to the latest manifest);
 *  - DRM (requestMediaKeySystemAccess): such media is never downloaded;
 *  - classes and ids of the page's elements ("cls"), for the ad blocker's
 *    generic cosmetic filters: the app answers "css:<rules>" (as Brave);
 *  - "play" events: with `window.__dvAuto` (the "Open videos in the player"
 *    setting) a video the user started is paused at once ("take") and
 *    DownVid's player plays it in its place: the element stays paused and
 *    muted, and its position is reported ("rect", "gone") until the app
 *    answers "release" (or "resume" when it cannot play it; "take:<id>"
 *    starts this for a video the user chose later).
 * Apart from that pause it only observes: hooked calls pass through unchanged.
 */
internal const val INJECTED_JS = """
(function () {
  if (window.__dvHooked) return;
  window.__dvHooked = true;
  var MEDIA = /\.(m3u8?|mpd|mp4|m4v|mov|webm|mkv)(\?|#|$|\/)|mpegurl|format=m3u8/i;
  var CT = /mpegurl|dash\+xml|^video\//i;
  var seen = {};
  function post(o) {
    try { o.page = location.href; o.top = window === window.top; dvMedia.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  function abs(u) { try { return new URL(String(u), location.href).href; } catch (e) { return ''; } }
  function report(u, via, ct) {
    if (!u) return;
    u = String(u);
    if (u.indexOf('blob:') === 0) { post({ t: 'blob', via: via }); return; }
    var a = abs(u);
    // src="" resolves to the page itself.
    if (a.indexOf('http') !== 0 || seen[a] || a === location.href) return;
    if (!ct && !MEDIA.test(a)) return;
    seen[a] = 1;
    post({ t: 'url', url: a, via: via, ct: ct || '' });
  }
  try {
    var of = window.fetch;
    if (of) window.fetch = function (input, init) {
      var u = typeof input === 'string' ? input : (input && input.url);
      report(u, 'fetch');
      var p = of.apply(this, arguments);
      try {
        p.then(function (r) {
          try { var ct = r.headers.get('content-type') || ''; if (CT.test(ct)) report(r.url || u, 'fetch', ct); } catch (e) {}
        }, function () {});
      } catch (e) {}
      return p;
    };
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      var x = this;
      report(u, 'xhr');
      try {
        x.addEventListener('load', function () {
          try { var ct = x.getResponseHeader('content-type') || ''; if (CT.test(ct)) report(x.responseURL || u, 'xhr', ct); } catch (e) {}
        });
      } catch (e) {}
      return oo.apply(this, arguments);
    };
  } catch (e) {}
  try {
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      configurable: true, enumerable: d.enumerable, get: d.get,
      set: function (v) { report(v, 'src', this instanceof HTMLVideoElement ? 'video/*' : ''); return d.set.call(this, v); }
    });
  } catch (e) {}
  try {
    var ocu = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      try { if (window.MediaSource && o instanceof MediaSource) post({ t: 'mse' }); } catch (e) {}
      return ocu.apply(this, arguments);
    };
  } catch (e) {}
  try {
    if (navigator.requestMediaKeySystemAccess) {
      var orm = navigator.requestMediaKeySystemAccess;
      navigator.requestMediaKeySystemAccess = function (ks) {
        window.__dvDrm = true;
        post({ t: 'drm', ks: String(ks) });
        return orm.apply(navigator, arguments);
      };
    }
  } catch (e) {}
  function addCss(css) {
    try {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
    } catch (e) {
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    }
  }
  var tokSeen = {}, tokQ = [], tokT = 0;
  function tok(el) {
    if (window.__dvGenericHide) return;
    var c = el.classList, k;
    if (c) for (var i = 0; i < c.length; i++) { k = '.' + c[i]; if (!tokSeen[k]) { tokSeen[k] = 1; tokQ.push(k); } }
    if (el.id && typeof el.id === 'string') { k = '#' + el.id; if (!tokSeen[k]) { tokSeen[k] = 1; tokQ.push(k); } }
    if (tokQ.length && !tokT) tokT = setTimeout(sendTok, 100);
  }
  function sendTok() { tokT = 0; if (!tokQ.length) return; post({ t: 'cls', v: tokQ.join('\n') }); tokQ = []; }
  function tokAll(root) {
    try { if (root.nodeType === 1) tok(root); if (root.querySelectorAll) root.querySelectorAll('[class],[id]').forEach(tok); } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', function () { tokAll(document); }, { once: true });
  function scan(root) {
    try {
      root.querySelectorAll('video[src],source[src]').forEach(function (el) {
        report(el.src, 'dom', el.tagName === 'VIDEO' || (el.parentNode && el.parentNode.tagName === 'VIDEO') ? 'video/*' : '');
      });
    } catch (e) {}
  }
  try {
    new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.type === 'attributes') {
          if (m.attributeName !== 'src') tok(m.target);
          else if (m.target.src) report(m.target.src, 'dom', m.target.tagName === 'VIDEO' ? 'video/*' : '');
          continue;
        }
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (n.nodeType !== 1) continue;
          if (n.tagName === 'VIDEO' || n.tagName === 'SOURCE') scan(n.parentNode || n); else if (n.querySelectorAll) scan(n);
          if (document.readyState !== 'loading') tokAll(n);
        }
      }
    }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src', 'class', 'id'] });
  } catch (e) {}
  // DownVid's player plays a taken video in place: the page's element stays
  // paused and silent under it, and its position (in top-frame document CSS
  // px, relayed through parent frames) is reported as the page moves.
  var vids = {}, nextVid = 1, track = null, relayed = null, rafOn = false;
  function vidOf(v) {
    if (!v.__dvId) { v.__dvId = Math.random().toString(36).slice(2) + (nextVid++); vids[v.__dvId] = v; }
    return v.__dvId;
  }
  // Rects are [x, y, w, h, cx, cy, cw, ch] in the frame's viewport px: the
  // video element's box, and the part of it its ancestors (overflow other
  // than visible, the iframes it is in) let through. Players often size the
  // element past a rounded or fixed-ratio container that crops it; DownVid's
  // player is laid out at the element's size and cropped the same way.
  var lastTop = '';
  function topRect(r, id) {
    var vv = window.visualViewport, sx = window.scrollX, sy = window.scrollY;
    var o = { t: 'rect', id: id, x: r[0] + sx, y: r[1] + sy, w: r[2], h: r[3], cx: r[4] + sx, cy: r[5] + sy, cw: r[6], ch: r[7], k: (window.devicePixelRatio || 1) * (vv ? vv.scale : 1) };
    var key = [o.id, o.x, o.y, o.w, o.h, o.cx, o.cy, o.cw, o.ch, o.k].join(',');
    if (key === lastTop) return;
    lastTop = key;
    post(o);
  }
  function sendRect(r, id) {
    if (window === window.top) topRect(r, id);
    else try { parent.postMessage({ __dvRect: r, id: id }, '*'); } catch (e) {}
  }
  function clipTo(r, x, y, w, h) {
    var l = Math.max(r[4], x), t = Math.max(r[5], y);
    var rr = Math.min(r[4] + r[6], x + w), b = Math.min(r[5] + r[7], y + h);
    r[4] = l; r[5] = t; r[6] = Math.max(0, rr - l); r[7] = Math.max(0, b - t);
  }
  // A clip too small to be the player is a layout quirk (an absolutely
  // positioned player under a collapsed box), not a crop: ignore it.
  function sane(r) {
    if (r[6] < 40 || r[7] < 40) { r[4] = r[0]; r[5] = r[1]; r[6] = r[2]; r[7] = r[3]; }
    return r;
  }
  function relay() {
    if (!relayed) return;
    var el = relayed.el, r = relayed.r;
    if (!el.isConnected) { relayed = null; return; }
    var b = el.getBoundingClientRect();
    var ox = b.left + el.clientLeft, oy = b.top + el.clientTop;
    var o = [r[0] + ox, r[1] + oy, r[2], r[3], r[4] + ox, r[5] + oy, r[6], r[7]];
    clipTo(o, ox, oy, el.clientWidth || b.width, el.clientHeight || b.height);
    sendRect(sane(o), relayed.id);
  }
  // Ancestors that crop the element (crossing shadow roots and slots).
  function clippers(v) {
    var out = [], el = v, n = 0;
    while (el && n++ < 40) {
      el = el.assignedSlot || el.parentElement || (el.parentNode && el.parentNode.host) || null;
      if (!el || el === document.body || el === document.documentElement) break;
      try {
        var cs = getComputedStyle(el);
        if ((cs.overflowX !== 'visible' || cs.overflowY !== 'visible') && cs.display !== 'inline' && cs.display !== 'contents') out.push(el);
      } catch (e) {}
    }
    return out;
  }
  function rectOf(v, clip) {
    var b = v.getBoundingClientRect();
    var r = [b.left, b.top, b.width, b.height, b.left, b.top, b.width, b.height];
    for (var i = 0; i < clip.length; i++) {
      if (!clip[i].isConnected) continue;
      var c = clip[i].getBoundingClientRect();
      clipTo(r, c.left + clip[i].clientLeft, c.top + clip[i].clientTop, clip[i].clientWidth, clip[i].clientHeight);
    }
    return sane(r);
  }
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || !d.__dvRect || !e.source) return;
    var fs = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < fs.length; i++) {
      if (fs[i].contentWindow === e.source) { relayed = { el: fs[i], r: d.__dvRect, id: d.id }; relay(); return; }
    }
  });
  function reportNow() {
    rafOn = false;
    if (track) {
      var v = track.v;
      if (!v.isConnected) { post({ t: 'gone', id: track.id }); stopTrack(); }
      else sendRect(rectOf(v, track.clip), track.id);
    }
    relay();
  }
  function sched() { if (!rafOn && (track || relayed)) { rafOn = true; requestAnimationFrame(reportNow); } }
  document.addEventListener('scroll', sched, { capture: true, passive: true });
  window.addEventListener('resize', sched);
  function startTrack(v) {
    if (track && track.v === v) { try { v.pause(); } catch (e) {} lastTop = ''; sched(); return; }
    stopTrack();
    track = { v: v, id: vidOf(v), muted: v.muted, clip: clippers(v) };
    try { v.pause(); v.muted = true; } catch (e) {}
    try { track.ro = new ResizeObserver(sched); track.ro.observe(v); } catch (e) {}
    track.timer = setInterval(function () { if (track) track.clip = clippers(track.v); sched(); }, 700);
    lastTop = '';
    sched();
  }
  function stopTrack() {
    if (!track) return;
    try { track.ro && track.ro.disconnect(); } catch (e) {}
    clearInterval(track.timer);
    try { track.v.muted = track.muted; } catch (e) {}
    track = null;
  }
  // A video the user means to watch, not a muted preview, background or
  // hidden ad element.
  function wanted(v) {
    var active = navigator.userActivation ? navigator.userActivation.isActive : true;
    if (v.muted && (!active || v.loop)) return false;
    if (isFinite(v.duration) && v.duration > 0 && v.duration < 10) return false;
    var r = v.getBoundingClientRect();
    return r.width >= 120 && r.height >= 68;
  }
  function onPlay(e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    // Ours is playing it: the page's element stays paused.
    if (track && track.v === v) { try { v.pause(); } catch (x) {} return; }
    var take = !!window.__dvAuto && !window.__dvDrm && wanted(v);
    if (take) { startTrack(v); window.__dvLast = v; }
    post({ t: 'play', id: vidOf(v), take: take, drm: !!window.__dvDrm, src: v.currentSrc || v.src || '', w: v.videoWidth || 0, h: v.videoHeight || 0, dur: isFinite(v.duration) ? v.duration : 0, pos: v.currentTime || 0 });
  }
  document.addEventListener('play', onPlay, true);
  // "play" is not composed: a <video> inside a web component (shadow DOM,
  // e.g. archive.org's player) never reaches the document's listener.
  try {
    var oas = Element.prototype.attachShadow;
    if (oas) Element.prototype.attachShadow = function () {
      var root = oas.apply(this, arguments);
      try { root.addEventListener('play', onPlay, true); } catch (e) {}
      return root;
    };
  } catch (e) {}
  window.__dvPauseAll = function () {
    document.querySelectorAll('video,audio').forEach(function (v) { try { v.pause(); } catch (e) {} });
  };
  try {
    dvMedia.addEventListener('message', function (e) {
      var d = e.data;
      if (typeof d !== 'string') return;
      if (d.indexOf('css:') === 0) addCss(d.slice(4));
      else if (d.indexOf('take:') === 0) {
        var tv = vids[d.slice(5)];
        // Where the page's video is now: it kept playing while DownVid
        // offered its player.
        if (tv) { post({ t: 'at', id: d.slice(5), pos: tv.currentTime || 0 }); startTrack(tv); }
      }
      else if (d === 'release') stopTrack();
      else if (d === 'resume') { var last = track ? track.v : window.__dvLast; stopTrack(); if (last) { var p = last.play(); if (p && p.catch) p.catch(function () {}); } }
      else if (d === 'pause') window.__dvPauseAll();
    });
  } catch (e) {}
})();
"""

/** Pauses page media in every frame that ran [INJECTED_JS] (the top frame only via evaluateJavascript). */
internal const val PAUSE_JS = "window.__dvPauseAll && window.__dvPauseAll();"

/** Defines `window.__dvAuto` (read by [INJECTED_JS] when a video starts). */
internal fun autoPlayerJs(on: Boolean) = "window.__dvAuto=$on;"

/**
 * Document-start script of one origin (as Brave's cosmetic filtering): CSS
 * hiding the site's ad elements, through a constructed stylesheet (page CSP
 * does not apply to it), and the site's scriptlets.
 */
internal fun cosmeticJs(c: Cosmetic): String = buildString {
    append("(function(){if(window.__dvCosmetic)return;window.__dvCosmetic=1;")
    if (c.genericHide) append("window.__dvGenericHide=true;")
    if (c.css.isNotEmpty()) {
        append("try{var s=new CSSStyleSheet();s.replaceSync(")
        append(org.json.JSONObject.quote(c.css))
        append(");document.adoptedStyleSheets=document.adoptedStyleSheets.concat([s]);}catch(e){}")
    }
    append("})();")
    if (c.script.isNotEmpty()) {
        append("\nif(!window.__dvScriptlets){window.__dvScriptlets=1;try{\n").append(c.script).append("\n}catch(e){}}")
    }
}
