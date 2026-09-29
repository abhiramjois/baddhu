package app.baddhu.dms

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import kotlin.math.roundToInt
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.baddhu.dms.rules.Instagram
import app.baddhu.dms.rules.RuleEngine
import app.baddhu.dms.rules.RulesRepository
import app.baddhu.dms.web.DmWebChromeClient
import app.baddhu.dms.web.DmWebViewClient
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val engine = RuleEngine()

    private val pink: Int get() = getColor(R.color.baddhu_pink)

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var webHolder: FrameLayout
    private var webView: WebView? = null
    private var bottomInset = 0
    private var appliedReelWidth = -1
    private var customView: View? = null

    private var pendingCameraUri: Uri? = null
    private var chooserAcceptsMultiple = false

    private val chromeClient = DmWebChromeClient(
        onPickFiles = ::startFileChooser,
        onEnterFullscreen = ::setCustomView,
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> deliverFileResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val repository = RulesRepository(applicationContext)
        engine.update(repository.initial())
        lifecycleScope.launch {
            repository.refresh()?.let { engine.update(it) }
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(pink)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(root)
        applyWindowInsets()

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildHeader())
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        webHolder = FrameLayout(this).apply {
            // side insets come from the WebView's own width; the holder only lifts
            // the reel off the navigation bar
            setPadding(0, 0, 0, dp(BOTTOM_LIFT_DP))
        }
        // the reel frame is a function of the WebView height, so re-derive it whenever
        // that height is re-measured rather than trusting the first pass
        webHolder.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applySideFrame()
        }
        column.addView(
            webHolder,
            LinearLayout.LayoutParams(MATCH, 0, 1f),
        )

        attachWebView(initialLoad = true)
        // no override params here: addView(view, params) would discard the
        // bottomMargin that lifts the tagline off the navigation bar
        column.addView(buildTagline())
        registerBackHandler()
    }

    private fun buildTagline(): View {
        return TextView(this).apply {
            id = R.id.tagline
            text = getString(R.string.baddhu_tagline)
            setTextColor(getColor(R.color.baddhu_ink))
            textSize = TAGLINE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = android.view.Gravity.END
            letterSpacing = 0.04f
            includeFontPadding = false
            maxLines = 2
            setLineSpacing(0f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(TAGLINE_BOTTOM_DP)
            }
        }
    }

    private fun buildHeader(): View {
        val pad = dp(HEADER_PAD_DP)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.START
            setPadding(0, pad, 0, pad)
            id = R.id.header
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.baddhu_wordmark)
                    contentDescription = getString(R.string.baddhu_logo_desc)
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(WORDMARK_H_DP),
                    ).apply {
                        gravity = android.view.Gravity.START
                    }
                },
            )
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bottomInset = bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            applySideFrame()
            insets
        }
    }

    private fun applySideFrame() {
        if (!::column.isInitialized) return

        // 1. size the WebView first, so the shared content edge is known
        if (::webHolder.isInitialized) {
            // The reel width is a function of the WebView height, and the height is
            // not final on the first pass (insets and the tagline settle afterwards),
            // so re-derive it on every layout. Writing only on change keeps that from
            // turning into a requestLayout loop.
            //
            // The width is set on the WebView itself rather than as holder padding:
            // setPadding from inside a layout pass re-measures the child a pass late,
            // which left the WebView stuck at the previous width.
            val want = if (webHolder.height > 0) reelWidthPx() else 0
            val view = webView
            if (want > 0 && want != appliedReelWidth) {
                appliedReelWidth = want
                view?.layoutParams =
                    FrameLayout.LayoutParams(want, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        gravity = android.view.Gravity.CENTER
                    }
            }
        }

        // 2. one vertical edge for the wordmark, the reel and the tagline, so the
        //    lot lines up instead of the logo hanging outside the content column
        val edge = contentEdge()
        column.findViewById<View>(R.id.header)?.setPadding(
            edge,
            dp(HEADER_PAD_DP),
            edge,
            dp(HEADER_PAD_DP),
        )
        column.findViewById<View>(R.id.tagline)?.setPadding(
            edge,
            dp(TAGLINE_PAD_DP),
            // the final glyph's side-bearing + letter-spacing stop short of the
            // advance box, so trim the padding to land the text on the content edge
            (edge - dp(TAGLINE_TRIM_DP)).coerceAtLeast(0),
            0,
        )
    }

    // The screen inset that the content column starts at. The WebView is centred at
    // appliedReelWidth, so both of its edges -- and everything aligned to them --
    // sit this far in.
    private fun contentEdge(): Int {
        val screen = resources.displayMetrics.widthPixels
        val width = if (appliedReelWidth > 0) appliedReelWidth else screen - 2 * dp(MAX_FRAME_DP)
        return ((screen - width) / 2f).roundToInt().coerceAtLeast(0)
    }

    // Instagram lays a DM reel out as
    //     viewportWidth x (viewportHeight - COMPOSER_RESERVE_DP)
    // and hangs the caption, action rail and audio row off that exact box. On a
    // 384x703 viewport that box came out 384x633 -- wider than 9:16 -- so a
    // 720x1280 clip was either pillarboxed (a black bar down each side) or
    // cover-cropped by ~49px. Resizing the box from script fixes the crop but
    // cannot fix the overlay, because the overlay is a parallel subtree still
    // sized to the old box.
    //
    // So make Instagram's own box 9:16 instead: narrow the WebView to
    // (height - reserve) * 9/16 and leave the reel entirely alone. The width comes
    // out near 356dp, which is an ordinary phone width, so the rest of the DM UI
    // lays out exactly as Instagram intends.
    private fun reelWidthPx(): Int {
        val metrics = resources.displayMetrics
        val density = metrics.density
        if (density <= 0f) return 0
        val screenDp = metrics.widthPixels / density
        val viewportDp = webHolder.height.toFloat() / density
        val reelWidthDp = (viewportDp - COMPOSER_RESERVE_DP) * 9f / 16f
        // never wider than the screen, never a useless sliver
        return dp(reelWidthDp.coerceIn(MIN_FRAME_DP, screenDp))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Instagram's reel player renders taller/wider than the layout viewport.
            // With loadWithOverviewMode the WebView scales that overflow down to fit,
            // which visibly shrinks the video; disabling it makes the page scroll
            // instead of rescale. useWideViewPort stays on so the page's
            // width=device-width is honoured.
            loadWithOverviewMode = false
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        engine.applyUserAgent(view.settings)

        view.setBackgroundColor(pink)
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = true
        view.webViewClient = DmWebViewClient(
            engine = engine,
            onOpenExternally = ::openExternally,
            onRendererGone = ::handleRendererGone,
        )
        view.webChromeClient = chromeClient
        return view
    }

    private fun attachWebView(initialLoad: Boolean) {
        val view = createWebView()
        webView = view
        applySideFrame()
        webHolder.addView(
            view,
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        if (initialLoad) {
            view.loadUrl(if (hasSession()) engine.homeUrl() else LOGIN_URL)
        }
    }

    private fun handleRendererGone(dead: WebView): Boolean {
        dead.stopLoading()
        webHolder.removeView(dead)
        dead.destroy()
        webView = null
        attachWebView(initialLoad = true)
        Toast.makeText(this, R.string.error_renderer_gone, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (view != null && view.canGoBack()) {
                        view.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun setCustomView(view: View?) {
        customView?.let { root.removeView(it) }
        customView = view
        view?.let { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }

    private fun hasSession(): Boolean =
        Instagram.hasSession(CookieManager.getInstance().getCookie(Instagram.SESSION_PROBE_URL))

    private fun openExternally(uri: Uri) {
        val target = if (uri.scheme.equals("intent", ignoreCase = true)) {
            intentFallbackUrl(uri) ?: return
        } else {
            uri
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun intentFallbackUrl(uri: Uri): Uri? = runCatching {
        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        val fallback = intent.getStringExtra("browser_fallback_url")
            ?: intent.getStringExtra("S.browser_fallback_url")
        fallback?.let(Uri::parse) ?: intent.data
    }.getOrNull()

    private fun startFileChooser(params: WebChromeClient.FileChooserParams) {
        chooserAcceptsMultiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val pick = runCatching { params.createIntent() }.getOrElse {
            chromeClient.completeFileChooser(null)
            Toast.makeText(this, R.string.error_no_file_picker, Toast.LENGTH_SHORT).show()
            return
        }
        if (chooserAcceptsMultiple) {
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val chooser = Intent.createChooser(pick, getString(R.string.chooser_title))
        newCameraUri()?.let { cameraUri ->
            pendingCameraUri = cameraUri
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }

        runCatching { fileChooserLauncher.launch(chooser) }.onFailure {
            chromeClient.completeFileChooser(null)
            Toast.makeText(this, R.string.error_no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun newCameraUri(): Uri? = runCatching {
        val directory = File(cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("capture_", ".jpg", directory)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()

    private fun deliverFileResult(result: ActivityResult) {
        val cameraUri = pendingCameraUri
        pendingCameraUri = null

        if (result.resultCode != Activity.RESULT_OK) {
            chromeClient.completeFileChooser(null)
            return
        }

        val picked = mutableListOf<Uri>()
        result.data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) picked += clip.getItemAt(index).uri
        }
        if (picked.isEmpty()) {
            result.data?.data?.let { picked += it }
        }
        if (picked.isEmpty() && cameraUri != null) {
            picked += cameraUri
        }

        val out = if (picked.isEmpty()) null else picked.toTypedArray()
        chromeClient.completeFileChooser(out)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        chromeClient.completeFileChooser(null)
        customView?.let { root.removeView(it) }
        customView = null
        webView?.let { view ->
            webHolder.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val MIN_FRAME_DP = 8f
        const val MAX_FRAME_DP = 14f

        // Instagram's DM composer reserve: the reel box is the viewport minus this.
        const val COMPOSER_RESERVE_DP = 69f
        const val BOTTOM_LIFT_DP = 2f
        const val HEADER_PAD_DP = 12f
        const val WORDMARK_H_DP = 56f
        const val TAGLINE_PAD_DP = 6f
        const val TAGLINE_BOTTOM_DP = 12f
        const val TAGLINE_SP = 14f
        const val TAGLINE_TRIM_DP = 1.5f
    }
}
