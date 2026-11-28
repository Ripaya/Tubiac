package eu.kanade.tachiyomi.source.model

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import java.io.Serializable
import java.net.URI

enum class UpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

interface SManga : Serializable {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: UpdateStrategy
    var initialized: Boolean
    var memo: JsonObject?
        get() = null
        set(_) {}

    var altTitles: List<String>
        get() = emptyList()
        set(_) {}
    var banner: String?
        get() = null
        set(_) {}
    var language: String?
        get() = null
        set(_) {}
    var contentRating: ContentRating
        get() = ContentRating.SAFE
        set(_) {}
    var score: Int?
        get() = null
        set(_) {}
    var genres: List<String>
        get() = genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        set(value) {
            genre = value.joinToString(", ")
        }
    var readingMode: ReadingMode?
        get() = null
        set(_) {}

    fun setUrlWithoutDomain(url: String) {
        this.url = extractPathAndQuery(url)
    }

    fun copyFrom(other: SManga) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.genres.isNotEmpty()) genres = other.genres
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        if (other.banner != null) banner = other.banner
        if (other.altTitles.isNotEmpty()) altTitles = other.altTitles
        if (other.score != null) score = other.score
        if (other.readingMode != null) readingMode = other.readingMode
        if (other.memo != null) memo = other.memo
        status = other.status
        contentRating = other.contentRating
        if (!initialized) initialized = other.initialized
    }

    enum class ContentRating {
        SAFE,
        SUGGESTIVE,
        ADULT,
    }

    enum class ReadingMode {
        RIGHT_TO_LEFT,
        LEFT_TO_RIGHT,
        LONG_STRIP,
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}

open class SMangaImpl : SManga {
    override lateinit var url: String
    override lateinit var title: String
    override var altTitles: List<String> = emptyList()
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    private var _genres: List<String>? = null
    override var genres: List<String>
        get() = _genres ?: genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        set(value) {
            _genres = value
            if (genre.isNullOrBlank() && value.isNotEmpty()) {
                genre = value.joinToString(", ")
            }
        }
    override var banner: String? = null
    override var language: String? = null
    override var contentRating: SManga.ContentRating = SManga.ContentRating.SAFE
    override var score: Int? = null
    override var readingMode: SManga.ReadingMode? = null
    override var status: Int = SManga.UNKNOWN
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
    override var memo: JsonObject? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SMangaImpl) return false
        return (if (::url.isInitialized) url else null) == (if (other::url.isInitialized) other.url else null) &&
            (if (::title.isInitialized) title else null) == (if (other::title.isInitialized) other.title else null) &&
            status == other.status &&
            update_strategy == other.update_strategy
    }

    override fun hashCode(): Int {
        var result = if (::url.isInitialized) url.hashCode() else 0
        result = 31 * result + (if (::title.isInitialized) title.hashCode() else 0)
        result = 31 * result + status
        result = 31 * result + update_strategy.hashCode()
        return result
    }
}

interface SChapter : Serializable {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?
    var memo: JsonObject?
        get() = null
        set(_) {}

    fun setUrlWithoutDomain(url: String) {
        this.url = extractPathAndQuery(url)
    }

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        memo = other.memo
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}

open class SChapterImpl : SChapter {
    override lateinit var url: String
    override lateinit var name: String
    override var date_upload: Long = 0L
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var memo: JsonObject? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SChapterImpl) return false
        return (if (::url.isInitialized) url else null) == (if (other::url.isInitialized) other.url else null) &&
            (if (::name.isInitialized) name else null) == (if (other::name.isInitialized) other.name else null) &&
            chapter_number == other.chapter_number
    }

    override fun hashCode(): Int {
        var result = if (::url.isInitialized) url.hashCode() else 0
        result = 31 * result + (if (::name.isInitialized) name.hashCode() else 0)
        result = 31 * result + chapter_number.hashCode()
        return result
    }
}

private fun extractPathAndQuery(orig: String): String {
    return try {
        val parsed = URI(orig)
        buildString {
            append(parsed.rawPath.orEmpty().ifEmpty { "/" })
            if (!parsed.rawQuery.isNullOrEmpty()) append("?${parsed.rawQuery}")
            if (!parsed.rawFragment.isNullOrEmpty()) append("#${parsed.rawFragment}")
        }
    } catch (_: Exception) {
        orig
    }
}

/**
 * Manga details + chapters update payload aligned with tachiyomix 1.6 KeiSource ABI.
 */
@Suppress("Unused")
open class SMangaUpdate(
    val manga: suspend () -> SManga,
    val chapters: suspend () -> List<SChapter>,
) {
    constructor(manga: SManga, chapters: List<SChapter> = emptyList()) : this({ manga }, { chapters })
    constructor(manga: SManga, chapters: suspend () -> List<SChapter>) : this({ manga }, chapters)
}

data class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
)

open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var uri: Uri? = null,
) {
    val number: Int
        get() = index + 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Page) return false
        return index == other.index && url == other.url && imageUrl == other.imageUrl && uri == other.uri
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + url.hashCode()
        result = 31 * result + (imageUrl?.hashCode() ?: 0)
        result = 31 * result + (uri?.hashCode() ?: 0)
        return result
    }
}

/**
 * Filter hierarchy ABI-aligned with Mihon/Tachiyomi extensions-lib.
 * All concrete subclasses remain open (never final) so dynamically loaded extensions
 * can either instantiate or subclass any Filter type without VerifyError.
 */
sealed class Filter<T>(val name: String, var state: T) {
    open class Header(name: String) : Filter<Any>(name, 0)
    open class Separator(name: String = "") : Filter<Any>(name, 0)

    open class Select<V>(name: String, val values: Array<V>, state: Int = 0) : Filter<Int>(name, state) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Select<*>
            return name == other.name && state == other.state && values.contentEquals(other.values)
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    open class Text(name: String, state: String = "") : Filter<String>(name, state)
    open class CheckBox(name: String, state: Boolean = false) : Filter<Boolean>(name, state)

    open class TriState(name: String, state: Int = STATE_IGNORE) : Filter<Int>(name, state) {
        fun isIgnored(): Boolean = state == STATE_IGNORE
        fun isIncluded(): Boolean = state == STATE_INCLUDE
        fun isExcluded(): Boolean = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    open class Group<V>(name: String, state: List<V>) : Filter<List<V>>(name, state)

    open class Sort(name: String, val values: Array<String>, state: Selection? = null) :
        Filter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Sort
            return name == other.name && state == other.state && values.contentEquals(other.values)
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Filter<*>
        return name == other.name && state == other.state
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (state?.hashCode() ?: 0)
        return result
    }
}

data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {
    constructor(vararg fs: Filter<*>) : this(fs.asList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return when (other) {
            is FilterList -> list == other.list
            is List<*> -> list == other
            else -> false
        }
    }

    override fun hashCode(): Int = list.hashCode()
}
