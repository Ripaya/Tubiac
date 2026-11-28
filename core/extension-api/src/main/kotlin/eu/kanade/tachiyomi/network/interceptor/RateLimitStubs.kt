@file:JvmName("RateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

/**
 * Sliding-window OkHttp rate-limit interceptor matching Mihon/Tachiyomi extension expectations.
 */
open class RateLimitInterceptor(
    private val host: String? = null,
    private val urlPrefix: String? = null,
    private val permits: Int,
    private val period: Long = 1,
    private val unit: TimeUnit = TimeUnit.SECONDS,
    durationMs: Long? = null,
) : Interceptor {

    private val lock = Any()
    private val timestamps = ArrayDeque<Long>()
    private val periodMs = (durationMs ?: unit.toMillis(period)).coerceAtLeast(1L)
    private val maxPermits = permits.coerceAtLeast(1)

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")

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

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimit(permits, period.toDuration(unit.toDurationUnit()))

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(permits = permits, durationMs = period.inWholeMilliseconds),
)
