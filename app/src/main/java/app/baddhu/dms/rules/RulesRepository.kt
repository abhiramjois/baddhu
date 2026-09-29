package app.baddhu.dms.rules

import android.content.Context
import app.baddhu.dms.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

object RulesStore {
    private val ref = AtomicReference(Rules())

    val current: Rules get() = ref.get()

    fun update(rules: Rules) {
        ref.set(rules)
    }
}

class RulesRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    fun initial(): Rules {
        val cached = readCache()
        if (cached != null) {
            RulesStore.update(cached)
            return cached
        }
        val bundled = readBundled()
        RulesStore.update(bundled)
        return bundled
    }

    suspend fun refresh(): Rules? = withContext(Dispatchers.IO) {
        val url = BuildConfig.RULES_URL
        if (url.isBlank() || !url.startsWith("https://")) return@withContext null

        val body = download(url) ?: return@withContext null
        val parsed = runCatching { json.decodeFromString<Rules>(body) }.getOrNull() ?: return@withContext null
        if (!parsed.isSane()) return@withContext null
        if (parsed.version < RulesStore.current.version) return@withContext null

        writeCache(parsed)
        RulesStore.update(parsed)
        parsed
    }

    private fun download(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "baddhu/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            if (connection.contentLength > MAX_BYTES) return@runCatching null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.length > MAX_BYTES) null else text
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun readCache(): Rules? {
        val file = File(context.filesDir, CACHE_NAME)
        if (!file.isFile || file.length() > MAX_BYTES.toLong()) return null
        val parsed = runCatching { json.decodeFromString<Rules>(file.readText()) }.getOrNull()
        return parsed?.takeIf { it.isSane() }
    }

    private fun readBundled(): Rules = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { json.decodeFromString<Rules>(it.readText()) }
    }.getOrElse { Rules() }
        .takeIf { it.isSane() } ?: Rules()

    private fun writeCache(rules: Rules) {
        runCatching {
            val target = File(context.filesDir, CACHE_NAME)
            val temp = File(context.filesDir, "$CACHE_NAME.tmp")
            temp.writeText(json.encodeToString(Rules.serializer(), rules))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private companion object {
        const val ASSET_NAME = "rules.json"
        const val CACHE_NAME = "rules-cache.json"
        const val TIMEOUT_MS = 4_000
        const val MAX_BYTES = 256 * 1024
    }
}
