package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.source.online.ExtensionNetworkBridge
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient

open class NetworkHelper() {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    open val client: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    open val cloudflareClient: OkHttpClient
        get() = ExtensionNetworkBridge.sharedClient

    open val cookieJar: CookieJar
        get() = client.cookieJar

    open val headers: Headers
        get() = Headers.Builder()
            .add("User-Agent", defaultUserAgentProvider())
            .build()

    open fun defaultUserAgentProvider(): String = ExtensionNetworkBridge.userAgentProvider()
}
