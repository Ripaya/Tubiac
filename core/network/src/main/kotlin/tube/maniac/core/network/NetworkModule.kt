package tube.maniac.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cookieJar: WebViewCookieJar,
        config: NetworkRuntimeConfig,
    ): OkHttpClient {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .dns(DynamicDoHDns(bootstrapClient, config))
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(config))
            .addInterceptor(
                CloudflareInterceptor(
                    context = context,
                    cookieJar = cookieJar,
                    config = config,
                ),
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
