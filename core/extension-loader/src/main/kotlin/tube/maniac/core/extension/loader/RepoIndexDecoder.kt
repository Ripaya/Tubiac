package tube.maniac.core.extension.loader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.model.ExtensionRepo
import tube.maniac.core.model.ExtensionSourceMeta
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

@Serializable
data class RepoMetaManifestDto(
    @SerialName("index_v2") val indexV2: String? = null,
    @SerialName("index_v1") val indexV1: String? = null,
    val meta: RepoMetaBlockDto? = null,
)

@Serializable
data class RepoMetaBlockDto(
    val name: String? = null,
    val website: String? = null,
    val signingKeyFingerprint: String? = null,
)

@Serializable
data class RepoIndexEntryDto(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String = "all",
    val code: Long = 1L,
    val version: String = "1.0.0",
    val nsfw: Int = 0,
    @SerialName("sources") val sourcesList: List<RepoSourceDto> = emptyList(),
)

@Serializable
data class RepoSourceDto(
    val name: String = "",
    val lang: String = "all",
    val id: String = "0",
    val baseUrl: String = "",
)

data class DecodedRepoIndex(
    val repoName: String? = null,
    val signingFingerprint: String? = null,
    val entries: List<ExtensionInfo>,
    val isLegacyDeprecationStub: Boolean = false,
)

/**
 * Pure Kotlin multi-format extension repository parser & URL normalizer supporting:
 * - Modern Mihon v2 `repo.json` manifests pointing to `index.pb`
 * - Wire-exact Protobuf `index.pb` (both gzipped and raw wire streams, with v2 `#101` envelope)
 * - Legacy `index.min.json` (plus detection of Keiyoushi/Mihon deprecation stubs)
 * - `tachiyomi://add-repo?url=...` deep link URL unwrapping
 */
object RepoIndexDecoder {

    private val STUB_PACKAGES = setOf(
        "eu.kanade.tachiyomi.extension.all.keiyoushi",
        "eu.kanade.tachiyomi.extension.all.mihon",
    )

    fun extractRepoInputUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("tachiyomi://add-repo", ignoreCase = true) ||
            trimmed.startsWith("mihon://add-repo", ignoreCase = true)
        ) {
            val query = trimmed.substringAfter("?", "")
            val urlParam = query.split("&")
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    if (idx <= 0) null else kv.substring(0, idx) to kv.substring(idx + 1)
                }
                .firstOrNull { it.first == "url" }
                ?.second
            if (!urlParam.isNullOrBlank()) {
                return runCatching { URLDecoder.decode(urlParam, "UTF-8") }.getOrDefault(urlParam).trim()
            }
        }
        return trimmed
    }

    /**
     * Generates prioritized candidate index URLs for a repository input URL.
     * Supports bare directory URLs, repo.json, index.pb, and index.min.json.
     */
    fun candidateIndexUrls(rawInput: String): List<String> {
        val clean = extractRepoInputUrl(rawInput).trim().trimEnd('/')
        if (clean.isBlank()) return emptyList()
        return when {
            clean.endsWith("repo.json", ignoreCase = true) -> listOf(clean)
            clean.endsWith(".pb", ignoreCase = true) -> listOf(clean)
            clean.endsWith(".json", ignoreCase = true) -> {
                val base = clean.substringBeforeLast("/")
                listOf(clean, "$base/index.pb", "$base/repo.json").distinct()
            }
            else -> listOf(
                "$clean/repo.json",
                "$clean/index.pb",
                "$clean/index.min.json",
            )
        }
    }

    fun decodeJsonIndex(
        json: Json,
        rawJson: String,
        repoBaseUrl: String,
        repo: ExtensionRepo,
        installedByPkg: Map<String, ExtensionInfo>,
    ): DecodedRepoIndex {
        val entries = json.decodeFromString<List<RepoIndexEntryDto>>(rawJson)
        val isStub = entries.isNotEmpty() && entries.all { it.pkg in STUB_PACKAGES }
        val mapped = entries.map { dto ->
            val resolvedApkUrl = when {
                dto.apk.startsWith("http://") || dto.apk.startsWith("https://") -> dto.apk
                else -> "$repoBaseUrl/apk/${dto.apk}"
            }
            val installed = installedByPkg[dto.pkg]
            val state = computeInstallState(repo, dto.code, installed)
            ExtensionInfo(
                pkgName = dto.pkg,
                name = dto.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: "),
                lang = dto.lang,
                versionName = dto.version,
                versionCode = dto.code,
                isNsfw = dto.nsfw >= 1,
                apkName = dto.apk,
                apkUrl = resolvedApkUrl,
                iconUrl = "$repoBaseUrl/icon/${dto.pkg}.png",
                repoBaseUrl = repoBaseUrl,
                sources = dto.sourcesList.map { src ->
                    ExtensionSourceMeta(
                        id = src.id.toLongOrNull() ?: 0L,
                        name = src.name,
                        lang = src.lang,
                        baseUrl = src.baseUrl,
                    )
                },
                installState = state,
                installedVersionCode = installed?.versionCode,
            )
        }
        return DecodedRepoIndex(entries = mapped, isLegacyDeprecationStub = isStub)
    }

    /**
     * Wire-exact decoder for Mihon `index.pb` (auto-unwraps gzip 0x1F 0x8B header if present).
     * Root fields: #1 name, #2 shortName, #3 signingKeyFingerprint, #101 v2 Extension container.
     * Extension entry: #1 name, #2 pkg, #3 assets(#1 apkUrl, #2 iconUrl), #4 libVersion,
     * #5 versionCode, #6 versionName, #7 contentRating (1=safe,2=suggestive,3=nsfw), #8 sources.
     */
    fun decodeProtobufIndex(
        rawBytes: ByteArray,
        fallbackBaseUrl: String,
        repo: ExtensionRepo,
        installedByPkg: Map<String, ExtensionInfo>,
    ): DecodedRepoIndex {
        val wire = gunzipIfNeeded(rawBytes)
        val topFields = parseWireFields(wire, 0, wire.size)

        var repoName: String? = null
        var fingerprint: String? = null
        val entryChunks = mutableListOf<ByteArray>()

        for (field in topFields) {
            when {
                field.number == 1 && field.wireType == WIRE_BYTES -> {
                    val text = field.bytes.toString(Charsets.UTF_8)
                    // Distinguish root repo name string vs v1 raw repeated Extension message
                    if (text.all { it.code in 32..126 } && !field.bytes.looksLikeExtensionMsg()) {
                        repoName = text
                    } else {
                        entryChunks.add(field.bytes)
                    }
                }
                field.number == 3 && field.wireType == WIRE_BYTES -> {
                    fingerprint = field.bytes.toString(Charsets.UTF_8)
                }
                field.number in setOf(100, 101) && field.wireType == WIRE_BYTES -> {
                    val container = parseWireFields(field.bytes, 0, field.bytes.size)
                    container.filter { it.number == 1 && it.wireType == WIRE_BYTES }
                        .forEach { entryChunks.add(it.bytes) }
                }
            }
        }

        val mapped = entryChunks.mapNotNull { chunk ->
            parseProtoExtensionEntry(chunk, fallbackBaseUrl, repo, installedByPkg)
        }
        return DecodedRepoIndex(
            repoName = repoName,
            signingFingerprint = fingerprint,
            entries = mapped,
            isLegacyDeprecationStub = false,
        )
    }

    private fun parseProtoExtensionEntry(
        bytes: ByteArray,
        fallbackBaseUrl: String,
        repo: ExtensionRepo,
        installedByPkg: Map<String, ExtensionInfo>,
    ): ExtensionInfo? {
        val fields = parseWireFields(bytes, 0, bytes.size)
        var name = ""
        var pkg = ""
        var apkUrl: String? = null
        var iconUrl: String? = null
        var libVersion: String? = null
        var versionCode = 1L
        var versionName = "1.0.0"
        var contentRating = 0
        val sources = mutableListOf<ExtensionSourceMeta>()

        for (f in fields) {
            when (f.number) {
                1 -> if (f.wireType == WIRE_BYTES) name = f.bytes.toString(Charsets.UTF_8)
                2 -> if (f.wireType == WIRE_BYTES) pkg = f.bytes.toString(Charsets.UTF_8)
                3 -> if (f.wireType == WIRE_BYTES) {
                    val assetFields = parseWireFields(f.bytes, 0, f.bytes.size)
                    for (af in assetFields) {
                        when (af.number) {
                            1 -> if (af.wireType == WIRE_BYTES) apkUrl = af.bytes.toString(Charsets.UTF_8)
                            2 -> if (af.wireType == WIRE_BYTES) iconUrl = af.bytes.toString(Charsets.UTF_8)
                        }
                    }
                }
                4 -> if (f.wireType == WIRE_BYTES) libVersion = f.bytes.toString(Charsets.UTF_8)
                5 -> if (f.wireType == WIRE_VARINT) versionCode = f.varint
                6 -> if (f.wireType == WIRE_BYTES) versionName = f.bytes.toString(Charsets.UTF_8)
                7 -> if (f.wireType == WIRE_VARINT) contentRating = f.varint.toInt()
                8 -> if (f.wireType == WIRE_BYTES) {
                    parseProtoSource(f.bytes)?.let(sources::add)
                }
            }
        }

        if (pkg.isBlank() || name.isBlank()) return null

        val lang = sources.firstOrNull { it.lang.isNotBlank() }?.lang
            ?: extractLangFromPkg(pkg)
        val installed = installedByPkg[pkg]
        val state = computeInstallState(repo, versionCode, installed)
        val resolvedApk = apkUrl?.takeIf { it.isNotBlank() }
        val resolvedApkName = resolvedApk?.substringAfterLast("/")

        return ExtensionInfo(
            pkgName = pkg,
            name = name.removePrefix("Tachiyomi: ").removePrefix("Mihon: "),
            lang = lang,
            versionName = versionName,
            versionCode = versionCode,
            // ContentRating: 1=Safe, 2=Suggestive, 3=NSFW
            isNsfw = contentRating >= 3,
            apkName = resolvedApkName,
            apkUrl = resolvedApk,
            iconUrl = iconUrl ?: "$fallbackBaseUrl/icon/$pkg.png",
            repoBaseUrl = fallbackBaseUrl,
            libVersion = libVersion,
            sources = sources,
            installState = state,
            installedVersionCode = installed?.versionCode,
        )
    }

    private fun parseProtoSource(bytes: ByteArray): ExtensionSourceMeta? {
        val fields = parseWireFields(bytes, 0, bytes.size)
        var id = 0L
        var name = ""
        var lang = "all"
        var baseUrl = ""
        val mirrors = mutableListOf<String>()
        for (f in fields) {
            when (f.number) {
                1 -> if (f.wireType == WIRE_VARINT) id = f.varint
                2 -> if (f.wireType == WIRE_BYTES) name = f.bytes.toString(Charsets.UTF_8)
                3 -> if (f.wireType == WIRE_BYTES) lang = f.bytes.toString(Charsets.UTF_8)
                4 -> if (f.wireType == WIRE_BYTES) baseUrl = f.bytes.toString(Charsets.UTF_8)
                5 -> if (f.wireType == WIRE_BYTES) mirrors.add(f.bytes.toString(Charsets.UTF_8))
            }
        }
        if (name.isBlank()) return null
        return ExtensionSourceMeta(
            id = id,
            name = name,
            lang = lang.ifBlank { "all" },
            baseUrl = baseUrl,
            mirrorUrls = mirrors.distinct(),
        )
    }

    private fun computeInstallState(
        repo: ExtensionRepo,
        remoteCode: Long,
        installed: ExtensionInfo?,
    ): ExtensionInstallState = when {
        installed != null && remoteCode > installed.versionCode -> ExtensionInstallState.UPDATE_AVAILABLE
        installed != null -> ExtensionInstallState.INSTALLED
        !repo.trusted -> ExtensionInstallState.UNTRUSTED
        else -> ExtensionInstallState.AVAILABLE
    }

    private fun extractLangFromPkg(pkg: String): String {
        val parts = pkg.split(".")
        return if (parts.size >= 4) parts[3] else "all"
    }

    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
        return bytes
    }

    private fun ByteArray.looksLikeExtensionMsg(): Boolean {
        if (size < 4) return false
        val fields = runCatching { parseWireFields(this, 0, minOf(size, 64)) }.getOrDefault(emptyList())
        return fields.any { it.number == 2 && it.wireType == WIRE_BYTES }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_I32 = 5

    private data class WireField(
        val number: Int,
        val wireType: Int,
        val varint: Long = 0L,
        val bytes: ByteArray = ByteArray(0),
    )

    private fun parseWireFields(buf: ByteArray, start: Int, end: Int): List<WireField> {
        val out = mutableListOf<WireField>()
        var i = start
        while (i < end) {
            val (tag, nextIdx) = readVarint(buf, i, end)
            i = nextIdx
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when (wireType) {
                WIRE_VARINT -> {
                    val (v, n) = readVarint(buf, i, end)
                    i = n
                    out.add(WireField(fieldNum, wireType, varint = v))
                }
                WIRE_BYTES -> {
                    val (lenLong, n) = readVarint(buf, i, end)
                    i = n
                    val len = lenLong.toInt().coerceAtLeast(0).coerceAtMost(end - i)
                    out.add(WireField(fieldNum, wireType, bytes = buf.copyOfRange(i, i + len)))
                    i += len
                }
                WIRE_I32 -> {
                    i = (i + 4).coerceAtMost(end)
                }
                WIRE_I64 -> {
                    i = (i + 8).coerceAtMost(end)
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(buf: ByteArray, start: Int, end: Int): Pair<Long, Int> {
        var idx = start
        var shift = 0
        var result = 0L
        while (idx < end) {
            val b = buf[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 63) break
        }
        return result to idx
    }
}
