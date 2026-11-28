package tube.maniac.core.network

import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudflareDetectorTest {

    private fun clientReturning(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): OkHttpClient {
        val mockResponseInterceptor = Interceptor { chain ->
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_2)
                .code(code)
                .message("HTTP $code")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        }
        return OkHttpClient.Builder()
            .addInterceptor(CloudflareDetectorInterceptor())
            .addInterceptor(mockResponseInterceptor)
            .build()
    }

    @Test
    fun `cloudflare hard ip block throws IpBlockedException`() {
        val html = """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body><div id="cf-error-details"><h1>Sorry, you have been blocked</h1>
            <h2>You are unable to access mangtto.com</h2></div></body></html>
        """.trimIndent()
        val client = clientReturning(
            code = 403,
            headers = mapOf("Server" to "cloudflare"),
            body = html,
        )
        val ex = assertThrows(IpBlockedException::class.java) {
            client.newCall(Request.Builder().url("https://mangtto.com").build()).execute()
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun `cloudflare challenge throws CloudflareChallengeException`() {
        val client = clientReturning(
            code = 403,
            headers = mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"),
            body = "<html><title>Just a moment...</title></html>",
        )
        val ex = assertThrows(CloudflareChallengeException::class.java) {
            client.newCall(Request.Builder().url("https://example.org").build()).execute()
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun `uncaught exception interceptor wraps non-IO exceptions as IOException and preserves IOException`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor { throw IllegalStateException("broken downstream") }
            .build()
        assertTrue(client.interceptors.firstOrNull() is UncaughtExceptionInterceptor)
        val ioEx = assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://example.org").build()).execute()
        }
        assertTrue(ioEx.cause is IllegalStateException)
    }
}
