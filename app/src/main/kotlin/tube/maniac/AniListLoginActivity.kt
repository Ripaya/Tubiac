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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tube.maniac.core.anilist.AniListAuthHelper
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.TubiacTheme
import javax.inject.Inject

/**
 * Direct In-App AniList OAuth2 & Token Sign-In.
 * Intersects OAuth redirect fragments BEFORE any external/secondary website is loaded,
 * and allows pasting an AniList access token directly against graphql.anilist.co.
 */
@AndroidEntryPoint
class AniListLoginActivity : ComponentActivity() {

    @Inject lateinit var aniListClient: AniListGraphQlClient
    @Inject lateinit var settingsRepository: SettingsRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty()
        val authUrl = AniListAuthHelper.buildAuthorizeUrl(clientId)

        setContent {
            var manualMode by remember { mutableStateOf(false) }
            var tokenInput by remember { mutableStateOf("") }
            var statusMessage by remember { mutableStateOf<String?>("Connecting directly to anilist.co (no secondary websites).") }
            var busy by remember { mutableStateOf(false) }

            fun completeWithToken(token: String) {
                if (busy) return
                busy = true
                statusMessage = "Verifying token directly with graphql.anilist.co..."
                lifecycleScope.launch {
                    aniListClient.fetchViewer(token.trim())
                        .onSuccess { viewer ->
                            settingsRepository.saveAniListAuth(token.trim(), viewer)
                            finish()
                        }
                        .onFailure { err ->
                            busy = false
                            statusMessage = "AniList verification failed: ${err.message}"
                        }
                }
            }

            TubiacTheme {
                MatteBackground {
                    Scaffold(
                        topBar = {
                            TubiacTopBar(
                                kicker = "DIRECT ANILIST AUTH",
                                title = "Sign in to AniList",
                                onBack = { finish() },
                                actions = {
                                    TextButton(onClick = { manualMode = !manualMode }) {
                                        Text(if (manualMode) "Web Login" else "Paste Token")
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            statusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }

                            if (manualMode) {
                                MatteCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = "Direct Bearer Token Sign-In",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = "Paste your AniList personal OAuth access token directly. Tubiac verifies it directly with https://graphql.anilist.co without any third-party servers.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        OutlinedTextField(
                                            value = tokenInput,
                                            onValueChange = { tokenInput = it },
                                            label = { Text("AniList Access Token") },
                                            minLines = 3,
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { completeWithToken(tokenInput) },
                                                enabled = tokenInput.isNotBlank() && !busy,
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text(if (busy) "Verifying..." else "Connect Directly")
                                            }
                                        }
                                    }
                                }
                            } else {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            CookieManager.getInstance().setAcceptCookie(true)
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            webViewClient = object : WebViewClient() {
                                                private fun tryInterceptToken(url: String?): Boolean {
                                                    if (url.isNullOrBlank()) return false
                                                    val token = AniListAuthHelper.extractAccessTokenFromCallback(url)
                                                    if (!token.isNullOrBlank()) {
                                                        completeWithToken(token)
                                                        return true
                                                    }
                                                    // Block navigation away from anilist.co to any secondary website
                                                    val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
                                                    if (host.isNotBlank() && host != "anilist.co" && !host.endsWith(".anilist.co")) {
                                                        return true
                                                    }
                                                    return false
                                                }

                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                ): Boolean = tryInterceptToken(request?.url?.toString())

                                                override fun onPageStarted(
                                                    view: WebView?,
                                                    url: String?,
                                                    favicon: android.graphics.Bitmap?,
                                                ) {
                                                    if (tryInterceptToken(url)) {
                                                        view?.stopLoading()
                                                    }
                                                }
                                            }
                                            loadUrl(authUrl)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CLIENT_ID = "extra_anilist_client_id"

        fun createIntent(context: Context, clientId: String = ""): Intent =
            Intent(context, AniListLoginActivity::class.java).putExtra(EXTRA_CLIENT_ID, clientId)
    }
}
