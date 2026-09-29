package app.baddhu.dms.web

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.baddhu.dms.rules.Instagram
import app.baddhu.dms.rules.RuleEngine

class DmWebViewClient(
    private val engine: RuleEngine,
    private val onOpenExternally: (Uri) -> Unit,
    private val onRendererGone: (WebView) -> Boolean,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handle(view, request.url)

    private fun handle(view: WebView, uri: Uri): Boolean {
        if (isInstagramHandoff(uri)) {
            return true
        }
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            onOpenExternally(uri)
            return true
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") {
            return false
        }
        if (!Instagram.isHost(uri.host)) {
            onOpenExternally(uri)
            return true
        }
        if (engine.shouldRedirect(uri, hasSession())) {
            view.loadUrl(engine.homeUrl())
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val session = hasSession()
        Log.d(TAG, "nav $url session=$session")
        view.evaluateJavascript(engine.script(), null)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        Log.d(TAG, "main frame error ${error.errorCode} ${error.description} ${request.url}")
        view.evaluateJavascript(engine.errorOverlayScript(), null)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val uri = request.url
        if (engine.isBlockedHost(uri.host)) return blockedResource()
        if (engine.isBlockedPath(uri.path)) return blockedResource()
        return null
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        onRendererGone(view)

    private fun hasSession(): Boolean =
        Instagram.hasSession(CookieManager.getInstance().getCookie(Instagram.SESSION_PROBE_URL))

    private fun isInstagramHandoff(uri: Uri): Boolean {
        if (uri.scheme.equals("instagram", ignoreCase = true)) return true
        if (!uri.scheme.equals("intent", ignoreCase = true)) return false
        val inner = runCatching {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return false
        val target = inner.component?.packageName ?: inner.`package`
        if (target != null && target.contains("instagram", ignoreCase = true)) return true
        val data = inner.data
        return data != null && data.scheme.equals("instagram", ignoreCase = true)
    }

    private companion object {
        const val TAG = "BaddhuNav"
    }
}
