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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.AppThemeMode

/**
 * Skippable 3-step First-Run Onboarding:
 * 1. Theme (Dynamic / Dark / AMOLED)
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Step ${step + 1} of 3",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onFinish) { Text("Skip") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    0 -> {
                        Text("Welcome to Tubiac", style = MaterialTheme.typography.displayLarge)
                        Text(
                            text = "Fast, minimal, open reading — your Shelf, your sources, your AniList.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("Choose your surface", style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppThemeMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.themeMode == mode,
                                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                            label = { Text(mode.name.replace("_", " ")) },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Android 12+ Dynamic Wallpaper Color")
                                    Switch(
                                        checked = settings.useDynamicColor,
                                        onCheckedChange = { v -> onUpdateSettings { it.copy(useDynamicColor = v) } },
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        Text("Connect AniList", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            text = "Optional: Sign in to pull rich covers, banners, and two-way sync. You can keep any field Local-Only anytime.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onConnectAniList,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with AniList") }
                    }

                    2 -> {
                        Text("Your Sources", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            text = "Pick a local folder for CBZ/ZIP/EPUB archives (Tubiac writes a .nomedia marker automatically), or add extension repos later in Browse.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onPickLocalFolder,
                            shape = CircleShape,
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
                    OutlinedButton(onClick = { step-- }, shape = CircleShape) { Text("Back") }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                Button(
                    onClick = {
                        if (step < 2) step++ else onFinish()
                    },
                    shape = CircleShape,
                ) {
                    Text(if (step < 2) "Next" else "Start Reading")
                }
            }
        }
    }
}
