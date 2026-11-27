package tube.maniac

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

/**
 * Decodes `content://...#entryName` and `file://...#entryName` pages from CBZ/ZIP/EPUB archives.
 */
class ZipEntryFetcher(
    private val context: Context,
    private val archiveUri: Uri,
    private val entryName: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val stream = if (archiveUri.scheme == "file") {
            val file = archiveUri.path?.let(::File) ?: throw FileNotFoundException("Invalid file URI: $archiveUri")
            FileInputStream(file)
        } else {
            context.contentResolver.openInputStream(archiveUri)
                ?: throw FileNotFoundException("Archive not accessible: $archiveUri")
        }

        val bytes = stream.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == entryName) {
                        return@use zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } ?: throw FileNotFoundException("Entry $entryName not found in $archiveUri")

        val buffer = Buffer().write(bytes)
        val ext = entryName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"

        return SourceFetchResult(
            source = ImageSource(source = buffer, fileSystem = options.fileSystem),
            mimeType = mime,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val raw = data.toString()
            val fragment = raw.substringAfter('#', "")
            if (fragment.isBlank()) return null
            val baseUri = Uri.parse(raw.substringBefore('#'))
            return ZipEntryFetcher(context, baseUri, fragment, options)
        }
    }
}
