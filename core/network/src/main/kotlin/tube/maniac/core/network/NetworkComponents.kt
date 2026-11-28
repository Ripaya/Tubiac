package tube.maniac.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.DoHProvider
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Typed Cloudflare challenge exception caught by UI layers to offer
 * a 1-tap "Open in WebView" solver action instead of raw HTTP errors.
 */
class CloudflareChallengeException(
    val challengeUrl: String,
    val statusCode: Int,
) : IOException("Cloudflare verification required ($statusCode) for $challengeUrl")

/**
 * Typed exception raised when a site or edge firewall (e.g. Cloudflare WAF)
 * hard-blocks the client IP/ASN rather than serving a solvable challenge.
 */
class IpBlockedException(
    val blockedUrl: String,
    val statusCode: Int,
) : IOException("Access blocked by site firewall (HTTP $statusCode) for $blockedUrl. Switch network/VPN or check in WebView.")

@Singleton
class NetworkRuntimeConfig @Inject constructor() {
    val userAgent = AtomicReference(AppSettings.DEFAULT_USER_AGENT)
    val spoofXRequestedWith = AtomicReference(true)
    val dohProvider = AtomicReference(DoHProvider.CLOUDFLARE)
}

/**
 * Unified CookieJar synchronizing Android WebView CookieManager with OkHttp
 * so Cloudflare clearances and site logins flow directly into image/chapter fetches.
 */
@Singleton
class WebViewCookieJar @Inject constructor() : CookieJar {

    private val memoryFallback = ConcurrentHashMap<String, List<Cookie>>()

    private fun cookieManagerOrNull(): CookieManager? = runCatching {
        CookieManager.getInstance()
    }.getOrNull()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val urlString = url.toString()
        val manager = cookieManagerOrNull()
        if (manager != null) {
            cookies.forEach { cookie ->
                manager.setCookie(urlString, cookie.toString())
            }
            manager.flush()
        } else {
            memoryFallback[url.host] = cookies
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = cookieManagerOrNull()
        if (manager != null) {
            val rawCookies = manager.getCookie(url.toString()).orEmpty()
            if (rawCookies.isBlank()) return emptyList()
            return rawCookies.split(";")
                .mapNotNull { token -> runCatching { Cookie.parse(url, token.trim()) }.getOrNull() }
        }
        return memoryFallback[url.host].orEmpty()
    }

    fun clearAll() {
        memoryFallback.clear()
        cookieManagerOrNull()?.removeAllCookies(null)
    }
}

class UserAgentInterceptor(
    private val config: NetworkRuntimeConfig,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", config.userAgent.get())
        }
        if (original.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        if (config.spoofXRequestedWith.get()) {
            builder.removeHeader("X-Requested-With")
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Mihon-aligned Cloudflare interceptor:
 * - Attempts silent background WebView challenge solving under a per-host read/write lock when a Context is available.
 * - Throws [CloudflareChallengeException] for interactive challenges or [IpBlockedException] for explicit WAF bans.
 */
class CloudflareDetectorInterceptor(
    private val context: Context? = null,
    private val cookieJar: WebViewCookieJar? = null,
    private val config: NetworkRuntimeConfig? = null,
) : Interceptor {

    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hostLock = if (context != null) {
            hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock() }
        } else {
            null
        }

        if (hostLock == null) {
            val response = chain.proceed(request)
            inspectAndThrowIfBlocked(request, response)
            return response
        }

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
                        solveInBackgroundWebView(context, request, prevClearance)
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
            // Never deadlock the UI thread waiting on WebView callbacks posted to Main
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
                        if (url == challengeUrl && !sawChallenge && view != null) {
                            view.evaluateJavascript("document.title") { rawTitle ->
                                val title = rawTitle.orEmpty().lowercase()
                                if (!title.contains("just a moment") && !title.contains("attention required")) {
                                    // Non-cf_clearance session cookie or page unlocked directly
                                    if (!cm.getCookie(originalRequest.url.toString()).isNullOrBlank()) {
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

class DynamicDoHDns(
    private val bootstrapClient: OkHttpClient,
    private val config: NetworkRuntimeConfig,
) : Dns {

    private val cache = ConcurrentHashMap<DoHProvider, Dns>()

    override fun lookup(hostname: String): List<InetAddress> {
        val provider = config.dohProvider.get()
        if (provider == DoHProvider.DISABLED || provider.dohUrl.isBlank()) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val dohDns = cache.getOrPut(provider) {
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(provider.dohUrl.toHttpUrl())
                .bootstrapDnsHosts(provider.bootstrapIps.map(InetAddress::getByName))
                .build()
        }
        return runCatching { dohDns.lookup(hostname) }
            .getOrElse { Dns.SYSTEM.lookup(hostname) }
    }
}
