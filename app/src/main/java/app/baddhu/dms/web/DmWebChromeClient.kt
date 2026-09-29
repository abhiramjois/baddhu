package app.baddhu.dms.web

import android.net.Uri
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class DmWebChromeClient(
    private val onPickFiles: (FileChooserParams) -> Unit,
    private val onEnterFullscreen: (View?) -> Unit,
) : WebChromeClient() {

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = filePathCallback
        onPickFiles(fileChooserParams)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) = Unit

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        onEnterFullscreen(view)
    }

    override fun onHideCustomView() {
        onEnterFullscreen(null)
    }

    fun completeFileChooser(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }
}
