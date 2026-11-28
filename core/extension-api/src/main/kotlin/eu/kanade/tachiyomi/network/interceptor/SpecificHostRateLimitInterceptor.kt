@file:JvmName("SpecificHostRateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Legacy Tachiyomi 1.2/1.3 rateLimitHost facade delegating to [RateLimitInterceptor].
 */
fun OkHttpClient.Builder.specificHostRateLimit(
    url: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(url, permits, period, unit)

fun OkHttpClient.Builder.specificHostRateLimit(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(url, permits, period, unit)
