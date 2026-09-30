package app.baddhu.dms.rules

import android.net.Uri
import android.webkit.WebSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

private val RULE_JSON = Json { encodeDefaults = true }

class RuleEngine {

    private val ref = AtomicReference(Compiled(Rules()))

    fun update(rules: Rules) {
        val current = ref.get()
        if (current.rules == rules) return
        ref.set(Compiled(rules))
    }

    fun homeUrl(): String = ref.get().homeUrl

    fun userAgent(): String? = ref.get().userAgent.ifBlank { null }

    fun script(): String = ref.get().script

    fun errorOverlayScript(): String = ERROR_OVERLAY

    fun applyUserAgent(settings: WebSettings) {
        userAgent()?.let { settings.userAgentString = it }
    }

    fun isAllowedPath(path: String): Boolean = ref.get().allows(path)

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return ref.get().blocksHost(host.lowercase())
    }

    fun isBlockedPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return ref.get().blocksPath(path)
    }

    fun shouldRedirect(uri: Uri, hasSession: Boolean): Boolean {
        if (!hasSession) return false
        if (!Instagram.isHost(uri.host)) return false
        val path = uri.path ?: return false
        return !isAllowedPath(path)
    }

    private data class Compiled(val rules: Rules) {
        val homeUrl: String = rules.homeUrl
        val userAgent: String = rules.userAgent
        val script: String = buildScript(rules)
        private val allow: List<String> = rules.allowPathPrefixes.filter { it.startsWith("/") }
        private val blockedHosts: List<String> =
            rules.blockedHosts.map { it.removePrefix("*.").lowercase() }.filter { it.isNotEmpty() }
        private val blockedPaths: List<String> =
            rules.blockedPathPrefixes.filter { it.startsWith("/") }

        fun allows(path: String): Boolean = allow.any { path.startsWith(it) }

        fun blocksHost(host: String): Boolean = blockedHosts.any { host == it || host.endsWith(".$it") }

        fun blocksPath(path: String): Boolean = blockedPaths.any { path.startsWith(it) }
    }
}

private const val ERROR_OVERLAY = """
(function () {
  if (document.getElementById('__baddhu_error')) { location.reload(); return; }
  var host = document.createElement('div');
  host.id = '__baddhu_error';
  host.setAttribute('style', [
    'position:fixed', 'inset:0', 'z-index:2147483647',
    'background:#101010', 'color:#f5f5f5',
    'display:flex', 'flex-direction:column',
    'align-items:center', 'justify-content:center',
    'font-family:system-ui,sans-serif', 'padding:32px', 'text-align:center'
  ].join(';'));
  var title = document.createElement('div');
  title.textContent = 'Could not reach Instagram';
  title.setAttribute('style', 'font-size:18px;font-weight:600;margin-bottom:8px');
  var body = document.createElement('div');
  body.textContent = 'Check your connection, then try again.';
  body.setAttribute('style', 'font-size:14px;opacity:0.7;margin-bottom:24px');
  var button = document.createElement('button');
  button.textContent = 'Retry';
  button.setAttribute('style', [
    'font-size:15px', 'padding:12px 28px', 'border:0', 'border-radius:8px',
    'background:#0095f6', 'color:#fff', 'cursor:pointer'
  ].join(';'));
  button.onclick = function () { location.reload(); };
  host.appendChild(title);
  host.appendChild(body);
  host.appendChild(button);
  (document.body || document.documentElement).appendChild(host);
})();
"""

@Serializable
private data class ScriptPayload(
    val home: String,
    val allow: List<String>,
    val css: List<String>,
)

private fun String.escaped(): String = replace("\"", "\\\"").replace("'", "\\'")

private fun buildCss(rules: Rules): List<String> = buildList {
    rules.hideSelectors.forEach { selector ->
        if (selector.isNotBlank()) add("$selector{display:none!important}")
    }
    rules.hideExactHrefs.forEach { href ->
        if (href.isBlank()) return@forEach
        add("a[href=\"${href.escaped()}\"]{display:none!important}")
    }
    rules.hideHrefPrefixes.forEach { href ->
        if (href.isBlank()) return@forEach
        add("a[href^=\"${href.escaped()}\"]{display:none!important}")
    }
    rules.hideHrefSuffixes.forEach { href ->
        if (href.isBlank()) return@forEach
        add("a[href${'$'}=\"${href.escaped()}\"]{display:none!important}")
    }
    rules.hideLabels.forEach { label ->
        if (label.isBlank()) return@forEach
        val safe = label.escaped()
        add("[aria-label=\"$safe\"],svg[aria-label=\"$safe\"]{display:none!important}")
        add("[aria-label=\"$safe\"] img,[aria-label=\"$safe\"] svg{display:none!important}")
    }
}

private fun buildScript(rules: Rules): String {
    val payload = RULE_JSON
        .encodeToString(
            ScriptPayload.serializer(),
            ScriptPayload(rules.homeUrl, rules.allowPathPrefixes, buildCss(rules)),
        )
        .replace('\u2028', ' ')
        .replace('\u2029', ' ')

    return """
(function () {
  if (window.__baddhu) { return; }
  window.__baddhu = true;

  var R = $payload;
  var STYLE_ID = '__baddhu_css';
  var pending = 0;
  var reelWatch = 0;
  // How far a finger may drift before a tap becomes a swipe (px). This has to be
  // *tighter* than Chrome's own touch slop, not looser -- see the touchmove
  // handler below.
  var DRAG_SLOP_PX = 6;

  function allowed(path) {
    for (var i = 0; i < R.allow.length; i++) {
      if (path.lastIndexOf(R.allow[i], 0) === 0) { return true; }
    }
    return false;
  }

  function install() {
    if (!document.head) { return; }
    if (document.getElementById(STYLE_ID)) { return; }
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.appendChild(document.createTextNode(R.css.join('\n')));
    document.head.appendChild(style);
  }

  function schedule() {
    if (pending) { return; }
    pending = window.setTimeout(function () {
      pending = 0;
      install();
      syncReel();
    }, 250);
  }

  function findReelPager() {
    var vh = window.innerHeight;
    var vids = document.getElementsByTagName('video');
    var tall = [];
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (r.height >= vh * 0.85) { tall.push(vids[i]); }
    }
    if (tall.length < 2) { return null; }
    // Identify the pager structurally: the nearest scrollable ancestor that holds
    // at least two of the tall videos. Do NOT key off scroll-snap-type here --
    // syncReel disables the snap, so a snap-based test can only ever match once
    // and every later re-assert silently finds nothing.
    var el = tall[0].parentElement;
    while (el && el !== document.documentElement) {
      if (el.scrollHeight > el.clientHeight + 8 && holdsTall(el, tall)) { return el; }
      el = el.parentElement;
    }
    return null;
  }

  function holdsTall(el, tall) {
    var n = 0;
    for (var i = 0; i < tall.length; i++) {
      if (el.contains(tall[i])) { n++; if (n >= 2) { return true; } }
    }
    return false;
  }

  // NOTE: do not resize the reel from here.
  //
  // Instagram lays a DM reel out as (viewport width) x (viewport height - the
  // composer reserve) and positions the caption/rail/audio against that exact
  // box. Measured on a 384x703 viewport it produced a 384x633 box for a 720x1280
  // clip: wider than 9:16, so the clip was pillarboxed (black bars down both
  // sides) or cover-cropped (49px off the top and bottom).
  //
  // Growing the box to the clip's real aspect does fix the crop, but it cannot fix
  // the overlay. The overlay is a parallel subtree still sized to the old box, and
  // every attempt to grow it with the video moved the username cluster to the top
  // of the reel, overlapping the header. So the geometry is now corrected natively
  // in MainActivity, by giving the WebView a viewport whose width makes Instagram's
  // own box exactly 9:16. With that, this function only has to hold the swipe lock.

  function syncReel() {
    var pager = findReelPager();
    if (!pager) { return; }
    pager.style.setProperty('scroll-snap-type', 'none', 'important');
    pager.style.setProperty('overscroll-behavior', 'none', 'important');
  }

  // The mutation-driven sync only fires while Instagram keeps mutating the DOM.
  // Once the reel settles, re-assert on a timer and on viewport changes, or the
  // lock and the object-fit fix silently drop off.
  function startReelWatch() {
    if (reelWatch) { return; }
    reelWatch = window.setInterval(syncReel, 1000);
    window.addEventListener('resize', syncReel);
    window.addEventListener('orientationchange', syncReel);
  }

  function pagerHolds(target) {
    var pager = findReelPager();
    if (!pager) { return false; }
    var el = target;
    while (el && el !== document.documentElement) {
      if (el === pager) { return true; }
      el = el.parentElement;
    }
    return false;
  }

  // Keep the pager from scrolling between reels without killing the controls
  // inside it. preventDefault on touchstart is what used to break every button:
  // cancelling touchstart cancels the browser's default touch handling, so Chrome
  // never synthesises the follow-up click. Mouse still worked, which is why this
  // looked like "only touch is broken". So touchstart only records a position, and
  // only an unambiguous vertical drag gets cancelled.
  //
  // Cancelling the moves is only half the job, though, and getting that half wrong
  // is what made the lock look decorative -- the pager still advanced to the next
  // reel. Two separate leaks, both of which had to go:
  //
  //   1. The handler used to bail out as soon as `dragging` was set, so exactly
  //      ONE touchmove per gesture was ever cancelled. Chrome starts a scroll on
  //      the first touchmove that is both out of its own slop and uncancelled, and
  //      once the scroll has begun it ignores cancellation on later moves. So every
  //      move after the first went straight through to the next reel. A lock has
  //      to hold the whole gesture, not a single event in it.
  //
  //   2. The slop has to be tighter than Chrome's, not looser. Chrome scales its
  //      8dp touch slop by the device pixel ratio -- about 22px on a Pixel 7 -- so
  //      the old 12px threshold left a window (roughly 12px to 22px of movement)
  //      where a move was already out of Chrome's slop but still under ours. That
  //      move went uncancelled, Chrome began the scroll, and per leak 1 nothing
  //      after it could stop it. The lock now engages before Chrome's slop does.
  var dragStartY = -1;
  var dragging = false;
  document.addEventListener('touchstart', function (e) {
    if (!e.touches || !e.touches.length || !pagerHolds(e.target)) {
      dragStartY = -1;
      return;
    }
    dragStartY = e.touches[0].clientY;
    dragging = false;
  }, { passive: true, capture: true });
  document.addEventListener('touchmove', function (e) {
    if (dragStartY < 0) { return; }
    if (!e.touches || !e.touches.length) { return; }
    if (dragging || Math.abs(e.touches[0].clientY - dragStartY) > DRAG_SLOP_PX) {
      dragging = true;
      e.preventDefault();
    }
  }, { passive: false, capture: true });
  document.addEventListener('touchend', function () {
    dragStartY = -1;
    dragging = false;
  }, { passive: true, capture: true });
  document.addEventListener('touchcancel', function () {
    dragStartY = -1;
    dragging = false;
  }, { passive: true, capture: true });

  // A mouse or trackpad never fires touch events at all, so the drag lock above
  // never sees it: a wheel tick inside the pager scrolls it exactly as a swipe
  // did. Same lock, other input path.
  document.addEventListener('wheel', function (e) {
    if (pagerHolds(e.target)) { e.preventDefault(); }
  }, { passive: false, capture: true });

  function guard() {
    syncReel();
    startReelWatch();
    if (allowed(location.pathname)) { return; }
    location.replace(R.home);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }

  if (history.pushState) {
    var push = history.pushState;
    history.pushState = function () {
      push.apply(history, arguments);
      guard();
    };
  }

  if (history.replaceState) {
    var replace = history.replaceState;
    history.replaceState = function () {
      replace.apply(history, arguments);
      guard();
    };
  }

  window.addEventListener('popstate', guard);
  window.addEventListener('hashchange', guard);
  if (window.MutationObserver && document.documentElement) {
    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  }

  guard();
})();
""".trimIndent()
}
