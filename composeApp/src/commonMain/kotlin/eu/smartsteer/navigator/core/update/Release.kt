package eu.smartsteer.navigator.core.update

/** Najnowsze wydanie z GitHub Releases (repozytorium publiczne, bez logowania). */
data class ReleaseInfo(
    val tag: String,
    /** Numer budowy (ostatni człon wersji, np. 0.1.42 → 42) — rośnie z każdym przebiegiem CI. */
    val build: Int,
    val version: String,
    val msiUrl: String?,
    val apkUrl: String?,
    val pageUrl: String,
)

object Releases {
    const val LATEST_API = "https://api.github.com/repos/smartsteer/Navigator/releases/latest"
    const val PAGE = "https://github.com/smartsteer/Navigator/releases/latest"

    /** "v0.1.42" / "0.1.42" → 42; null, gdy nie da się odczytać. */
    fun buildNumber(versionOrTag: String): Int? =
        versionOrTag.trim().removePrefix("v").split('.').takeIf { it.size >= 2 }?.last()?.toIntOrNull()

    /** Czy wydanie jest nowsze od zainstalowanej budowy (budowa 0 = wersja deweloperska, bez aktualizacji). */
    fun isNewer(release: ReleaseInfo, currentBuild: Int): Boolean = currentBuild > 0 && release.build > currentBuild

    /** Odczyt odpowiedzi API /releases/latest. Szkice i wersje testowe pomijamy. */
    fun parseLatest(json: String): ReleaseInfo? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        if (root["draft"] == true || root["prerelease"] == true) return null
        val tag = root["tag_name"] as? String ?: return null
        val build = buildNumber(tag) ?: return null
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val url = m["browser_download_url"] as? String ?: return@mapNotNull null
            name to url
        }
        fun asset(ext: String) = assets.firstOrNull { it.first.endsWith(ext, ignoreCase = true) }?.second
        return ReleaseInfo(
            tag = tag,
            build = build,
            version = tag.removePrefix("v"),
            msiUrl = asset(".msi"),
            apkUrl = asset(".apk"),
            pageUrl = root["html_url"] as? String ?: PAGE,
        )
    }
}

/** Minimalny parser JSON (obiekty → Map, tablice → List, liczby → Double), wystarczający do odpowiedzi GitHub API. */
internal object MiniJson {
    fun parse(text: String): Any? = try {
        val p = P(text)
        p.ws()
        p.value().also { p.ws(); if (p.i != text.length) return null }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(): Nothing = throw IllegalArgumentException("JSON @$i")
        fun expect(c: Char) { if (s[i] != c) fail(); i++ }
        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) fail(); i += word.length; return v }

        fun value(): Any? = when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> num()
        }

        fun obj(): Map<String, Any?> {
            expect('{'); ws()
            val m = LinkedHashMap<String, Any?>()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); expect(':'); ws()
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                expect('}'); return m
            }
        }

        fun arr(): List<Any?> {
            expect('['); ws()
            val l = ArrayList<Any?>()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                expect(']'); return l
            }
        }

        fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: fail()
        }
    }
}
