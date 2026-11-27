package eu.kanade.tachiyomi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptor
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.registerInjekt
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

class ExtensionAbiCompatibilityTest {

    @Test
    fun `Filter subclasses HttpSource and ParsedHttpSource are never final in compiled bytecode`() {
        val extendableClasses = listOf(
            Filter.Header::class.java,
            Filter.Separator::class.java,
            Filter.Text::class.java,
            Filter.Select::class.java,
            Filter.CheckBox::class.java,
            Filter.TriState::class.java,
            Filter.Group::class.java,
            Filter.Sort::class.java,
            HttpSource::class.java,
            ParsedHttpSource::class.java,
            NetworkHelper::class.java,
            RateLimitInterceptor::class.java,
            SManga::class.java,
            SChapter::class.java,
            Page::class.java,
        )

        extendableClasses.forEach { clazz ->
            assertFalse(
                "${clazz.name} must not be final in compiled bytecode (causes extension VerifyError)",
                Modifier.isFinal(clazz.modifiers),
            )
        }
    }

    @Test
    fun `Tachiyomi Filter subclasses support inheritance default constructors and equality`() {
        class GenreBox(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
        class SortFilter(values: Array<String>) : Filter.Sort("Sort", values)
        class SelectFilter(values: Array<String>) : Filter.Select<String>("Order", values)
        class TextFilter : Filter.Text("Author")
        class TriFilter : Filter.TriState("Tag")
        class GroupFilter(items: List<Filter.CheckBox>) : Filter.Group<Filter.CheckBox>("Genres", items)

        val f1 = SelectFilter(arrayOf("Latest", "Popular"))
        val f2 = SelectFilter(arrayOf("Latest", "Popular"))
        val f3 = SelectFilter(arrayOf("Latest", "Title"))
        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
        assertNotEquals(f1, f3)

        val sort1 = SortFilter(arrayOf("A-Z", "Views")).apply {
            state = Filter.Sort.Selection(index = 1, ascending = false)
        }
        val sort2 = SortFilter(arrayOf("A-Z", "Views")).apply {
            state = Filter.Sort.Selection(index = 1, ascending = false)
        }
        assertEquals(sort1, sort2)

        val group = GroupFilter(listOf(GenreBox("Action", true)))
        assertEquals(1, group.state.size)
        assertEquals("", TextFilter().state)
        assertTrue(TriFilter().isIgnored())

        val filterList = FilterList(f1, sort1, group)
        assertEquals(3, filterList.size)
    }

    @Test
    fun `ParsedHttpSource HttpSource RateLimitInterceptorKt and util JsoupExtensionsKt ABI signatures match`() {
        val sampleSource = object : ParsedHttpSource() {
            override val name: String = "AbiSample"
            override val baseUrl: String = "https://example.org"
            override val lang: String = "en"
            override val supportsLatest: Boolean = true

            public override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/$page", headers)
            override fun popularMangaSelector(): String = "div.item"
            override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
                title = element.text()
                setUrlWithoutDomain(element.attr("abs:href"))
            }
            override fun popularMangaNextPageSelector(): String? = null
            override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
                POST("$baseUrl/search", headers)
            override fun searchMangaSelector(): String = "div.item"
            override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
            override fun searchMangaNextPageSelector(): String? = null
            override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
            override fun latestUpdatesSelector(): String = "div.item"
            override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
            override fun latestUpdatesNextPageSelector(): String? = null
            override fun mangaDetailsParse(document: Document): SManga = SManga.create()
            override fun chapterListSelector(): String = "a.ch"
            override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
                name = element.text()
                setUrlWithoutDomain(element.attr("href"))
            }
            override fun pageListParse(document: Document): List<Page> = listOf(Page(0, imageUrl = "https://cdn/1.png"))
            override fun imageUrlParse(document: Document): String = "https://cdn/1.png"
        }

        val okClientClass = OkHttpClient.Builder::class.java
        val rateLimitKt = Class.forName("eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt")
        val jsoupExtKt = Class.forName("eu.kanade.tachiyomi.util.JsoupExtensionsKt")
        val okHttpExtKt = Class.forName("eu.kanade.tachiyomi.network.OkHttpExtensionsKt")
        val requestsKt = Class.forName("eu.kanade.tachiyomi.network.RequestsKt")

        assertTrue(rateLimitKt.methods.any { it.name == "rateLimit" && it.parameterTypes.firstOrNull() == okClientClass })
        assertTrue(jsoupExtKt.methods.any { it.name == "asJsoup" && it.parameterTypes.firstOrNull() == Response::class.java })
        assertTrue(okHttpExtKt.methods.any { it.name == "asObservableSuccess" })
        assertTrue(requestsKt.methods.any { it.name == "GET" })

        val req = sampleSource.popularMangaRequest(1)
        assertEquals("https://example.org/popular/1", req.url.toString())
        assertEquals(UpdateStrategy.ALWAYS_UPDATE, SManga.create().update_strategy)
        assertEquals(TimeUnit.SECONDS, TimeUnit.SECONDS)

        val sourceInterfaces = listOf(ConfigurableSource::class.java, UnmeteredSource::class.java, SourceFactory::class.java)
        assertTrue(sourceInterfaces.all { it.isInterface })
        assertTrue(MangasPage(emptyList(), false).mangaListEmptyCheck())
        registerInjekt(NetworkHelper())
        registerInjekt(Json { ignoreUnknownKeys = true })
        val callObservable = sampleSource.client.newCall(req).asObservable()
        val callSuccessObservable = sampleSource.client.newCall(req).asObservableSuccess()
        assertTrue(callObservable != null && callSuccessObservable != null)
    }

    private fun MangasPage.mangaListEmptyCheck(): Boolean = mangas.isEmpty()
}
