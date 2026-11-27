package tube.maniac

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.TubiacTheme
import tube.maniac.core.network.NetworkRuntimeConfig
import javax.inject.Inject

/**
 * Isolated In-App WebView for solving Cloudflare challenges and site logins.
 * Shares CookieManager directly with OkHttp WebViewCookieJar and spoofs User-Agent / X-Requested-With.
 */
@AndroidEntryPoint
class WebViewActivity : ComponentActivity() {

    @Inject
    lateinit var networkConfig: NetworkRuntimeConfig

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rawUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val initialUrl = if (rawUrl.startsWith("https://") || rawUrl.startsWith("http://")) {
            rawUrl
        } else {
            "https://anilist.co"
        }

        setContent {
            TubiacTheme {
                Scaffold(
                    topBar = {
                        TubiacTopBar(
                            kicker = "IN-APP BROWSER",
                            title = "WebView Solver",
                            onBack = { finish() },
                            actions = {
                                IconButton(
                                    onClick = {
                                        CookieManager.getInstance().flush()
                                        finish()
                                    },
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = "Done & Save Cookies")
                                }
                            },
                        )
                    },
                ) { padding ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = networkConfig.userAgent.get()
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = false

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        CookieManager.getInstance().flush()
                                    }
                                }
                                loadUrl(initialUrl)
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, url: String): Intent =
            Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
