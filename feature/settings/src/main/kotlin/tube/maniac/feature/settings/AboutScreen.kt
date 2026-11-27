package tube.maniac.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.TabularBadge
import tube.maniac.core.designsystem.components.TubiacAppMark
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.designsystem.theme.tabular

internal data class AppPackageMeta(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
)

@Composable
internal fun rememberAppPackageMeta(context: Context): AppPackageMeta = remember(context) {
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val vName = info.versionName ?: "0.1.0-alpha"
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        AppPackageMeta(versionName = vName, versionCode = vCode, packageName = context.packageName)
    }.getOrDefault(AppPackageMeta("0.1.0-alpha", 1L, context.packageName))
}

private const val REPO_URL = "https://github.com/Ripaya/Tubiac"
private const val ISSUES_URL = "https://github.com/Ripaya/Tubiac/issues"
private const val RELEASES_URL = "https://github.com/Ripaya/Tubiac/releases"

private data class ChangelogEntry(
    val version: String,
    val tag: String,
    val highlights: List<String>,
)

private val TUBIAC_CHANGELOG = listOf(
    ChangelogEntry(
        version = "Latest Alpha Channel",
        tag = "CURRENT",
        highlights = listOf(
            "Mature editorial matte paper-grain finish with unified hairline cards and typography.",
            "Split Settings into dedicated full-screen categories including a full About hub.",
            "Enabled R8 bytecode optimization, resource shrinking, and locale trimming to reduce binary size under 50 MB.",
            "Fixed earliest-unread chapter selection when sorted oldest-first and improved repo/search keyboard flows.",
        ),
    ),
    ChangelogEntry(
        version = "v0.1.1-alpha (Build 2)",
        tag = "RELEASE",
        highlights = listOf(
            "Explicit PackageInstaller manifest receiver for Android 14+ extension APK installs.",
            "Full remote getImageUrl resolution, Referer header forwarding, and offline .cbz archive playback.",
            "Added automated compatible dependency updater and service health monitor workflows.",
        ),
    ),
    ChangelogEntry(
        version = "v0.1.0-alpha (Build 1)",
        tag = "INITIAL",
        highlights = listOf(
            "Local-first Room shelf, Tachiyomi/Mihon extension API bridge, and two-way AniList policy matrix.",
            "SAF Local Source scanner, DNS-over-HTTPS resolver, and WebView Cloudflare clearance sharing.",
        ),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appMeta = rememberAppPackageMeta(context)
    val deviceSummary = remember(appMeta) {
        "Tubiac v${appMeta.versionName} (Build ${appMeta.versionCode}) • ${appMeta.packageName} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    var feedbackCategory by remember { mutableStateOf("Feature Request") }
    var feedbackTitle by remember { mutableStateOf("") }
    var feedbackMessage by remember { mutableStateOf("") }
    var actionNotice by remember { mutableStateOf<String?>(null) }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            actionNotice = "Could not open browser for URL."
        }
    }

    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        actionNotice = "$label copied to clipboard."
    }

    SettingsCategoryScaffold(
        kicker = "SETTINGS // PROJECT INFO",
        title = "About Tubiac",
        feedback = actionNotice,
        onBack = onBack,
    ) {
        // 1. App Icon, Name, Version & Description Hero Card
        item {
            MatteCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TubiacAppMark(size = 64.dp)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Tubiac", style = MaterialTheme.typography.headlineMedium)
                                TabularBadge(
                                    text = "OPEN SOURCE",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                text = "Version ${appMeta.versionName} (Build ${appMeta.versionCode})",
                                style = MaterialTheme.typography.labelLarge.tabular(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = appMeta.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Text(
                        text = "Tubiac is a lightweight, matte-finished manga and comic reader engineered around local-first ownership, dynamic Tachiyomi/Mihon extension compatibility, DNS-over-HTTPS resilience, and granular privacy-respecting AniList synchronization.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(
                        onClick = { copyToClipboard("Diagnostic Version Info", deviceSummary) },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Build & Device Info")
                    }
                }
            }
        }

        // 2. Repository Link & Star CTA
        item {
            SettingsMatteGroup(title = "Source Repository", icon = Icons.Rounded.Star) {
                Text(
                    text = "Enjoying Tubiac? Please consider starring the repository on GitHub — every star helps project visibility and continued development.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = REPO_URL,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { openUrl(REPO_URL) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Star Repository")
                    }
                    OutlinedButton(
                        onClick = { copyToClipboard("Repository URL", REPO_URL) },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy URL")
                    }
                }
            }
        }

        // 3. Changelog Section
        item {
            SettingsMatteGroup(title = "Changelog", icon = Icons.Rounded.History) {
                TUBIAC_CHANGELOG.forEachIndexed { idx, entry ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.version, style = MaterialTheme.typography.titleSmall.tabular())
                            TabularBadge(
                                text = entry.tag,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        entry.highlights.forEach { bullet ->
                            Text(
                                text = "• $bullet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { openUrl(RELEASES_URL) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View All Releases on GitHub")
                }
            }
        }

        // 4. In-App Feedback Form
        item {
            SettingsMatteGroup(title = "Feedback Form", icon = Icons.Rounded.Feedback) {
                Text(
                    text = "Share feature ideas, reader feedback, or extension compatibility requests.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("Feature Request", "UI / UX", "Extensions", "General").forEach { category ->
                        FilterChip(
                            selected = feedbackCategory == category,
                            onClick = { feedbackCategory = category },
                            label = { Text(category) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
                OutlinedTextField(
                    value = feedbackTitle,
                    onValueChange = { feedbackTitle = it },
                    label = { Text("Summary") },
                    placeholder = { Text("Brief title for your feedback") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = feedbackMessage,
                    onValueChange = { feedbackMessage = it },
                    label = { Text("Details") },
                    placeholder = { Text("Describe what works well or what should be improved...") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )

                val formattedPayload = remember(feedbackCategory, feedbackTitle, feedbackMessage, deviceSummary) {
                    buildString {
                        appendLine("### Category: $feedbackCategory")
                        appendLine()
                        appendLine(feedbackMessage.ifBlank { "(No details entered)" })
                        appendLine()
                        appendLine("---")
                        appendLine("Environment: $deviceSummary")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val titleParam = Uri.encode("[$feedbackCategory] ${feedbackTitle.ifBlank { "User Feedback" }}")
                            val bodyParam = Uri.encode(formattedPayload)
                            openUrl("$ISSUES_URL/new?title=$titleParam&body=$bodyParam")
                        },
                        enabled = feedbackTitle.isNotBlank() || feedbackMessage.isNotBlank(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Submit on GitHub")
                    }
                    OutlinedButton(
                        onClick = { copyToClipboard("Feedback Report", formattedPayload) },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
            }
        }

        // 5. Issue & Bug Report Section
        item {
            SettingsMatteGroup(title = "Issues & Bug Reporting", icon = Icons.Rounded.BugReport) {
                Text(
                    text = "Encountered a broken source, Cloudflare block, or UI defect? Open an issue with your pre-filled build metadata.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "DIAGNOSTICS ATTACHED AUTOMATICALLY",
                    style = MaterialTheme.typography.labelSmall.kicker,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = deviceSummary,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val body = Uri.encode("### Description\nDescribe steps to reproduce:\n\n### Environment\n$deviceSummary\n")
                            openUrl("$ISSUES_URL/new?labels=bug&title=${Uri.encode("[Bug] ")}&body=$body")
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Report Issue")
                    }
                    OutlinedButton(
                        onClick = { openUrl(ISSUES_URL) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Browse Issues")
                    }
                }
            }
        }

        // 6. License & Disclaimer
        item {
            SettingsMatteGroup(title = "License & Disclaimer", icon = Icons.Rounded.Info) {
                Text(
                    text = "Licensed under GNU General Public License v3.0 (GPLv3). Tubiac is strictly a standalone client and tracking utility; it does not host, index, or distribute any copyrighted publications or third-party extension code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
