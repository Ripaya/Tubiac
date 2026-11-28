@file:JvmName("RequestsKt")

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

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

fun GET(
    url: HttpUrl,
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
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .post(body)
    .build()

fun POST(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
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
    body: RequestBody = DEFAULT_BODY,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .put(body)
    .build()

fun PUT(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
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

fun DELETE(
    url: HttpUrl,
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

fun HEAD(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .head()
    .build()

fun PATCH(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .patch(body)
    .build()

fun PATCH(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .patch(body)
    .build()
