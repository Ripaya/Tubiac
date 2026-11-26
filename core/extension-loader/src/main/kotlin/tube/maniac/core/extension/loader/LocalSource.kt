package tube.maniac.core.extension.loader

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import rx.Observable
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ComicInfoMetadata(
    val title: String? = null,
    val summary: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val genre: String? = null,
    val status: Int = SManga.UNKNOWN,
)

object ComicInfoParser {
    fun parse(input: InputStream): ComicInfoMetadata {
        var title: String? = null
        var summary: String? = null
        var writer: String? = null
        var penciller: String? = null
        var genre: String? = null
        var status = SManga.UNKNOWN

        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "Series", "Title" -> if (title == null) title = parser.nextText().trim()
                        "Summary" -> summary = parser.nextText().trim()
                        "Writer" -> writer = parser.nextText().trim()
                        "Penciller" -> penciller = parser.nextText().trim()
                        "Genre" -> genre = parser.nextText().trim()
                        "PublishingStatusTachiyomi" -> {
                            status = when (parser.nextText().trim().lowercase()) {
                                "ongoing" -> SManga.ONGOING
                                "completed" -> SManga.COMPLETED
                                "hiatus" -> SManga.ON_HIATUS
                                "cancelled" -> SManga.CANCELLED
                                else -> SManga.UNKNOWN
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return ComicInfoMetadata(title, summary, writer, penciller, genre, status)
    }
}

/**
 * Storage Access Framework (SAF) Local Source scanning CBZ, CBR, ZIP, EPUB archives
 * and uncompressed image directories, parsing ComicInfo.xml, and ensuring .nomedia markers.
 */
@Singleton
class LocalSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogueSource {

    override val id: Long = LOCAL_SOURCE_ID
    override val name: String = "Local Source"
    override val lang: String = "other"
    override val supportsLatest: Boolean = true

    @Volatile
    private var rootTreeUri: Uri? = null

    fun setRootTreeUri(uriString: String?) {
        rootTreeUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        ensureNoMediaMarker()
    }

    /** Creates a .nomedia marker in the SAF Local Source root so Android MediaStore skips manga pages. */
    fun ensureNoMediaMarker() {
        val root = rootTreeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return
        if (root.findFile(".nomedia") == null) {
            runCatching { root.createFile("application/octet-stream", ".nomedia") }
        }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val root = rootTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: return@fromCallable MangasPage(emptyList(), false)

        val mangas = root.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
            .sortedBy { it.name.orEmpty().lowercase() }
            .map { dir -> directoryToManga(dir) }

        MangasPage(mangas, hasNextPage = false)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val root = rootTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: return@fromCallable MangasPage(emptyList(), false)

        val mangas = root.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .map { dir -> directoryToManga(dir) }

        MangasPage(mangas, hasNextPage = false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val all = fetchPopularManga(1).toBlocking().first().mangas
            val filtered = if (query.isBlank()) all else all.filter {
                it.title.contains(query, ignoreCase = true)
            }
            MangasPage(filtered, hasNextPage = false)
        }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val dirUri = Uri.parse(manga.url)
        val seriesDir = DocumentFile.fromTreeUri(context, dirUri) ?: return@fromCallable emptyList()

        val chapterFiles = seriesDir.listFiles().filter { file ->
            val lower = file.name.orEmpty().lowercase()
            !lower.startsWith(".") && (
                file.isDirectory || SUPPORTED_ARCHIVES.any { lower.endsWith(it) }
                )
        }.sortedByDescending { it.name.orEmpty() }

        chapterFiles.mapIndexed { index, file ->
            SChapter.create().apply {
                url = file.uri.toString()
                name = file.name.orEmpty().substringBeforeLast(".")
                date_upload = file.lastModified()
                chapter_number = extractChapterNumber(name) ?: (chapterFiles.size - index).toFloat()
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val uri = Uri.parse(chapter.url)
        val doc = DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
            ?: return@fromCallable emptyList()

        if (doc.isDirectory) {
            doc.listFiles()
                .filter { isImageFilename(it.name.orEmpty()) }
                .sortedBy { it.name.orEmpty() }
                .mapIndexed { idx, img -> Page(index = idx, url = img.uri.toString(), imageUrl = img.uri.toString(), uri = img.uri) }
        } else {
            val pages = mutableListOf<Page>()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    val entryNames = mutableListOf<String>()
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFilename(entry.name)) {
                            entryNames.add(entry.name)
                        }
                        entry = zis.nextEntry
                    }
                    entryNames.sorted().forEachIndexed { idx, name ->
                        pages.add(Page(index = idx, url = "$uri#$name", imageUrl = "$uri#$name", uri = uri))
                    }
                }
            }
            pages
        }
    }

    private fun directoryToManga(dir: DocumentFile): SManga {
        val cover = dir.listFiles().firstOrNull { file ->
            val n = file.name.orEmpty().lowercase()
            n.startsWith("cover.") && isImageFilename(n)
        }
        val comicInfoFile = dir.findFile("ComicInfo.xml")
        val metadata = comicInfoFile?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use(ComicInfoParser::parse)
        }

        return SManga.create().apply {
            url = dir.uri.toString()
            title = metadata?.title ?: dir.name.orEmpty()
            description = metadata?.summary
            author = metadata?.writer
            artist = metadata?.penciller
            genre = metadata?.genre
            status = metadata?.status ?: SManga.UNKNOWN
            thumbnail_url = cover?.uri?.toString()
            initialized = true
        }
    }

    private fun isImageFilename(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun extractChapterNumber(name: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    companion object {
        const val LOCAL_SOURCE_ID = 0L
        private val SUPPORTED_ARCHIVES = listOf(".cbz", ".zip", ".cbr", ".epub")
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif")
    }
}

@Singleton
class SourceManager @Inject constructor(
    val localSource: LocalSource,
    private val extensionLoader: ExtensionLoader,
) {
    private val _installedExtensions = MutableStateFlow<List<LoadedExtension>>(emptyList())
    val installedExtensions: StateFlow<List<LoadedExtension>> = _installedExtensions.asStateFlow()

    private val _sources = MutableStateFlow<List<Source>>(listOf(localSource))
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    fun reloadExtensions() {
        val loaded = extensionLoader.loadInstalledExtensions()
        _installedExtensions.value = loaded
        _sources.value = listOf(localSource) + loaded.flatMap { it.sources }
    }

    fun getSource(sourceId: Long): Source? =
        _sources.value.firstOrNull { it.id == sourceId }

    fun getCatalogueSources(): List<CatalogueSource> =
        _sources.value.filterIsInstance<CatalogueSource>()
}
