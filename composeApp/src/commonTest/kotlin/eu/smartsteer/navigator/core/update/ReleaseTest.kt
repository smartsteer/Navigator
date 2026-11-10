package eu.smartsteer.navigator.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {

    private val sample = """
        {"url":"x","html_url":"https://github.com/smartsteer/Navigator/releases/tag/v0.1.42",
         "id":123,"tag_name":"v0.1.42","name":"SmartSteer Navigator 0.1.42","draft":false,"prerelease":false,
         "body":"Poprawki \"pola\"\nZażółć \u0041",
         "assets":[
           {"name":"SmartSteer-Navigator-0.1.42.apk","size":1.5e7,"browser_download_url":"https://e/a.apk"},
           {"name":"SmartSteer-Navigator-0.1.42.msi","size":61234567,"browser_download_url":"https://e/a.msi"}
         ]}
    """.trimIndent()

    @Test
    fun parsesLatestRelease() {
        val r = assertNotNull(Releases.parseLatest(sample))
        assertEquals("v0.1.42", r.tag)
        assertEquals("0.1.42", r.version)
        assertEquals(42, r.build)
        assertEquals("https://e/a.msi", r.msiUrl)
        assertEquals("https://e/a.apk", r.apkUrl)
        assertEquals("https://github.com/smartsteer/Navigator/releases/tag/v0.1.42", r.pageUrl)
    }

    @Test
    fun rejectsDraftsAndGarbage() {
        assertNull(Releases.parseLatest(sample.replace("\"draft\":false", "\"draft\":true")))
        assertNull(Releases.parseLatest("""{"message":"Not Found"}"""))
        assertNull(Releases.parseLatest("<html>rate limit</html>"))
        assertNull(Releases.parseLatest("{\"tag_name\":"))
    }

    @Test
    fun buildNumbers() {
        assertEquals(42, Releases.buildNumber("v0.1.42"))
        assertEquals(7, Releases.buildNumber("0.1.7"))
        assertNull(Releases.buildNumber("najnowsza"))
    }

    @Test
    fun newerOnlyForHigherBuild() {
        val r = Releases.parseLatest(sample)!!
        assertTrue(Releases.isNewer(r, 41))
        assertFalse(Releases.isNewer(r, 42))
        assertFalse(Releases.isNewer(r, 50))
        assertFalse(Releases.isNewer(r, 0)) // wersja deweloperska
    }

    @Test
    fun miniJsonUnicodeEscape() {
        assertEquals(mapOf("a" to "Ł"), MiniJson.parse("{\"a\":\"\\u0141\"}"))
    }
}
