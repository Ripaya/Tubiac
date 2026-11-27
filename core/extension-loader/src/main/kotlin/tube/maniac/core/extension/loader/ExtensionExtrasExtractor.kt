package tube.maniac.core.extension.loader

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tube.maniac.core.model.DomainCookieInfo
import tube.maniac.core.model.ExtractedComment
import tube.maniac.core.model.ExtractedPreferenceItem
import tube.maniac.core.model.ExtractedPreferenceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts extended capabilities beyond chapters/pages from repositories, APKs & web sources:
 * 1. Live ConfigurableSource preferences (`setupPreferenceScreen`) + persistence in SharedPreferences
 * 2. WebView & OkHttp domain cookie vault inspection / clearing
 * 3. Page & HTML discussion / comment / translator-note extraction
 */
@Singleton
class ExtensionExtrasExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    fun preferencesForSource(sourceId: Long): SharedPreferences =
        context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)

    @SuppressLint("RestrictedApi")
    suspend fun extractSourcePreferences(source: Source): Result<List<ExtractedPreferenceItem>> =
        withContext(Dispatchers.Main) {
            runCatching {
                val configurable = source as? ConfigurableSource ?: return@runCatching emptyList()
                val prefManager = PreferenceManager(context).apply {
                    sharedPreferencesName = "source_${source.id}"
                }
                val screen = prefManager.createPreferenceScreen(context)
                configurable.setupPreferenceScreen(screen)
                val items = mutableListOf<ExtractedPreferenceItem>()
                collectPreferences(screen, prefManager.sharedPreferences, items)
                items
            }
        }

    fun updateTextPreference(sourceId: Long, key: String, value: String) {
        preferencesForSource(sourceId).edit().putString(key, value).apply()
    }

    fun updateBooleanPreference(sourceId: Long, key: String, value: Boolean) {
        preferencesForSource(sourceId).edit().putBoolean(key, value).apply()
    }

    fun updateMultiSelectPreference(sourceId: Long, key: String, values: Set<String>) {
        preferencesForSource(sourceId).edit().putStringSet(key, values).apply()
    }

    private fun collectPreferences(
        group: PreferenceGroup,
        prefs: SharedPreferences?,
        out: MutableList<ExtractedPreferenceItem>,
    ) {
        for (i in 0 until group.preferenceCount) {
            val pref: Preference = group.getPreference(i)
            if (pref is PreferenceGroup) {
                collectPreferences(pref, prefs, out)
                continue
            }
            val key = pref.key ?: continue
            val title = pref.title?.toString().orEmpty().ifBlank { key }
            val summary = pref.summary?.toString().orEmpty()

            when (pref) {
                is TwoStatePreference -> {
                    val saved = prefs?.getBoolean(key, pref.isChecked) ?: pref.isChecked
                    out.add(
                        ExtractedPreferenceItem(
                            key = key,
                            title = title,
                            summary = summary,
                            type = ExtractedPreferenceType.SWITCH,
                            booleanValue = saved,
                        ),
                    )
                }
                is ListPreference -> {
                    val saved = prefs?.getString(key, pref.value) ?: pref.value.orEmpty()
                    out.add(
                        ExtractedPreferenceItem(
                            key = key,
                            title = title,
                            summary = summary,
                            type = ExtractedPreferenceType.LIST,
                            currentValue = saved,
                            entries = pref.entries?.map { it.toString() }.orEmpty(),
                            entryValues = pref.entryValues?.map { it.toString() }.orEmpty(),
                        ),
                    )
                }
                is MultiSelectListPreference -> {
                    val saved = prefs?.getStringSet(key, pref.values) ?: pref.values.orEmpty()
                    out.add(
                        ExtractedPreferenceItem(
                            key = key,
                            title = title,
                            summary = summary,
                            type = ExtractedPreferenceType.MULTI_SELECT,
                            entries = pref.entries?.map { it.toString() }.orEmpty(),
                            entryValues = pref.entryValues?.map { it.toString() }.orEmpty(),
                            multiValues = saved,
                        ),
                    )
                }
                is EditTextPreference -> {
                    val saved = prefs?.getString(key, pref.text) ?: pref.text.orEmpty()
                    out.add(
                        ExtractedPreferenceItem(
                            key = key,
                            title = title,
                            summary = summary,
                            type = ExtractedPreferenceType.TEXT,
                            currentValue = saved,
                        ),
                    )
                }
                else -> {
                    val saved = prefs?.all?.get(key)?.toString().orEmpty()
                    out.add(
                        ExtractedPreferenceItem(
                            key = key,
                            title = title,
                            summary = summary,
                            type = ExtractedPreferenceType.ACTION,
                            currentValue = saved,
                        ),
                    )
                }
            }
        }
    }

    fun inspectDomainCookies(urls: List<String>): List<DomainCookieInfo> {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        val domains = urls.mapNotNull { it.toHttpUrlOrNull()?.host }.distinct()
        return domains.map { host ->
            val raw = manager.getCookie("https://$host").orEmpty()
            val names = raw.split(";")
                .mapNotNull { it.trim().substringBefore("=").takeIf(String::isNotBlank) }
                .distinct()
            DomainCookieInfo(
                domain = host,
                cookieNames = names,
                hasSessionCookie = names.any { n ->
                    val l = n.lowercase()
                    l.contains("session") || l.contains("auth") || l.contains("token") || l.contains("user")
                },
                hasCloudflareClearance = names.any { it.equals("cf_clearance", ignoreCase = true) || it.startsWith("__cf") },
            )
        }
    }

    fun clearCookiesForDomain(domain: String) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val raw = manager.getCookie("https://$domain").orEmpty()
        raw.split(";").forEach { cookie ->
            val name = cookie.trim().substringBefore("=")
            if (name.isNotBlank()) {
                manager.setCookie("https://$domain", "$name=; Max-Age=0; path=/")
            }
        }
        manager.flush()
    }

    /**
     * Fetches a series page using the source's OkHttpClient/headers/cookies and extracts
     * reader comments, Disqus/Giscus discussion metadata, and release notes.
     */
    suspend fun extractCommentsFromSourcePage(
        source: Source?,
        pageUrl: String,
    ): Result<List<ExtractedComment>> = withContext(Dispatchers.IO) {
        runCatching {
            val http = source as? HttpSource
            val fullUrl = when {
                pageUrl.startsWith("http://") || pageUrl.startsWith("https://") -> pageUrl
                http != null -> http.baseUrl.trimEnd('/') + "/" + pageUrl.trimStart('/')
                else -> return@runCatching emptyList()
            }
            val client = http?.client ?: okHttpClient
            val reqBuilder = Request.Builder().url(fullUrl).get()
            http?.headers?.let { reqBuilder.headers(it) }
            val html = client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body.string()
            }
            parseCommentsFromHtml(Jsoup.parse(html, fullUrl), sourceName = source?.name ?: "Web Source")
        }
    }

    companion object {
        fun parseCommentsFromHtml(doc: Document, sourceName: String): List<ExtractedComment> {
            val comments = mutableListOf<ExtractedComment>()
            val selectors = listOf(
                "#comments li.comment",
                ".comment-list .comment",
                ".comments-area .comment",
                "[class*='comment-item']",
                "[id*='comment-'] ",
            )
            for (sel in selectors) {
                val nodes = doc.select(sel)
                if (nodes.isNotEmpty()) {
                    nodes.take(15).forEach { el ->
                        val author = el.selectFirst(".fn, .comment-author, [class*='author'], strong, b")
                            ?.text()?.trim().orEmpty().ifBlank { "Reader" }
                        val time = el.selectFirst("time, .comment-metadata, [class*='date'], [class*='time']")
                            ?.text()?.trim().orEmpty()
                        val body = el.selectFirst(".comment-content, .comment-body p, p")
                            ?.text()?.trim().orEmpty().ifBlank { el.text().trim() }
                        if (body.length >= 4) {
                            comments.add(
                                ExtractedComment(
                                    author = author,
                                    body = body.take(400),
                                    timestampLabel = time,
                                    origin = sourceName,
                                ),
                            )
                        }
                    }
                    break
                }
            }

            if (comments.isEmpty()) {
                val hasDisqus = doc.selectFirst("#disqus_thread, script[src*='disqus']") != null
                val hasGiscus = doc.selectFirst(".giscus, script[src*='giscus']") != null
                if (hasDisqus || hasGiscus) {
                    comments.add(
                        ExtractedComment(
                            author = sourceName,
                            body = if (hasDisqus) {
                                "This page uses embedded Disqus comments — open in WebView to read/post live discussion."
                            } else {
                                "This page embeds GitHub Giscus discussions — open in WebView to participate."
                            },
                            origin = "Embedded Thread",
                        ),
                    )
                }
            }

            return comments.distinctBy { it.author to it.body }
        }
    }
}
