package app.baddhu.dms.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_HOME_URL = "https://www.instagram.com/direct/inbox/"

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

val DEFAULT_ALLOW_PATHS = listOf(
    "/direct/",
    "/accounts/",
    "/challenge/",
    "/two_factor/",
    "/p/",
    "/reel/",
    "/stories/",
    "/highlights/",
    "/api/",
)

private const val HTTPS_PREFIX = "https://"

@Serializable
data class Rules(
    val version: Int = 1,
    val updatedAt: String = "",
    @SerialName("homeUrl") val homeUrl: String = DEFAULT_HOME_URL,
    @SerialName("userAgent") val userAgent: String = DEFAULT_USER_AGENT,
    @SerialName("allowPathPrefixes") val allowPathPrefixes: List<String> = DEFAULT_ALLOW_PATHS,
    @SerialName("hideSelectors") val hideSelectors: List<String> = emptyList(),
    @SerialName("hideLabels") val hideLabels: List<String> = emptyList(),
    @SerialName("hideExactHrefs") val hideExactHrefs: List<String> = emptyList(),
    @SerialName("hideHrefPrefixes") val hideHrefPrefixes: List<String> = emptyList(),
    @SerialName("hideHrefSuffixes") val hideHrefSuffixes: List<String> = emptyList(),
    @SerialName("blockedHosts") val blockedHosts: List<String> = emptyList(),
    @SerialName("blockedPathPrefixes") val blockedPathPrefixes: List<String> = emptyList(),
) {
    fun isSane(): Boolean {
        if (version < 1) return false
        if (homeUrl.isBlank()) return false
        if (!homeUrl.startsWith(HTTPS_PREFIX)) return false
        val host = runCatching { java.net.URI(homeUrl).host }.getOrNull() ?: return false
        if (!Instagram.isHost(host)) return false
        if (allowPathPrefixes.none { it.startsWith("/") }) return false
        return !allowPathPrefixes.any { it == "/" }
    }
}

object Instagram {
    fun isHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return value == HOST || value.endsWith(".$HOST")
    }

    const val HOST = "instagram.com"
    const val SESSION_PROBE_URL = "https://www.instagram.com/"

    fun hasSession(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrEmpty()) return false
        return cookieHeader.contains("ds_user_id=") || cookieHeader.contains("sessionid=")
    }
}
