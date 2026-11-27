@file:JvmName("OkHttpExtensionsKt")

package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int) : IllegalStateException("HTTP error $code")

val jsonMime = "application/json; charset=utf-8".toMediaType()

fun Call.asObservable(): Observable<Response> = Observable.fromCallable { execute() }

fun Call.asObservableSuccess(): Observable<Response> = Observable.fromCallable {
    val response = execute()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
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

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}

inline fun <reified T> Response.parseAs(json: Json): T =
    use { json.decodeFromString(it.body.string()) }

inline fun <reified T> Response.parseAs(): T =
    parseAs(Injekt.get())

inline fun <reified T> String.parseAs(json: Json): T =
    json.decodeFromString(this)

inline fun <reified T> String.parseAs(): T =
    parseAs(Injekt.get())
