package tube.maniac.core.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.DoHProvider
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed Cloudflare challenge exception caught by UI layers to offer
 * a 1-tap "Open in WebView" solver action instead of raw HTTP errors.
 */
class CloudflareChallengeException(
    val challengeUrl: String,
    val statusCode: Int,
) : IOException("Cloudflare verification required ($statusCode) for $challengeUrl")

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
                .mapNotNull { Cookie.parse(url, it.trim()) }
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
        if (config.spoofXRequestedWith.get()) {
            builder.removeHeader("X-Requested-With")
        }
        return chain.proceed(builder.build())
    }
}

class CloudflareDetectorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 403 || response.code == 503) {
            val server = response.header("Server").orEmpty().lowercase()
            val cfMitigated = response.header("cf-mitigated").orEmpty().lowercase()
            if (cfMitigated == "challenge" || server.contains("cloudflare")) {
                response.close()
                throw CloudflareChallengeException(
                    challengeUrl = request.url.toString(),
                    statusCode = response.code,
                )
            }
        }
        return response
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
