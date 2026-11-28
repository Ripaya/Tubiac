package tube.maniac.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.anilist.AniListSyncEngine
import tube.maniac.core.database.BackupFormat
import tube.maniac.core.database.BackupManager
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.MatteNavigationRow
import tube.maniac.core.designsystem.components.TabularBadge
import tube.maniac.core.designsystem.components.TubiacAppMark
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.DiagnosticReporterState
import tube.maniac.core.diagnostics.LogcatEntry
import tube.maniac.core.diagnostics.TubiacLogcat
import tube.maniac.core.model.AniListSyncPolicy
import tube.maniac.core.model.AniListUser
import tube.maniac.core.model.AnimationSpeed
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.AppThemeMode
import tube.maniac.core.model.AutoBackupFrequency
import tube.maniac.core.model.ConflictResolution
import tube.maniac.core.model.DoHProvider
import tube.maniac.core.model.DownloadStorageFormat
import tube.maniac.core.model.RateLimitStatus
import tube.maniac.core.model.SelectiveRestoreOptions
import tube.maniac.core.model.SyncFieldPolicy
import tube.maniac.core.model.TapZonePreset
import tube.maniac.core.network.NetworkRuntimeConfig
import tube.maniac.core.network.WebViewCookieJar
import tube.maniac.feature.downloads.AutoBackupWorker
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val anilistUser: AniListUser? = null,
    val rateLimitStatus: RateLimitStatus = RateLimitStatus(),
    val statusFeedback: String? = null,
    val diagnosticsState: DiagnosticReporterState = DiagnosticReporterState(),
    val logcatEntries: List<LogcatEntry> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val aniListClient: AniListGraphQlClient,
    private val aniListSyncEngine: AniListSyncEngine,
    private val networkConfig: NetworkRuntimeConfig,
    private val cookieJar: WebViewCookieJar,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val feedbackFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsRepository.settingsFlow,
            settingsRepository.anilistUserFlow,
            aniListClient.rateLimiter.status,
            feedbackFlow,
        ) { settings, user, rateLimit, feedback ->
            listOf(settings, user, rateLimit, feedback)
        },
        AutoIssueReporter.state,
        TubiacLogcat.entries,
    ) { core, diagState, logs ->
        @Suppress("UNCHECKED_CAST")
        val settings = core[0] as AppSettings
        val user = core[1] as AniListUser?
        val rateLimit = core[2] as RateLimitStatus
        val feedback = core[3] as String?
        SettingsUiState(settings, user, rateLimit, feedback, diagState, logs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun clearFeedback() {
        feedbackFlow.value = null
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(transform)
            val updated = settingsRepository.currentSettings()
            networkConfig.dohProvider.set(updated.dohProvider)
            networkConfig.userAgent.set(updated.customUserAgent)
            networkConfig.spoofXRequestedWith.set(updated.spoofXRequestedWith)
            AutoIssueReporter.updatePreferences(updated.autoReportIssuesEnabled, updated.customIssueRelayUrl)
            AutoBackupWorker.schedule(context, updated.autoBackupFrequency)
        }
    }

    fun flushDiagnosticReports() {
        viewModelScope.launch {
            val count = AutoIssueReporter.flushPendingReports(okHttpClient)
            feedbackFlow.value = if (count > 0) {
                "Submitted $count bug report(s) to GitHub."
            } else {
                AutoIssueReporter.state.value.lastStatus
            }
        }
    }

    fun clearDiagnostics() {
        TubiacLogcat.clear()
        AutoIssueReporter.clearPendingAndHistory()
        feedbackFlow.value = "Cleared local Logcat buffer and diagnostic queue."
    }

    fun updateSyncPolicy(transform: (AniListSyncPolicy) -> AniListSyncPolicy) {
        updateSettings { it.copy(syncPolicy = transform(it.syncPolicy)) }
    }

    fun syncAniListNow() {
        viewModelScope.launch {
            val token = settingsRepository.anilistTokenFlow.first()
            if (token.isNullOrBlank()) {
                feedbackFlow.value = "Connect AniList first to sync."
                return@launch
            }
            val flushed = aniListSyncEngine.flushOfflineQueue(token)
            feedbackFlow.value = "Synced cleanly ($flushed queued updates pushed)."
        }
    }

    fun disconnectAniList() {
        viewModelScope.launch {
            settingsRepository.clearAniListAuth()
            feedbackFlow.value = "AniList account disconnected. Local library preserved."
        }
    }

    fun connectWithDirectToken(rawToken: String) {
        val token = rawToken.trim()
        if (token.isBlank()) return
        viewModelScope.launch {
            aniListClient.fetchViewer(token)
                .onSuccess { viewer ->
                    settingsRepository.saveAniListAuth(token, viewer)
                    feedbackFlow.value = "Connected directly as ${viewer.name} via graphql.anilist.co."
                }
                .onFailure { err ->
                    feedbackFlow.value = "Token check failed: ${err.message}"
                }
        }
    }

    fun pullAniListLibrary(conflictResolution: ConflictResolution) {
        viewModelScope.launch {
            val token = settingsRepository.anilistTokenFlow.first()
            val user = uiState.value.anilistUser
            if (token.isNullOrBlank() || user == null) {
                feedbackFlow.value = "Connect AniList first before pulling remote lists."
                return@launch
            }
            feedbackFlow.value = "Importing AniList lists directly from graphql.anilist.co..."
            aniListSyncEngine.pullLibraryFromAniList(user.id, token, conflictResolution)
                .onSuccess { count ->
                    feedbackFlow.value = "Imported/updated $count series from your AniList account."
                }
                .onFailure { err ->
                    feedbackFlow.value = "AniList library pull failed: ${err.message}"
                }
        }
    }

    fun exportBackupToUri(uri: Uri, format: BackupFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    backupManager.exportBackup(format, out)
                } ?: error("Cannot write to target file")
            }.onSuccess {
                feedbackFlow.value = "Backup exported (${format.name})."
            }.onFailure {
                feedbackFlow.value = "Backup failed: ${it.message}"
            }
        }
    }

    fun importBackupFromUri(uri: Uri, format: BackupFormat, options: SelectiveRestoreOptions) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    backupManager.restoreBackup(input, format, options)
                } ?: error("Cannot read backup file")
            }.onSuccess {
                feedbackFlow.value = "Selective restore completed."
            }.onFailure {
                feedbackFlow.value = "Restore failed: ${it.message}"
            }
        }
    }

    fun clearCachesAndVacuum() {
        viewModelScope.launch(Dispatchers.IO) {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            cookieJar.clearAll()
            backupManager.vacuumDatabase()
            feedbackFlow.value = "Caches cleared, cookies reset, and SQLite database vacuumed."
        }
    }

    fun cleanOrphanedDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = context.getExternalFilesDir("downloads")
            val emptyDirs = dir?.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }.orEmpty()
            emptyDirs.forEach(File::deleteRecursively)
            feedbackFlow.value = "Removed ${emptyDirs.size} empty orphaned download folder(s)."
        }
    }
}

/** Main categorized Settings Hub linking each section to its own dedicated screen. */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenDownloadsSettings: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val appMeta = rememberAppPackageMeta(context)

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "PREFERENCES",
                    title = "Settings",
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    MatteCard(onClick = onOpenAbout) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TubiacAppMark(size = 46.dp)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("Tubiac Reader", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "v${appMeta.versionName} • Build ${appMeta.versionCode}",
                                    style = MaterialTheme.typography.labelMedium.tabular(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TabularBadge(
                                text = if (settings.useMatteTexture) "MATTE" else "CLEAN",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "WORKSPACE & DISPLAY",
                        style = MaterialTheme.typography.labelSmall.kicker,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Palette,
                        title = "Appearance & Matte Surface",
                        subtitle = "Theme palette, paper-grain matte texture, dynamic tint & motion",
                        badge = settings.themeMode.name.replace("_", " "),
                        onClick = onOpenAppearance,
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Tune,
                        title = "Reader & Shelf Defaults",
                        subtitle = "Smart layout detection, tap zones, volume keys & library badges",
                        badge = settings.readerPreferences.tapZonePreset.name.replace("_", " "),
                        onClick = onOpenReader,
                    )
                }

                item {
                    Text(
                        text = "DATA & STORAGE",
                        style = MaterialTheme.typography.labelSmall.kicker,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Sync,
                        title = "AniList & Sync Policy",
                        subtitle = "Account sign-in, request budget & per-field local/sync locks",
                        badge = state.anilistUser?.name ?: "Local",
                        onClick = onOpenSync,
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Backup,
                        title = "Backup & Database",
                        subtitle = ".tachibk & JSON+ZIP exports, selective restore & SQLite vacuum",
                        badge = settings.autoBackupFrequency.name,
                        onClick = onOpenBackup,
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Download,
                        title = "Downloads & Local Storage",
                        subtitle = "Queue, SAF folder, concurrency & CBZ archive storage format",
                        badge = "${settings.concurrentPageDownloads}x",
                        onClick = onOpenDownloadsSettings,
                    )
                }

                item {
                    Text(
                        text = "SECURITY & PROJECT",
                        style = MaterialTheme.typography.labelSmall.kicker,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Security,
                        title = "Privacy & Network",
                        subtitle = "Incognito, biometric lock, FLAG_SECURE, User-Agent & DoH resolver",
                        badge = settings.dohProvider.name,
                        onClick = onOpenPrivacy,
                    )
                }

                item {
                    MatteNavigationRow(
                        icon = Icons.Rounded.Info,
                        title = "About Tubiac",
                        subtitle = "Version info, changelog, feedback form, bug reports & GitHub repo",
                        badge = "v${appMeta.versionName}",
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsCategoryScaffold(
        kicker = "SETTINGS // APPEARANCE",
        title = "Appearance & Matte Surface",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Theme Palette", icon = Icons.Rounded.Palette) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.updateSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.name.replace("_", " ")) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                SettingsToggleRow(
                    title = "Matte Paper-Grain Texture",
                    subtitle = "Apply subtle editorial micro-grain and hairline cards app-wide",
                    checked = settings.useMatteTexture,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(useMatteTexture = v) } },
                )
                SettingsToggleRow(
                    title = "Android 12+ Dynamic Color",
                    subtitle = "Use wallpaper palette; off uses Washi Paper & Sumi Ink signature",
                    checked = settings.useDynamicColor,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(useDynamicColor = v) } },
                )
                SettingsToggleRow(
                    title = "Cover-Derived Accent on Detail",
                    subtitle = "Tint Series Detail accents from artwork palette",
                    checked = settings.useCoverDerivedDetailAccent,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(useCoverDerivedDetailAccent = v) } },
                )
            }
        }

        item {
            SettingsMatteGroup(title = "Motion & Transitions", icon = Icons.Rounded.Tune) {
                Text("Animation Speed", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimationSpeed.entries.forEach { spd ->
                        FilterChip(
                            selected = settings.animationSpeed == spd,
                            onClick = { viewModel.updateSettings { it.copy(animationSpeed = spd) } },
                            label = { Text(spd.name) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSyncScreen(
    onBack: () -> Unit,
    onOpenAniListLogin: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val policy = state.settings.syncPolicy
    var directTokenInput by remember { mutableStateOf("") }
    var clientIdInput by remember(state.settings.anilistClientId) { mutableStateOf(state.settings.anilistClientId) }
    var conflictMode by remember { mutableStateOf(ConflictResolution.MERGE) }

    SettingsCategoryScaffold(
        kicker = "SETTINGS // SYNC HUB",
        title = "AniList & Sync Policy",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Direct AniList Account (graphql.anilist.co)", icon = Icons.Rounded.Sync) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.anilistUser?.name ?: "AniList Not Connected",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (state.anilistUser != null) {
                                "${state.anilistUser?.mangaCount ?: 0} titles • ${state.anilistUser?.chaptersRead ?: 0} ch • Queue: ${state.rateLimitStatus.pendingMutationsCount}"
                            } else {
                                "Direct API connection (${state.rateLimitStatus.remaining}/${state.rateLimitStatus.limit} req/min)"
                            },
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.anilistUser == null) {
                        Button(onClick = onOpenAniListLogin, shape = MaterialTheme.shapes.small) { Text("Sign In") }
                    } else {
                        OutlinedButton(onClick = viewModel::disconnectAniList, shape = MaterialTheme.shapes.small) {
                            Text("Disconnect")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = viewModel::syncAniListNow,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Push Queue")
                    }
                    Button(
                        onClick = { viewModel.pullAniListLibrary(conflictMode) },
                        enabled = state.anilistUser != null,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Pull Lists")
                    }
                }
                Text("Pull Conflict Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConflictResolution.entries.forEach { mode ->
                        FilterChip(
                            selected = conflictMode == mode,
                            onClick = { conflictMode = mode },
                            label = { Text(mode.name.replace("_", " ")) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                OutlinedTextField(
                    value = directTokenInput,
                    onValueChange = { directTokenInput = it },
                    label = { Text("Direct Bearer Token (optional bypass)") },
                    placeholder = { Text("Paste AniList OAuth JWT token...") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        viewModel.connectWithDirectToken(directTokenInput)
                        directTokenInput = ""
                    },
                    enabled = directTokenInput.isNotBlank(),
                    shape = MaterialTheme.shapes.small,
                ) { Text("Verify & Connect Token") }

                OutlinedTextField(
                    value = clientIdInput,
                    onValueChange = { clientIdInput = it },
                    label = { Text("Custom AniList OAuth Client ID (optional)") },
                    placeholder = { Text("Default built-in or anilist.co/settings/developer ID") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { viewModel.updateSettings { it.copy(anilistClientId = clientIdInput.trim()) } },
                    shape = MaterialTheme.shapes.small,
                ) { Text("Save Custom Client ID") }
            }
        }

        item {
            SettingsMatteGroup(title = "Field Sync Policy Matrix", icon = Icons.Rounded.Lock) {
                PolicyMatrixRow(
                    field = "Chapter Reading Progress",
                    synced = policy.chapterProgress == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(chapterProgress = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Reading Status",
                    synced = policy.readingStatus == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(readingStatus = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Personal Scores / Ratings",
                    synced = policy.personalScore == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(personalScore = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Custom Categories & Tags",
                    synced = policy.customCategoriesAndTags == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(customCategoriesAndTags = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Start & Finish Dates",
                    synced = policy.startAndFinishDates == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(startAndFinishDates = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Private Notes",
                    synced = policy.privateNotes == SyncFieldPolicy.SYNCED_TO_ANILIST,
                    onToggle = { s ->
                        viewModel.updateSyncPolicy {
                            it.copy(privateNotes = if (s) SyncFieldPolicy.SYNCED_TO_ANILIST else SyncFieldPolicy.LOCAL_ONLY)
                        }
                    },
                )
                PolicyMatrixRow(
                    field = "Detailed Reading History",
                    synced = false,
                    lockedLocalOnly = true,
                    onToggle = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsBackupScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreOptions by remember { mutableStateOf(SelectiveRestoreOptions()) }
    var pendingRestoreFormat by remember { mutableStateOf(BackupFormat.TACHIBK_GZIP) }

    val exportTachibkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.exportBackupToUri(it, BackupFormat.TACHIBK_GZIP) } }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.exportBackupToUri(it, BackupFormat.JSON_ZIP) } }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackupFromUri(it, pendingRestoreFormat, restoreOptions) } }

    SettingsCategoryScaffold(
        kicker = "SETTINGS // ARCHIVE",
        title = "Backup & Database",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Manual Backup & Restore", icon = Icons.Rounded.Backup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { exportTachibkLauncher.launch("tubiac-backup.tachibk") },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Export .tachibk") }
                    OutlinedButton(
                        onClick = { exportZipLauncher.launch("tubiac-backup.zip") },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Export JSON+ZIP") }
                }
                Button(
                    onClick = { showRestoreDialog = true },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import Backup (Selective Restore)") }
                Text("Auto-Backup Cadence", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoBackupFrequency.entries.forEach { freq ->
                        FilterChip(
                            selected = settings.autoBackupFrequency == freq,
                            onClick = { viewModel.updateSettings { it.copy(autoBackupFrequency = freq) } },
                            label = { Text(freq.name) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }

        item {
            SettingsMatteGroup(title = "Database & Storage Maintenance", icon = Icons.Rounded.CleaningServices) {
                OutlinedButton(
                    onClick = viewModel::clearCachesAndVacuum,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Image Cache, Cookies & Vacuum SQLite DB")
                }
                OutlinedButton(
                    onClick = viewModel::cleanOrphanedDownloads,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove Empty Orphaned Download Folders")
                }
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Selective Restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = pendingRestoreFormat == BackupFormat.TACHIBK_GZIP,
                            onClick = { pendingRestoreFormat = BackupFormat.TACHIBK_GZIP },
                            label = { Text(".tachibk") },
                            shape = MaterialTheme.shapes.small,
                        )
                        FilterChip(
                            selected = pendingRestoreFormat == BackupFormat.JSON_ZIP,
                            onClick = { pendingRestoreFormat = BackupFormat.JSON_ZIP },
                            label = { Text("JSON+ZIP") },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                    RestoreCheckbox("Library & Chapter states", restoreOptions.restoreLibraryAndChapters) {
                        restoreOptions = restoreOptions.copy(restoreLibraryAndChapters = it)
                    }
                    RestoreCheckbox("Categories & Tags", restoreOptions.restoreCategoriesAndTags) {
                        restoreOptions = restoreOptions.copy(restoreCategoriesAndTags = it)
                    }
                    RestoreCheckbox("Reading History", restoreOptions.restoreHistory) {
                        restoreOptions = restoreOptions.copy(restoreHistory = it)
                    }
                    RestoreCheckbox("AniList Mappings", restoreOptions.restoreAniListMappings) {
                        restoreOptions = restoreOptions.copy(restoreAniListMappings = it)
                    }
                    RestoreCheckbox("Extension Repositories", restoreOptions.restoreExtensionRepos) {
                        restoreOptions = restoreOptions.copy(restoreExtensionRepos = it)
                    }
                    RestoreCheckbox("App Settings", restoreOptions.restoreSettings) {
                        restoreOptions = restoreOptions.copy(restoreSettings = it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    importBackupLauncher.launch(arrayOf("*/*"))
                }) { Text("Choose Backup File") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDownloadsScreen(
    onBack: () -> Unit,
    onOpenDownloadQueue: () -> Unit,
    onPickLocalFolder: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsCategoryScaffold(
        kicker = "SETTINGS // OFFLINE",
        title = "Downloads & Local Storage",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Queue & Local Repository", icon = Icons.Rounded.Download) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onOpenDownloadQueue,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Download Queue") }
                    OutlinedButton(
                        onClick = onPickLocalFolder,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Local Folder") }
                }
                Text(
                    text = if (settings.localSourceTreeUri.isNullOrBlank()) {
                        "Local folder: Not configured"
                    } else {
                        "Local folder: Linked (SAF persistent)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsMatteGroup(title = "Transfer & Archiving Behavior", icon = Icons.Rounded.Tune) {
                Text(
                    text = "Concurrent page downloads: ${settings.concurrentPageDownloads}",
                    style = MaterialTheme.typography.labelLarge.tabular(),
                )
                Slider(
                    value = settings.concurrentPageDownloads.toFloat(),
                    onValueChange = { v -> viewModel.updateSettings { it.copy(concurrentPageDownloads = v.toInt()) } },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                SettingsToggleRow(
                    title = "Wi-Fi Only Downloads",
                    subtitle = "Pause queue on metered cellular networks",
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(wifiOnlyDownloads = v) } },
                )
                SettingsToggleRow(
                    title = "Auto-Delete Read Chapters",
                    subtitle = "Free space automatically once a chapter is completed",
                    checked = settings.autoDeleteReadChapters,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoDeleteReadChapters = v) } },
                )
                Text(
                    text = "Download Ahead: ${settings.downloadAheadCount} chapter(s)",
                    style = MaterialTheme.typography.labelLarge.tabular(),
                )
                Slider(
                    value = settings.downloadAheadCount.toFloat(),
                    onValueChange = { v -> viewModel.updateSettings { it.copy(downloadAheadCount = v.toInt()) } },
                    valueRange = 0f..5f,
                    steps = 4,
                )
                Text("Archive Storage Format", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadStorageFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = settings.downloadStorageFormat == fmt,
                            onClick = { viewModel.updateSettings { it.copy(downloadStorageFormat = fmt) } },
                            label = { Text(fmt.name.replace("_", " ")) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsReaderScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsCategoryScaffold(
        kicker = "SETTINGS // VIEWER",
        title = "Reader & Shelf Defaults",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Reader Engine Defaults", icon = Icons.Rounded.Tune) {
                SettingsToggleRow(
                    title = "Smart Reader Auto-Detection",
                    subtitle = "Webtoon for Manhwa/tall strips, RTL for Manga",
                    checked = settings.readerPreferences.smartAutoDetect,
                    onCheckedChange = { v ->
                        viewModel.updateSettings {
                            it.copy(readerPreferences = it.readerPreferences.copy(smartAutoDetect = v))
                        }
                    },
                )
                SettingsToggleRow(
                    title = "Volume Key Page Navigation",
                    subtitle = "Turn pages using hardware volume rocker",
                    checked = settings.readerPreferences.volumeKeyNavigation,
                    onCheckedChange = { v ->
                        viewModel.updateSettings {
                            it.copy(readerPreferences = it.readerPreferences.copy(volumeKeyNavigation = v))
                        }
                    },
                )
                Text("Tap Zone Preset", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TapZonePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = settings.readerPreferences.tapZonePreset == preset,
                            onClick = {
                                viewModel.updateSettings {
                                    it.copy(readerPreferences = it.readerPreferences.copy(tapZonePreset = preset))
                                }
                            },
                            label = { Text(preset.name.replace("_", " ")) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }

        item {
            SettingsMatteGroup(title = "Shelf Updates & Badges", icon = Icons.Rounded.Sync) {
                SettingsToggleRow(
                    title = "Skip Completed Series on Library Update",
                    subtitle = "Faster background updates with fewer requests",
                    checked = settings.skipCompletedOnUpdate,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(skipCompletedOnUpdate = v) } },
                )
                SettingsToggleRow(
                    title = "Show Unread Counter Badges",
                    subtitle = "Display unread count badge on Shelf cover cards",
                    checked = settings.showUnreadBadges,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(showUnreadBadges = v) } },
                )
                SettingsToggleRow(
                    title = "Show Downloaded Chapter Badges",
                    subtitle = "Display offline count badge on Shelf cover cards",
                    checked = settings.showDownloadBadges,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(showDownloadBadges = v) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPrivacyScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    var uaInput by remember(settings.customUserAgent) { mutableStateOf(settings.customUserAgent) }

    SettingsCategoryScaffold(
        kicker = "SETTINGS // PROTECTION",
        title = "Privacy & Network",
        feedback = state.statusFeedback,
        onBack = onBack,
    ) {
        item {
            SettingsMatteGroup(title = "Privacy & Device Lock", icon = Icons.Rounded.Security) {
                SettingsToggleRow(
                    title = "Incognito Mode",
                    subtitle = "Never record history or push AniList sync updates",
                    checked = settings.incognitoMode,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(incognitoMode = v) } },
                )
                SettingsToggleRow(
                    title = "Biometric / Device PIN App Lock",
                    subtitle = "Require authentication when launching Tubiac",
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(biometricLockEnabled = v) } },
                )
                SettingsToggleRow(
                    title = "Secure Screen (FLAG_SECURE)",
                    subtitle = "Block screenshots and hide app preview in recents",
                    checked = settings.secureScreenEnabled,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(secureScreenEnabled = v) } },
                )
                SettingsToggleRow(
                    title = "Show 18+ / NSFW Extensions",
                    subtitle = "Disabled by default in Browse > Extensions",
                    checked = settings.showNsfwExtensions,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(showNsfwExtensions = v) } },
                )
                SettingsToggleRow(
                    title = "Automatic Crash & Logcat Reporting",
                    subtitle = "Automatically send redacted bugs, crashes & extension failures to GitHub Issues",
                    checked = settings.autoReportIssuesEnabled,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoReportIssuesEnabled = v) } },
                )
            }
        }

        item {
            SettingsMatteGroup(title = "Networking, Headers & DoH", icon = Icons.Rounded.Lock) {
                SettingsToggleRow(
                    title = "Spoof X-Requested-With Header",
                    subtitle = "Improves extension and Cloudflare WebView compatibility",
                    checked = settings.spoofXRequestedWith,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(spoofXRequestedWith = v) } },
                )
                Text("DNS-over-HTTPS (DoH)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DoHProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = settings.dohProvider == provider,
                            onClick = { viewModel.updateSettings { it.copy(dohProvider = provider) } },
                            label = { Text(provider.label) },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
                OutlinedTextField(
                    value = uaInput,
                    onValueChange = { uaInput = it },
                    label = { Text("Custom User-Agent") },
                    minLines = 2,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (uaInput.isNotBlank()) {
                                viewModel.updateSettings { it.copy(customUserAgent = uaInput.trim()) }
                            }
                        },
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Save User-Agent") }
                    OutlinedButton(
                        onClick = {
                            uaInput = AppSettings.DEFAULT_USER_AGENT
                            viewModel.updateSettings { it.copy(customUserAgent = AppSettings.DEFAULT_USER_AGENT) }
                        },
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Reset Default") }
                }
            }
        }
    }
}

@Composable
internal fun SettingsCategoryScaffold(
    kicker: String,
    title: String,
    feedback: String?,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = kicker,
                    title = title,
                    onBack = onBack,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                feedback?.let { msg ->
                    item {
                        MatteCard(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
internal fun SettingsMatteGroup(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    MatteCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PolicyMatrixRow(
    field: String,
    synced: Boolean,
    onToggle: (Boolean) -> Unit,
    lockedLocalOnly: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(field, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    lockedLocalOnly -> "Locked Local-Only (never sent to servers)"
                    synced -> "Synced to AniList"
                    else -> "Local-Only in Room DB"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lockedLocalOnly) {
            Icon(Icons.Rounded.Lock, contentDescription = "Locked local-only", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Switch(checked = synced, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun RestoreCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
