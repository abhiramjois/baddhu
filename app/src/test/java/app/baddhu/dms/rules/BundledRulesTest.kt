package app.baddhu.dms.rules

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class BundledRulesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private fun bundled(): Rules {
        val candidates = listOf(
            File("src/main/assets/$ASSET"),
            File("app/src/main/assets/$ASSET"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("bundled $ASSET not found from ${File(".").absolutePath}", file != null)
        return json.decodeFromString<Rules>(file!!.readText())
    }

    @Test
    fun bundledRulesAreSane() {
        assertTrue("bundled $ASSET failed isSane()", bundled().isSane())
    }

    @Test
    fun bundledHomeUrlStaysOnInstagram() {
        val host = URI(bundled().homeUrl).host
        assertTrue("homeUrl host is $host", Instagram.isHost(host))
    }

    @Test
    fun bundledAllowListProtectsLoginAndDirect() {
        val allow = bundled().allowPathPrefixes
        for (required in listOf("/direct/", "/accounts/", "/challenge/", "/two_factor/")) {
            assertTrue("allowPathPrefixes is missing $required", required in allow)
        }
    }

    @Test
    fun bundledRulesActuallyHideSomething() {
        val rules = bundled()
        val hiding = rules.hideSelectors.size +
            rules.hideExactHrefs.size +
            rules.hideHrefPrefixes.size +
            rules.hideHrefSuffixes.size +
            rules.hideLabels.size
        assertTrue("bundled rules hide nothing; nav would be fully visible", hiding > 0)
    }

    @Test
    fun sanityCheckRejectsForeignAndInsecureHomeUrls() {
        val base = bundled()
        assertTrue(!base.copy(homeUrl = "https://evil.example/direct/").isSane())
        assertTrue(!base.copy(homeUrl = "http://www.instagram.com/direct/").isSane())
        assertTrue(!base.copy(homeUrl = "https://instagram.com.evil.example/direct/").isSane())
        assertTrue(!base.copy(allowPathPrefixes = listOf("/")).isSane())
        assertTrue(!base.copy(version = 0).isSane())
    }

    private companion object {
        const val ASSET = "rules.json"
    }
}
