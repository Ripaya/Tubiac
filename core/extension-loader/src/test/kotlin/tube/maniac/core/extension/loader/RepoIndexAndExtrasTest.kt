package tube.maniac.core.extension.loader

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.model.ExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.FullTypeReference
import uy.kohesive.injekt.injectLazy
import uy.kohesive.injekt.registerInjekt
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RepoIndexAndExtrasTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `extractRepoInputUrl decodes tachiyomi add-repo deep link`() {
        val raw = "tachiyomi://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkeiyoushi%2Fextensions%2Frepo%2Findex.min.json"
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            RepoIndexDecoder.extractRepoInputUrl(raw),
        )
    }

    @Test
    fun `candidateIndexUrls prioritizes index_pb alongside legacy min json`() {
        val candidates = RepoIndexDecoder.candidateIndexUrls("https://example.org/ext-repo/")
        assertEquals(
            listOf(
                "https://example.org/ext-repo/repo.json",
                "https://example.org/ext-repo/index.pb",
                "https://example.org/ext-repo/index.min.json",
            ),
            candidates,
        )

        val jsonCandidates = RepoIndexDecoder.candidateIndexUrls("https://example.org/ext-repo/index.min.json")
        assertTrue(jsonCandidates.contains("https://example.org/ext-repo/index.pb"))
        assertTrue(jsonCandidates.contains("https://example.org/ext-repo/repo.json"))
    }

    @Test
    fun `decodeJsonIndex flags Keiyoushi legacy deprecation stub`() {
        val stubJson = """
            [
              {"name":"Outdated App","pkg":"eu.kanade.tachiyomi.extension.all.keiyoushi","apk":"a.apk","lang":"all","code":1,"version":"1.4.1","nsfw":0},
              {"name":"Update to Mihon 0.20.1+","pkg":"eu.kanade.tachiyomi.extension.all.mihon","apk":"b.apk","lang":"all","code":1,"version":"1.4.1","nsfw":0}
            ]
        """.trimIndent()
        val decoded = RepoIndexDecoder.decodeJsonIndex(
            json = json,
            rawJson = stubJson,
            repoBaseUrl = "https://example.org/repo",
            repo = ExtensionRepo(name = "Keiyoushi", indexUrl = "https://example.org/repo", trusted = true),
            installedByPkg = emptyMap(),
        )
        assertTrue(decoded.isLegacyDeprecationStub)
        assertEquals(2, decoded.entries.size)
    }

    @Test
    fun `decodeProtobufIndex parses gzipped v2 Mihon wire payload with assets and sources`() {
        val srcMsg = pbMsg(
            pbVarint(1, 6247824327199706550L),
            pbStr(2, "Asura Scans"),
            pbStr(3, "en"),
            pbStr(4, "https://asurascans.com"),
            pbStr(5, "https://asurascans.net"),
        )
        val assetsMsg = pbMsg(
            pbStr(1, "https://cdn.example.org/asurascans.apk"),
            pbStr(2, "https://cdn.example.org/asurascans.png"),
        )
        val extMsg = pbMsg(
            pbStr(1, "Asura Scans"),
            pbStr(2, "eu.kanade.tachiyomi.extension.en.asurascans"),
            pbBytes(3, assetsMsg),
            pbStr(4, "1.6"),
            pbVarint(5, 106072L),
            pbStr(6, "1.6.72"),
            pbVarint(7, 1L), // safe rating
            pbBytes(8, srcMsg),
        )
        val container101 = pbMsg(pbBytes(1, extMsg))
        val root = pbMsg(
            pbStr(1, "Keiyoushi"),
            pbStr(3, "9add655a"),
            pbBytes(101, container101),
        )

        val gz = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(root) }
        }.toByteArray()

        val decoded = RepoIndexDecoder.decodeProtobufIndex(
            rawBytes = gz,
            fallbackBaseUrl = "https://cdn.example.org",
            repo = ExtensionRepo(name = "Test", indexUrl = "https://cdn.example.org/index.pb", trusted = true),
            installedByPkg = emptyMap(),
        )

        assertEquals("Keiyoushi", decoded.repoName)
        assertEquals("9add655a", decoded.signingFingerprint)
        assertFalse(decoded.isLegacyDeprecationStub)
        assertEquals(1, decoded.entries.size)

        val ext = decoded.entries.first()
        assertEquals("Asura Scans", ext.name)
        assertEquals("eu.kanade.tachiyomi.extension.en.asurascans", ext.pkgName)
        assertEquals("https://cdn.example.org/asurascans.apk", ext.apkUrl)
        assertEquals("https://cdn.example.org/asurascans.png", ext.iconUrl)
        assertEquals(106072L, ext.versionCode)
        assertEquals("1.6.72", ext.versionName)
        assertFalse(ext.isNsfw)
        assertEquals(ExtensionInstallState.AVAILABLE, ext.installState)
        assertEquals(1, ext.sources.size)
        assertEquals("https://asurascans.com", ext.sources.first().baseUrl)
        assertEquals(listOf("https://asurascans.net"), ext.sources.first().mirrorUrls)
    }

    @Test
    fun `parseCommentsFromHtml extracts reader comments and embedded Disqus notice`() {
        val html = """
            <html><body>
              <div id="comments">
                <ol class="comment-list">
                  <li class="comment">
                    <span class="comment-author">ReaderOne</span>
                    <time>2h ago</time>
                    <div class="comment-body"><p>Amazing chapter pacing and panel flow!</p></div>
                  </li>
                </ol>
              </div>
            </body></html>
        """.trimIndent()
        val comments = ExtensionExtrasExtractor.parseCommentsFromHtml(Jsoup.parse(html), "TestSource")
        assertEquals(1, comments.size)
        assertEquals("ReaderOne", comments.first().author)
        assertTrue(comments.first().body.contains("Amazing chapter pacing"))

        val disqusHtml = "<html><body><div id='disqus_thread'></div></body></html>"
        val disqusComments = ExtensionExtrasExtractor.parseCommentsFromHtml(Jsoup.parse(disqusHtml), "TestSource")
        assertEquals(1, disqusComments.size)
        assertTrue(disqusComments.first().body.contains("Disqus"))
    }

    @Test
    fun `Injekt resolves Json via FullTypeReference and injectLazy`() {
        registerInjekt(json)
        val direct = Injekt.getInstance<Json>(object : FullTypeReference<Json>() {}.type)
        val lazyJson: Json by injectLazy()
        assertEquals(json, direct)
        assertEquals(json, lazyJson)
    }

    private fun pbMsg(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        parts.forEach { write(it) }
    }.toByteArray()

    private fun pbStr(fieldNum: Int, s: String): ByteArray = pbBytes(fieldNum, s.toByteArray(Charsets.UTF_8))

    private fun pbBytes(fieldNum: Int, data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeVarint(this, ((fieldNum.toLong() shl 3) or 2L))
        writeVarint(this, data.size.toLong())
        write(data)
    }.toByteArray()

    private fun pbVarint(fieldNum: Int, value: Long): ByteArray = ByteArrayOutputStream().apply {
        writeVarint(this, (fieldNum.toLong() shl 3))
        writeVarint(this, value)
    }.toByteArray()

    private fun writeVarint(out: ByteArrayOutputStream, v: Long) {
        var cur = v
        while (true) {
            if ((cur and 0x7FL.inv()) == 0L) {
                out.write(cur.toInt())
                return
            } else {
                out.write(((cur.toInt() and 0x7F) or 0x80))
                cur = cur ushr 7
            }
        }
    }
}
