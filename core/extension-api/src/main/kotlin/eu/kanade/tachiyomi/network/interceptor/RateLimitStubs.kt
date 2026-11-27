@file:JvmName("RateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Sliding-window OkHttp rate-limit interceptor matching Mihon/Tachiyomi extension expectations.
 */
open class RateLimitInterceptor(
    private val host: String? = null,
    private val urlPrefix: String? = null,
    private val permits: Int,
    private val period: Long = 1,
    private val unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {

    private val lock = Any()
    private val timestamps = ArrayDeque<Long>()
    private val periodMs = unit.toMillis(period).coerceAtLeast(1L)
    private val maxPermits = permits.coerceAtLeast(1)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val matchesHost = host == null || request.url.host.equals(host, ignoreCase = true)
        val matchesPrefix = urlPrefix == null || request.url.toString().startsWith(urlPrefix)
        if (!matchesHost || !matchesPrefix) {
            return chain.proceed(request)
        }

        val waitMs = synchronized(lock) {
            val now = System.currentTimeMillis()
            while (timestamps.isNotEmpty() && now - timestamps.peekFirst() >= periodMs) {
                timestamps.removeFirst()
            }
            val delay = if (timestamps.size >= maxPermits) {
                (periodMs - (now - timestamps.peekFirst())).coerceAtLeast(1L)
            } else {
                0L
            }
            timestamps.addLast(now + delay)
            delay
        }

        if (waitMs > 0L) {
            Thread.sleep(waitMs.coerceAtMost(5_000L))
        }
        return chain.proceed(request)
    }
}

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(permits = permits, period = period, unit = unit),
)

fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(host = url.host, permits = permits, period = period, unit = unit),
)

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(urlPrefix = url, permits = permits, period = period, unit = unit),
)
