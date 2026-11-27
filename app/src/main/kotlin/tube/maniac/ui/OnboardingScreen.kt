package tube.maniac.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.TubiacAppMark
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.AppThemeMode

/**
 * Skippable 3-step First-Run Onboarding with matte editorial finish:
 * 1. Surface & Theme (Light / Dark / AMOLED + Matte Texture)
 * 2. Optional AniList OAuth2 Login
 * 3. Optional Local Folder / Extension Repo Setup
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onConnectAniList: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }

    MatteBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "EDITION SETUP // 0${step + 1} OF 03",
                            style = MaterialTheme.typography.labelSmall.kicker,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onFinish) { Text("Skip Setup") }
                    }
                    LinearProgressIndicator(
                        progress = { (step + 1) / 3f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (step) {
                        0 -> {
                            TubiacAppMark(size = 56.dp)
                            Text("Welcome to Tubiac", style = MaterialTheme.typography.displayLarge)
                            Text(
                                text = "Focused, serialized manga & comic reading — local-first library, extension repos, and two-way AniList tracking.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MatteCard {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text("Reading Surface", style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AppThemeMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = settings.themeMode == mode,
                                                onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                                label = { Text(mode.name.replace("_", " ")) },
                                                shape = MaterialTheme.shapes.small,
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Matte Paper Grain Texture", style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = settings.useMatteTexture,
                                            onCheckedChange = { v -> onUpdateSettings { it.copy(useMatteTexture = v) } },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Android 12+ Wallpaper Color", style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = settings.useDynamicColor,
                                            onCheckedChange = { v -> onUpdateSettings { it.copy(useDynamicColor = v) } },
                                        )
                                    }
                                }
                            }
                        }

                        1 -> {
                            Text("SYNC & TRACKING", style = MaterialTheme.typography.labelSmall.kicker, color = MaterialTheme.colorScheme.primary)
                            Text("Connect AniList", style = MaterialTheme.typography.headlineLarge)
                            Text(
                                text = "Optional: Sign in to pull high-res covers, banners, and two-way progress sync. Granular fields can stay Local-Only anytime.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onConnectAniList,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sign in with AniList") }
                        }

                        2 -> {
                            Text("ARCHIVE SOURCES", style = MaterialTheme.typography.labelSmall.kicker, color = MaterialTheme.colorScheme.primary)
                            Text("Your Sources", style = MaterialTheme.typography.headlineLarge)
                            Text(
                                text = "Pick a local folder for CBZ, CBR, ZIP, or EPUB archives (Tubiac writes a .nomedia marker automatically), or add extension repos later in Browse.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onPickLocalFolder,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Select Local Manga Folder") }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (step > 0) {
                        OutlinedButton(
                            onClick = { step-- },
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Back") }
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }
                    Button(
                        onClick = {
                            if (step < 2) step++ else onFinish()
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(if (step < 2) "Continue" else "Enter Library")
                    }
                }
            }
        }
    }
}
