package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Catches non-IO exceptions in the OkHttp interceptor chain and wraps them in IOException
 * so async Callbacks don't crash the process. Required first interceptor for Mihon/keiyoushi sources.
 */
open class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }
}
