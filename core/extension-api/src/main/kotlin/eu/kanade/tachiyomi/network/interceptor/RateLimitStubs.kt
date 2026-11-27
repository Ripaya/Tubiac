package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val intervalMs = unit.toMillis(period).coerceAtLeast(1L) / permits.coerceAtLeast(1)
    return addInterceptor { chain ->
        if (intervalMs > 0L) {
            Thread.sleep(intervalMs.coerceAtMost(2000L))
        }
        chain.proceed(chain.request())
    }
}

fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val intervalMs = unit.toMillis(period).coerceAtLeast(1L) / permits.coerceAtLeast(1)
    val host = url.host
    return addInterceptor { chain ->
        if (chain.request().url.host.equals(host, ignoreCase = true) && intervalMs > 0L) {
            Thread.sleep(intervalMs.coerceAtMost(2000L))
        }
        chain.proceed(chain.request())
    }
}

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val intervalMs = unit.toMillis(period).coerceAtLeast(1L) / permits.coerceAtLeast(1)
    return addInterceptor { chain ->
        if (chain.request().url.toString().startsWith(url) && intervalMs > 0L) {
            Thread.sleep(intervalMs.coerceAtMost(2000L))
        }
        chain.proceed(chain.request())
    }
}
