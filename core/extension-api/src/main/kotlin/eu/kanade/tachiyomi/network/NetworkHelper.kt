package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.source.online.ExtensionNetworkBridge
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient

class NetworkHelper {
    val client: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    val cloudflareClient: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    val cookieJar: CookieJar
        get() = client.cookieJar

    val headers: Headers
        get() = Headers.Builder()
            .add("User-Agent", defaultUserAgentProvider())
            .build()

    fun defaultUserAgentProvider(): String = ExtensionNetworkBridge.userAgentProvider()
}
