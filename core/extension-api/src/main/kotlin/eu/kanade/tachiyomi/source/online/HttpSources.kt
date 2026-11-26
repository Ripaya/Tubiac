package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.security.MessageDigest

object ExtensionNetworkBridge {
    @Volatile
    var sharedClient: OkHttpClient = OkHttpClient()

    @Volatile
    var userAgentProvider: () -> String = {
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
    }
}

fun GET(url: String, headers: Headers = Headers.headersOf()): Request =
    Request.Builder().url(url).headers(headers).get().build()

fun Response.asJsoup(html: String? = null): Document =
    Jsoup.parse(html ?: body?.string().orEmpty(), request.url.toString())

abstract class HttpSource : CatalogueSource {

    abstract val baseUrl: String
    open val versionId: Int = 1

    override val id: Long by lazy {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    open val client: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    val headers: Headers by lazy { headersBuilder().build() }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", ExtensionNetworkBridge.userAgentProvider())

    open fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http://") || manga.url.startsWith("https://")) manga.url else baseUrl + manga.url

    open fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http://") || chapter.url.startsWith("https://")) chapter.url else baseUrl + chapter.url

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun popularMangaParse(response: Response): MangasPage

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected abstract fun searchMangaParse(response: Response): MangasPage

    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    protected open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    protected abstract fun mangaDetailsParse(response: Response): SManga

    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    protected abstract fun chapterListParse(response: Response): List<SChapter>

    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)
    protected abstract fun pageListParse(response: Response): List<Page>

    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)
    protected abstract fun imageUrlParse(response: Response): String

    open fun imageRequest(page: Page): Request = GET(requireNotNull(page.imageUrl), headers)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        client.newCall(popularMangaRequest(page)).execute().use(::popularMangaParse)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            client.newCall(searchMangaRequest(page, query, filters)).execute().use(::searchMangaParse)
        }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        client.newCall(latestUpdatesRequest(page)).execute().use(::latestUpdatesParse)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        client.newCall(mangaDetailsRequest(manga)).execute().use(::mangaDetailsParse)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        client.newCall(chapterListRequest(manga)).execute().use(::chapterListParse)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        client.newCall(pageListRequest(chapter)).execute().use(::pageListParse)
    }
}

abstract class ParsedHttpSource : HttpSource() {

    protected abstract fun popularMangaSelector(): String
    protected abstract fun popularMangaFromElement(element: Element): SManga
    protected abstract fun popularMangaNextPageSelector(): String?

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map(::popularMangaFromElement)
        val hasNextPage = popularMangaNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun searchMangaSelector(): String
    protected abstract fun searchMangaFromElement(element: Element): SManga
    protected abstract fun searchMangaNextPageSelector(): String?

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map(::searchMangaFromElement)
        val hasNextPage = searchMangaNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun latestUpdatesSelector(): String
    protected abstract fun latestUpdatesFromElement(element: Element): SManga
    protected abstract fun latestUpdatesNextPageSelector(): String?

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map(::latestUpdatesFromElement)
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun mangaDetailsParse(document: Document): SManga
    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected abstract fun chapterListSelector(): String
    protected abstract fun chapterFromElement(element: Element): SChapter
    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select(chapterListSelector()).map(::chapterFromElement)

    protected abstract fun pageListParse(document: Document): List<Page>
    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    protected abstract fun imageUrlParse(document: Document): String
    override fun imageUrlParse(response: Response): String = imageUrlParse(response.asJsoup())
}
