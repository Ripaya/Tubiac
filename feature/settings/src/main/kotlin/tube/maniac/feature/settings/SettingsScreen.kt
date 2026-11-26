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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.anilist.AniListSyncEngine
import tube.maniac.core.database.BackupFormat
import tube.maniac.core.database.BackupManager
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.designsystem.theme.tabular
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
) : ViewModel() {

    private val feedbackFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settingsFlow,
        settingsRepository.anilistUserFlow,
        aniListClient.rateLimiter.status,
        feedbackFlow,
    ) { settings, user, rateLimit, feedback ->
        SettingsUiState(settings, user, rateLimit, feedback)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(transform)
            val updated = settingsRepository.currentSettings()
            networkConfig.dohProvider.set(updated.dohProvider)
            networkConfig.userAgent.set(updated.customUserAgent)
            networkConfig.spoofXRequestedWith.set(updated.spoofXRequestedWith)
            AutoBackupWorker.schedule(context, updated.autoBackupFrequency)
        }
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
            val removed = dir?.listFiles()?.count { it.isDirectory && it.listFiles().isNullOrEmpty() } ?: 0
            dir?.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach(File::deleteRecursively)
            feedbackFlow.value = "Removed $removed empty orphaned download folder(s)."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenDownloads: () -> Unit,
    onOpenAniListLogin: () -> Unit,
    onPickLocalFolder: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreOptions by remember { mutableStateOf(SelectiveRestoreOptions()) }
    var pendingRestoreFormat by remember { mutableStateOf(BackupFormat.TACHIBK_GZIP) }
    var importConflict by remember { mutableStateOf(ConflictResolution.MERGE) }

    val exportTachibkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.exportBackupToUri(it, BackupFormat.TACHIBK_GZIP) } }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.exportBackupToUri(it, BackupFormat.JSON_ZIP) } }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackupFromUri(it, pendingRestoreFormat, restoreOptions) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.statusFeedback?.let { feedback ->
                item {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            // 1. Appearance & Motion
            item {
                SettingsSectionCard(title = "Appearance & Motion", icon = Icons.Rounded.Palette) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.updateSettings { it.copy(themeMode = mode) } },
                                label = { Text(mode.name.replace("_", " ")) },
                            )
                        }
                    }
                    SettingsToggleRow(
                        title = "Android 12+ Dynamic Color",
                        subtitle = "Falls back to Tubiac Ink & Vermilion when off",
                        checked = settings.useDynamicColor,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(useDynamicColor = v) } },
                    )
                    SettingsToggleRow(
                        title = "Cover-Derived Accent on Detail",
                        subtitle = "Tint Series Detail buttons from artwork palette",
                        checked = settings.useCoverDerivedDetailAccent,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(useCoverDerivedDetailAccent = v) } },
                    )
                    Text("Animation Speed", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimationSpeed.entries.forEach { spd ->
                            FilterChip(
                                selected = settings.animationSpeed == spd,
                                onClick = { viewModel.updateSettings { it.copy(animationSpeed = spd) } },
                                label = { Text(spd.name) },
                            )
                        }
                    }
                }
            }

            // 2. Data & Sync Hub (AniList + Granular Local vs Synced Policy Matrix)
            item {
                SettingsSectionCard(title = "Data & Sync Hub (AniList)", icon = Icons.Rounded.Sync) {
                    // AniList Account & Rate-Limit Card
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
                                text = "Rate limit: ${state.rateLimitStatus.remaining}/${state.rateLimitStatus.limit} req/min" +
                                    (if (state.rateLimitStatus.isDegradedMode) " (Degraded)" else "") +
                                    " • Queued: ${state.rateLimitStatus.pendingMutationsCount}",
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.anilistUser == null) {
                            Button(onClick = onOpenAniListLogin, shape = CircleShape) { Text("Sign In") }
                        } else {
                            OutlinedButton(onClick = viewModel::disconnectAniList, shape = CircleShape) {
                                Text("Disconnect")
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = viewModel::syncAniListNow, shape = CircleShape) {
                            Text("Sync Now")
                        }
                        ConflictResolution.entries.forEach { mode ->
                            FilterChip(
                                selected = importConflict == mode,
                                onClick = { importConflict = mode },
                                label = { Text(mode.name.replace("_", " ")) },
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text("Local-Only vs. Synced Policy Matrix", style = MaterialTheme.typography.titleSmall)

                    val policy = settings.syncPolicy
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

            // 3. Backup, Restore & Database Maintenance
            item {
                SettingsSectionCard(title = "Backup, Restore & Database", icon = Icons.Rounded.Backup) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { exportTachibkLauncher.launch("tubiac-backup.tachibk") },
                            shape = CircleShape,
                        ) { Text("Export .tachibk") }
                        OutlinedButton(
                            onClick = { exportZipLauncher.launch("tubiac-backup.zip") },
                            shape = CircleShape,
                        ) { Text("Export JSON+ZIP") }
                    }
                    FilledTonalButton(
                        onClick = { showRestoreDialog = true },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import Backup (Selective Restore)") }

                    Text("Auto-Backup Schedule", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoBackupFrequency.entries.forEach { freq ->
                            FilterChip(
                                selected = settings.autoBackupFrequency == freq,
                                onClick = { viewModel.updateSettings { it.copy(autoBackupFrequency = freq) } },
                                label = { Text(freq.name) },
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::clearCachesAndVacuum, shape = CircleShape) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear Cache & Vacuum DB")
                        }
                        OutlinedButton(onClick = viewModel::cleanOrphanedDownloads, shape = CircleShape) {
                            Text("Clean Orphans")
                        }
                    }
                }
            }

            // 4. Downloads & Local Source
            item {
                SettingsSectionCard(title = "Downloads & Local Source", icon = Icons.Rounded.Download) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onOpenDownloads, shape = CircleShape) {
                            Text("Open Download Queue")
                        }
                        OutlinedButton(onClick = onPickLocalFolder, shape = CircleShape) {
                            Text("Select Local Folder")
                        }
                    }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DownloadStorageFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = settings.downloadStorageFormat == fmt,
                                onClick = { viewModel.updateSettings { it.copy(downloadStorageFormat = fmt) } },
                                label = { Text(fmt.name.replace("_", " ")) },
                            )
                        }
                    }
                }
            }

            // 5. Reader & Shelf Defaults
            item {
                SettingsSectionCard(title = "Reader & Shelf Defaults", icon = Icons.Rounded.Sync) {
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
                    SettingsToggleRow(
                        title = "Skip Completed Series on Library Update",
                        subtitle = "Faster background updates with fewer requests",
                        checked = settings.skipCompletedOnUpdate,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(skipCompletedOnUpdate = v) } },
                    )
                    Text("Tap Zone Preset", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TapZonePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = settings.readerPreferences.tapZonePreset == preset,
                                onClick = {
                                    viewModel.updateSettings {
                                        it.copy(readerPreferences = it.readerPreferences.copy(tapZonePreset = preset))
                                    }
                                },
                                label = { Text(preset.name.replace("_", " ")) },
                            )
                        }
                    }
                }
            }

            // 6. Privacy & Security (Incognito, Biometric/PIN, FLAG_SECURE, DoH)
            item {
                SettingsSectionCard(title = "Privacy & Security", icon = Icons.Rounded.Security) {
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
                        title = "Spoof X-Requested-With Header",
                        subtitle = "Improves extension and Cloudflare WebView compatibility",
                        checked = settings.spoofXRequestedWith,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(spoofXRequestedWith = v) } },
                    )
                    SettingsToggleRow(
                        title = "Show 18+ / NSFW Extensions",
                        subtitle = "Disabled by default in Browse > Extensions",
                        checked = settings.showNsfwExtensions,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(showNsfwExtensions = v) } },
                    )
                    Text("DNS-over-HTTPS (DoH)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DoHProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = settings.dohProvider == provider,
                                onClick = { viewModel.updateSettings { it.copy(dohProvider = provider) } },
                                label = { Text(provider.label) },
                            )
                        }
                    }
                }
            }

            // 7. About & Disclaimer
            item {
                SettingsSectionCard(title = "About Tubiac", icon = Icons.Rounded.Info) {
                    Text("Tubiac v0.1.0-alpha1 (tube.maniac)", style = MaterialTheme.typography.titleSmall.tabular())
                    Text(
                        text = "Tubiac is strictly an open-source reader application and AniList tracking client licensed under GNU GPLv3. It does not host, distribute, or bundle any copyrighted media content or third-party extension code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        )
                        FilterChip(
                            selected = pendingRestoreFormat == BackupFormat.JSON_ZIP,
                            onClick = { pendingRestoreFormat = BackupFormat.JSON_ZIP },
                            label = { Text("JSON+ZIP") },
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

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
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
