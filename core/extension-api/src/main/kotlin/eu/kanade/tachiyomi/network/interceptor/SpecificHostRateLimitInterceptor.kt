@file:JvmName("SpecificHostRateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

/**
 * Mihon extension-lib 1.3 through 1.6 per-host rate limiting extensions.
 */
@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(httpUrl, permits, period.toDuration(unit.toDurationUnit()))

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(host = httpUrl.host, permits = permits, durationMs = period.inWholeMilliseconds),
)

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(url, permits, period.toDuration(unit.toDurationUnit()))

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(
        host = url.toHttpUrlOrNull()?.host,
        urlPrefix = if (url.toHttpUrlOrNull() == null) url else null,
        permits = permits,
        durationMs = period.inWholeMilliseconds,
    ),
)

/**
 * Legacy Tachiyomi 1.2/1.3 rateLimitHost facade delegating to [RateLimitInterceptor].
 */
fun OkHttpClient.Builder.specificHostRateLimit(
    url: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(url, permits, period.toDuration(unit.toDurationUnit()))

fun OkHttpClient.Builder.specificHostRateLimit(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = rateLimitHost(url, permits, period.toDuration(unit.toDurationUnit()))
