package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

interface Source {
    val id: Long
    val name: String
    val lang: String
        get() = ""
    val language: String
        get() = when (lang) {
            "all" -> "mul"
            "other" -> "und"
            else -> lang
        }

    suspend fun getMangaDetails(manga: SManga): SManga = fetchMangaDetails(manga).toBlocking().first()
    suspend fun getChapterList(manga: SManga): List<SChapter> = fetchChapterList(manga).toBlocking().first()
    suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).toBlocking().first()

    /**
     * Unified Mihon/tachiyomix 1.6 update entrypoint; legacy 1.3-1.5 sources automatically delegate
     * to [getMangaDetails] + [getChapterList], while 1.6 KeiSource extensions override this directly.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean = true,
        fetchChapters: Boolean = true,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            getMangaDetails(manga).also { manga.copyFrom(it) }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) getChapterList(updatedManga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(manga)
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(emptyList())
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(emptyList())
}

interface CatalogueSource : Source {
    override val lang: String
    override val language: String
        get() = when (lang) {
            "all" -> "mul"
            "other" -> "und"
            else -> lang
        }
    val supportsLatest: Boolean

    fun fetchPopularManga(page: Int): Observable<MangasPage>
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>
    fun getFilterList(): FilterList = FilterList()

    suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).toBlocking().first()
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchSearchManga(page, query, filters).toBlocking().first()
    suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).toBlocking().first()
}

interface UnmeteredSource

/**
 * Standard Mihon/Tachiyomi configurable source contract using AndroidX PreferenceScreen.
 */
interface ConfigurableSource : Source {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

interface SourceFactory {
    fun createSources(): List<Source>
}
