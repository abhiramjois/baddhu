package app.baddhu.dms.web

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

private val EMPTY = ByteArray(0)

fun blockedResource(mimeType: String = "text/plain"): WebResourceResponse =
    WebResourceResponse(
        mimeType,
        "utf-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(EMPTY),
    )
