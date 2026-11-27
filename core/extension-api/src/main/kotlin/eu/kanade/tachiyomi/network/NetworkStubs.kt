package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.source.online.ExtensionNetworkBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import rx.Observable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .get()
    .build()

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .post(body)
    .build()

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .put(body)
    .build()

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody? = null,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .delete(body)
    .build()

fun HEAD(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .head()
    .build()

fun Call.asObservable(): Observable<Response> = Observable.fromCallable { execute() }

fun Call.asObservableSuccess(): Observable<Response> = Observable.fromCallable {
    val response = execute()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP error ${response.code}")
    }
    response
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
            else response.close()
        }
    })
}

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

class NetworkHelper {
    val client: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    val cloudflareClient: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    val headers: Headers
        get() = Headers.Builder().add("User-Agent", defaultUserAgentProvider()).build()

    fun defaultUserAgentProvider(): String = ExtensionNetworkBridge.userAgentProvider()
}
