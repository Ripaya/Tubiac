package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tube.maniac.core.model.AppSettings
import tube.maniac.core.network.CloudflareChallengeException
import tube.maniac.core.network.IpBlockedException
import tube.maniac.core.network.NetworkRuntimeConfig
import tube.maniac.core.network.WebViewCookieJar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Mihon-aligned Cloudflare interceptor keeping the exact class & simpleName (`CloudflareInterceptor`)
 * required by KeiSource / Keiyoushi / Tachiyomi extensions:
 * - Attempts silent background WebView challenge solving under a per-host read/write lock when a Context is available.
 * - Throws [CloudflareChallengeException] for interactive challenges or [IpBlockedException] for explicit WAF bans.
 */
open class CloudflareInterceptor(
    private val context: Context? = null,
    private val cookieJar: WebViewCookieJar? = null,
    private val config: NetworkRuntimeConfig? = null,
) : Interceptor {

    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val appContext = context ?: run {
            val response = chain.proceed(request)
            inspectAndThrowIfBlocked(request, response)
            return response
        }
        val hostLock = hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock() }

        val (initialResponse, prevClearance, outcome) = hostLock.read {
            val resp = chain.proceed(request)
            val result = classifyResponse(resp)
            Triple(resp, readCfClearance(request.url), result)
        }

        when (outcome) {
            ChallengeClassification.NONE -> return initialResponse
            ChallengeClassification.HARD_BLOCK -> {
                initialResponse.close()
                throw IpBlockedException(
                    blockedUrl = request.url.toString(),
                    statusCode = initialResponse.code,
                )
            }
            ChallengeClassification.CHALLENGE -> {
                val challengeStatus = initialResponse.code
                val resolved = hostLock.write {
                    if (isClearanceUpdated(request.url, prevClearance)) {
                        initialResponse.close()
                        true
                    } else {
                        initialResponse.close()
                        solveInBackgroundWebView(appContext, request, prevClearance)
                    }
                }
                if (resolved) {
                    val retried = chain.proceed(request)
                    inspectAndThrowIfBlocked(request, retried)
                    return retried
                }
                throw CloudflareChallengeException(
                    challengeUrl = request.url.toString(),
                    statusCode = challengeStatus,
                )
            }
        }
    }

    private enum class ChallengeClassification { NONE, CHALLENGE, HARD_BLOCK }

    private fun inspectAndThrowIfBlocked(request: Request, response: Response) {
        when (classifyResponse(response)) {
            ChallengeClassification.NONE -> Unit
            ChallengeClassification.HARD_BLOCK -> {
                val code = response.code
                response.close()
                throw IpBlockedException(
                    blockedUrl = request.url.toString(),
                    statusCode = code,
                )
            }
            ChallengeClassification.CHALLENGE -> {
                val code = response.code
                response.close()
                throw CloudflareChallengeException(
                    challengeUrl = request.url.toString(),
                    statusCode = code,
                )
            }
        }
    }

    private fun classifyResponse(response: Response): ChallengeClassification {
        if (response.code != 403 && response.code != 451 && response.code != 503) {
            return ChallengeClassification.NONE
        }
        val server = response.header("Server").orEmpty().lowercase()
        val cfMitigated = response.header("cf-mitigated").orEmpty().lowercase()
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val isHtmlLike = contentType.isEmpty() ||
            contentType.contains("html") ||
            contentType.contains("text") ||
            contentType.contains("json")
        val bodySnippet = if (isHtmlLike) {
            runCatching { response.peekBody(8192).string().lowercase() }.getOrDefault("")
        } else {
            ""
        }

        val isHardBlock = bodySnippet.contains("sorry, you have been blocked") ||
            bodySnippet.contains("you are unable to access") ||
            bodySnippet.contains("error 1020") ||
            (bodySnippet.contains("cf-error-details") && cfMitigated != "challenge")

        if (isHardBlock) return ChallengeClassification.HARD_BLOCK

        val isChallenge = cfMitigated == "challenge" ||
            bodySnippet.contains("just a moment") ||
            bodySnippet.contains("cf-chl-") ||
            bodySnippet.contains("challenge-platform") ||
            (server.contains("cloudflare") && (response.code == 403 || response.code == 503))

        return if (isChallenge) ChallengeClassification.CHALLENGE else ChallengeClassification.NONE
    }

    private fun readCfClearance(url: HttpUrl): String? {
        val loaded = cookieJar?.loadForRequest(url).orEmpty()
        loaded.firstOrNull { it.name == "cf_clearance" }?.value?.let { return it }
        return runCatching {
            CookieManager.getInstance().getCookie(url.toString())
                ?.split(";")
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("cf_clearance=") }
                ?.substringAfter("cf_clearance=")
        }.getOrNull()
    }

    private fun isClearanceUpdated(url: HttpUrl, previousClearance: String?): Boolean {
        val current = readCfClearance(url)
        return !current.isNullOrBlank() && current != previousClearance
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveInBackgroundWebView(
        ctx: Context,
        originalRequest: Request,
        previousClearance: String?,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView?>(null)
        val bypassed = AtomicBoolean(false)

        val challengeUrl = if (originalRequest.method == "GET") {
            originalRequest.url.toString()
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val userAgent = originalRequest.header("User-Agent")
            ?: config?.userAgent?.get()
            ?: AppSettings.DEFAULT_USER_AGENT

        mainHandler.post {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val origin = "${originalRequest.url.scheme}://${originalRequest.url.host}"
                cm.setCookie(origin, "cf_clearance=; Max-Age=0")
                originalRequest.url.topPrivateDomain()?.let { domain ->
                    cm.setCookie("${originalRequest.url.scheme}://.$domain", "cf_clearance=; Max-Age=0; Domain=.$domain")
                }
                cm.flush()

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = userAgent
                    cm.setAcceptThirdPartyCookies(this, true)
                }
                webViewRef.set(wv)

                wv.webViewClient = object : WebViewClient() {
                    private var sawChallenge = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        cm.flush()
                        if (isClearanceUpdated(originalRequest.url, previousClearance)) {
                            bypassed.set(true)
                            latch.countDown()
                            return
                        }
                        if (url != null && !url.startsWith("about:") && view != null) {
                            view.evaluateJavascript("document.title") { rawTitle ->
                                val title = rawTitle.orEmpty().lowercase()
                                val isChallengeTitle = title.contains("just a moment") ||
                                    title.contains("attention required") ||
                                    title.contains("access denied") ||
                                    title.contains("cloudflare")
                                if (!isChallengeTitle) {
                                    if (sawChallenge || !cm.getCookie(originalRequest.url.toString()).isNullOrBlank()) {
                                        bypassed.set(true)
                                    }
                                    latch.countDown()
                                } else {
                                    sawChallenge = true
                                }
                            }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            val headerChallenge = errorResponse?.responseHeaders
                                ?.any { (k, v) -> k.equals("cf-mitigated", ignoreCase = true) && v.equals("challenge", ignoreCase = true) } == true
                            if (headerChallenge || errorResponse?.statusCode == 403 || errorResponse?.statusCode == 503) {
                                sawChallenge = true
                            }
                        }
                    }
                }
                wv.loadUrl(challengeUrl)
            }.onFailure {
                latch.countDown()
            }
        }

        try {
            latch.await(18, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            mainHandler.post {
                runCatching {
                    webViewRef.getAndSet(null)?.let { wv ->
                        wv.stopLoading()
                        wv.destroy()
                    }
                }
            }
        }
        return bypassed.get() || isClearanceUpdated(originalRequest.url, previousClearance)
    }
}
