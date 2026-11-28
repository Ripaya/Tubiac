package tube.maniac.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.ExtensionRepoEntity
import tube.maniac.core.database.entity.SeriesEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.MatteSegmentedTabs
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.unwrapRoot
import tube.maniac.core.extension.loader.ExtensionExtrasExtractor
import tube.maniac.core.extension.loader.ExtensionRepoManager
import tube.maniac.core.extension.loader.LocalSource
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.AniListMedia
import tube.maniac.core.model.DomainCookieInfo
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.model.ExtensionRepo
import tube.maniac.core.model.ExtractedPreferenceItem
import tube.maniac.core.model.ExtractedPreferenceType
import tube.maniac.core.model.MigrationCandidate
import tube.maniac.core.model.PublicationStatus
import javax.inject.Inject

enum class BrowseSubTab(val label: String) {
    SOURCES("Sources"),
    EXTENSIONS("Extensions"),
    REPOS("Repos"),
}

enum class ExtensionFilterTab(val label: String) {
    INSTALLED("Installed"),
    AVAILABLE("Available"),
    UPDATES("Updates"),
}

data class InspectedSourceBundle(
    val title: String,
    val pkgName: String? = null,
    val primarySourceId: Long? = null,
    val baseUrls: List<String> = emptyList(),
    val preferences: List<ExtractedPreferenceItem> = emptyList(),
    val cookies: List<DomainCookieInfo> = emptyList(),
    val isSandboxInstalled: Boolean = false,
)

data class BrowseUiState(
    val activeTab: BrowseSubTab = BrowseSubTab.SOURCES,
    val sources: List<Source> = emptyList(),
    val installedExtensions: List<ExtensionInfo> = emptyList(),
    val extensionPrimarySourceIds: Map<String, Long> = emptyMap(),
    val loadErrors: Map<String, String> = emptyMap(),
    val remoteExtensions: List<ExtensionInfo> = emptyList(),
    val repos: List<ExtensionRepo> = emptyList(),
    val showNsfw: Boolean = false,
    val extensionSubTab: ExtensionFilterTab = ExtensionFilterTab.INSTALLED,
    val langFilter: String = "all",
    val extensionQuery: String = "",
    val statusMessage: String? = null,
    val inspectedBundle: InspectedSourceBundle? = null,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repoManager: ExtensionRepoManager,
    private val repoDao: ExtensionRepoDao,
    private val settingsRepository: SettingsRepository,
    private val extrasExtractor: ExtensionExtrasExtractor,
) : ViewModel() {

    private val activeTabFlow = MutableStateFlow(BrowseSubTab.SOURCES)
    private val extSubTabFlow = MutableStateFlow(ExtensionFilterTab.INSTALLED)
    private val langFilterFlow = MutableStateFlow("all")
    private val extQueryFlow = MutableStateFlow("")
    private val remoteExtensionsFlow = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    private val inspectedBundleFlow = MutableStateFlow<InspectedSourceBundle?>(null)

    val uiState: StateFlow<BrowseUiState> = combine(
        combine(
            activeTabFlow,
            sourceManager.sources,
            sourceManager.installedExtensions,
            remoteExtensionsFlow,
            repoDao.observeAll(),
        ) { tab, sources, installed, remote, repos ->
            BrowseUiState(
                activeTab = tab,
                sources = sources,
                installedExtensions = installed.map { it.info },
                extensionPrimarySourceIds = installed.mapNotNull { ext ->
                    ext.sources.firstOrNull()?.id?.let { sid -> ext.info.pkgName to sid }
                }.toMap(),
                loadErrors = installed.mapNotNull { ext ->
                    ext.loadError?.let { ext.info.name to it }
                }.toMap(),
                remoteExtensions = remote,
                repos = repos.map { it.toDomain() },
            )
        },
        combine(
            settingsRepository.settingsFlow,
            extSubTabFlow,
            langFilterFlow,
            extQueryFlow,
            statusMessageFlow,
        ) { settings, extTab, lang, extQuery, msg ->
            listOf(settings, extTab, lang, extQuery, msg)
        },
        inspectedBundleFlow,
    ) { base, extras, bundle ->
        @Suppress("UNCHECKED_CAST")
        val settings = extras[0] as tube.maniac.core.model.AppSettings
        val extTab = extras[1] as ExtensionFilterTab
        val lang = extras[2] as String
        val extQuery = extras[3] as String
        val msg = extras[4] as String?
        base.copy(
            showNsfw = settings.showNsfwExtensions,
            extensionSubTab = extTab,
            langFilter = lang,
            extensionQuery = extQuery,
            statusMessage = msg,
            inspectedBundle = bundle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    init {
        sourceManager.reloadExtensions()
        viewModelScope.launch {
            if (repoDao.getAll().any { it.enabled }) {
                refreshRepos()
            }
        }
    }

    fun selectTab(tab: BrowseSubTab) {
        activeTabFlow.value = tab
    }

    fun selectExtensionTab(tab: ExtensionFilterTab) {
        extSubTabFlow.value = tab
    }

    fun selectLang(lang: String) {
        langFilterFlow.value = lang
    }

    fun updateExtensionQuery(q: String) {
        extQueryFlow.value = q
    }

    fun refreshAll() {
        sourceManager.reloadExtensions()
        refreshRepos()
    }

    fun refreshRepos() {
        viewModelScope.launch {
            statusMessageFlow.value = "Decoding repository indices (index.pb / repo.json / index.min.json)..."
            val installedMap = sourceManager.installedExtensions.value.associate { it.info.pkgName to it.info }
            val result = repoManager.refreshAllEnabledReposWithErrors(installedMap)
            remoteExtensionsFlow.value = result.extensions
            statusMessageFlow.value = if (result.errors.isEmpty()) {
                "Loaded ${result.extensions.size} extensions from enabled repos"
            } else {
                val errorSummary = result.errors.entries.joinToString("; ") { (repo, err) -> "$repo: $err" }
                "Loaded ${result.extensions.size} extensions • Errors: $errorSummary"
            }
        }
    }

    fun addRepo(name: String, indexUrl: String, trusted: Boolean) {
        if (indexUrl.isBlank()) return
        val normalized = ExtensionRepoManager.normalizeRepoIndexUrl(indexUrl)
        viewModelScope.launch {
            repoDao.upsert(
                ExtensionRepoEntity(
                    name = name.ifBlank { normalized.substringAfter("://").substringBefore("/") },
                    indexUrl = normalized,
                    enabled = true,
                    trusted = trusted,
                ),
            )
            refreshRepos()
        }
    }

    fun toggleRepo(id: Long, enabled: Boolean) {
        viewModelScope.launch { repoDao.setEnabled(id, enabled) }
    }

    fun toggleRepoTrusted(id: Long, trusted: Boolean) {
        viewModelScope.launch {
            repoDao.setTrusted(id, trusted)
            refreshRepos()
        }
    }

    fun deleteRepo(id: Long) {
        viewModelScope.launch { repoDao.deleteById(id) }
    }

    fun installExtension(ext: ExtensionInfo) {
        viewModelScope.launch {
            statusMessageFlow.value = "Extracting ${ext.name} into sandbox..."
            repoManager.installExtensionApk(ext)
                .onSuccess {
                    sourceManager.reloadExtensions()
                    val loadedList = sourceManager.installedExtensions.value
                    val installedMap = loadedList.associate { it.info.pkgName to it.info }
                    val updatedRemote = remoteExtensionsFlow.value.map { item ->
                        if (item.pkgName == ext.pkgName) {
                            item.copy(
                                installState = ExtensionInstallState.INSTALLED,
                                installedVersionCode = item.versionCode,
                                isSandboxInstalled = true,
                            )
                        } else {
                            val match = installedMap[item.pkgName]
                            if (match != null) item.copy(installState = ExtensionInstallState.INSTALLED, installedVersionCode = match.versionCode)
                            else item
                        }
                    }
                    remoteExtensionsFlow.value = updatedRemote
                    val justLoaded = loadedList.firstOrNull { it.info.pkgName == ext.pkgName }
                    val loadedSources = justLoaded?.sources.orEmpty()
                    statusMessageFlow.value = when {
                        justLoaded?.loadError != null -> "${ext.name} load failed: ${justLoaded.loadError}"
                        loadedSources.isEmpty() -> "${ext.name} extracted, but no Source entries loaded."
                        else -> "${ext.name} loaded (${loadedSources.size} source(s) ready)."
                    }
                }
                .onFailure { err ->
                    val root = err.unwrapRoot()
                    statusMessageFlow.value = root.message ?: "Install failed"
                }
        }
    }

    fun installExtensionSystem(ext: ExtensionInfo) {
        viewModelScope.launch {
            statusMessageFlow.value = "Launching OS installer for ${ext.name}..."
            repoManager.installSystemApk(ext)
                .onFailure { err ->
                    val root = err.unwrapRoot()
                    AutoIssueReporter.reportRuntimeBug(
                        category = "extension-install",
                        title = "[Extension] System PackageInstaller failed for ${ext.pkgName}",
                        detail = root.message ?: "System install failed",
                        throwable = root,
                    )
                    statusMessageFlow.value = root.message ?: "System install failed"
                }
        }
    }

    fun uninstallExtension(ext: ExtensionInfo) {
        repoManager.uninstallExtension(ext.pkgName, ext.isSandboxInstalled)
        sourceManager.reloadExtensions()
        statusMessageFlow.value = "Removed ${ext.name}."
    }

    fun inspectSource(source: Source) {
        viewModelScope.launch {
            val prefs = extrasExtractor.extractSourcePreferences(source).getOrDefault(emptyList())
            val urls = listOfNotNull((source as? HttpSource)?.baseUrl)
            val cookies = extrasExtractor.inspectDomainCookies(urls)
            inspectedBundleFlow.value = InspectedSourceBundle(
                title = source.name,
                primarySourceId = source.id,
                baseUrls = urls,
                preferences = prefs,
                cookies = cookies,
            )
        }
    }

    fun inspectInstalledExtension(ext: ExtensionInfo) {
        viewModelScope.launch {
            val loaded = sourceManager.installedExtensions.value.firstOrNull { it.info.pkgName == ext.pkgName }
            val primarySource = loaded?.sources?.firstOrNull()
            val prefs = primarySource?.let { extrasExtractor.extractSourcePreferences(it).getOrDefault(emptyList()) }
                .orEmpty()
            val urls = loaded?.sources?.mapNotNull { (it as? HttpSource)?.baseUrl }?.distinct().orEmpty()
            val cookies = extrasExtractor.inspectDomainCookies(urls)
            inspectedBundleFlow.value = InspectedSourceBundle(
                title = ext.name,
                pkgName = ext.pkgName,
                primarySourceId = primarySource?.id,
                baseUrls = urls,
                preferences = prefs,
                cookies = cookies,
                isSandboxInstalled = ext.isSandboxInstalled,
            )
        }
    }

    fun dismissInspector() {
        inspectedBundleFlow.value = null
    }

    fun saveTextPref(sourceId: Long, key: String, value: String) {
        extrasExtractor.updateTextPreference(sourceId, key, value)
        inspectedBundleFlow.value?.let { bundle ->
            inspectedBundleFlow.value = bundle.copy(
                preferences = bundle.preferences.map {
                    if (it.key == key) it.copy(currentValue = value) else it
                },
            )
        }
    }

    fun saveBoolPref(sourceId: Long, key: String, value: Boolean) {
        extrasExtractor.updateBooleanPreference(sourceId, key, value)
        inspectedBundleFlow.value?.let { bundle ->
            inspectedBundleFlow.value = bundle.copy(
                preferences = bundle.preferences.map {
                    if (it.key == key) it.copy(booleanValue = value) else it
                },
            )
        }
    }

    fun toggleMultiSelectPref(sourceId: Long, key: String, value: String) {
        val bundle = inspectedBundleFlow.value ?: return
        val currentSet = bundle.preferences.firstOrNull { it.key == key }?.multiValues.orEmpty()
        val updatedSet = if (value in currentSet) currentSet - value else currentSet + value
        extrasExtractor.updateMultiSelectPreference(sourceId, key, updatedSet)
        inspectedBundleFlow.value = bundle.copy(
            preferences = bundle.preferences.map {
                if (it.key == key) it.copy(multiValues = updatedSet) else it
            },
        )
    }

    fun clearDomainCookies(domain: String) {
        extrasExtractor.clearCookiesForDomain(domain)
        inspectedBundleFlow.value?.let { bundle ->
            inspectedBundleFlow.value = bundle.copy(
                cookies = extrasExtractor.inspectDomainCookies(bundle.baseUrls),
            )
        }
        statusMessageFlow.value = "Cleared cookies for $domain."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenGlobalSearch: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onOpenWebView: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenMigration: (Long) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddRepoDialog by remember { mutableStateOf(false) }

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "CATALOGUE",
                    title = "Browse",
                    actions = {
                        IconButton(onClick = onOpenGlobalSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "Global Search")
                        }
                        IconButton(onClick = viewModel::refreshAll) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Reload extensions & repositories")
                        }
                    },
                )
            },
            floatingActionButton = {
                if (state.activeTab == BrowseSubTab.REPOS) {
                    FloatingActionButton(
                        onClick = { showAddRepoDialog = true },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add Repo Store")
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                MatteSegmentedTabs(
                    options = BrowseSubTab.entries.map { it.label },
                    selectedIndex = BrowseSubTab.entries.indexOf(state.activeTab).coerceAtLeast(0),
                    onSelect = { idx -> viewModel.selectTab(BrowseSubTab.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                state.statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (msg.contains("Errors:") || msg.contains("failed", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                when (state.activeTab) {
                    BrowseSubTab.SOURCES -> SourcesList(
                        sources = state.sources,
                        onGlobalSearch = onOpenGlobalSearch,
                        onOpenSource = onOpenSource,
                        onOpenWebView = onOpenWebView,
                        onInspectSource = viewModel::inspectSource,
                    )

                    BrowseSubTab.EXTENSIONS -> ExtensionsList(
                        state = state,
                        onQueryChange = viewModel::updateExtensionQuery,
                        onSelectTab = viewModel::selectExtensionTab,
                        onSelectLang = viewModel::selectLang,
                        onInstall = viewModel::installExtension,
                        onInstallSystem = viewModel::installExtensionSystem,
                        onUninstall = viewModel::uninstallExtension,
                        onInspectExtension = viewModel::inspectInstalledExtension,
                        onOpenSource = onOpenSource,
                    )

                    BrowseSubTab.REPOS -> ReposList(
                        repos = state.repos,
                        onToggle = viewModel::toggleRepo,
                        onToggleTrusted = viewModel::toggleRepoTrusted,
                        onDelete = viewModel::deleteRepo,
                        onAddClick = { showAddRepoDialog = true },
                    )
                }
            }
        }
    }

    state.inspectedBundle?.let { bundle ->
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissInspector,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(bundle.title, style = MaterialTheme.typography.titleLarge)
                bundle.pkgName?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (bundle.baseUrls.isNotEmpty()) {
                    Text("LOGIN & COOKIES", style = MaterialTheme.typography.labelSmall.kicker, color = MaterialTheme.colorScheme.primary)
                    bundle.baseUrls.forEach { baseUrl ->
                        val host = baseUrl.substringAfter("://").substringBefore("/")
                        val cookieInfo = bundle.cookies.firstOrNull { it.domain.equals(host, ignoreCase = true) }
                        MatteCard {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(host, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (cookieInfo != null && cookieInfo.cookieNames.isNotEmpty()) {
                                        "Cookies (${cookieInfo.cookieNames.size}): ${cookieInfo.cookieNames.take(4).joinToString(", ")}" +
                                            (if (cookieInfo.hasCloudflareClearance) " • CF Cleared" else "") +
                                            (if (cookieInfo.hasSessionCookie) " • Session Active" else "")
                                    } else {
                                        "No domain cookies captured yet."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { onOpenWebView(baseUrl) },
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Sign In / WebView")
                                    }
                                    if (cookieInfo != null && cookieInfo.cookieNames.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { viewModel.clearDomainCookies(host) },
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Icon(Icons.Rounded.Cookie, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Clear")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (bundle.preferences.isNotEmpty() && bundle.primarySourceId != null) {
                    Text("SOURCE PREFERENCES", style = MaterialTheme.typography.labelSmall.kicker, color = MaterialTheme.colorScheme.primary)
                    bundle.preferences.forEach { pref ->
                        MatteCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(pref.title, style = MaterialTheme.typography.titleSmall)
                                if (pref.summary.isNotBlank()) {
                                    Text(pref.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when (pref.type) {
                                    ExtractedPreferenceType.SWITCH -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(if (pref.booleanValue) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium)
                                            Switch(
                                                checked = pref.booleanValue,
                                                onCheckedChange = { v -> viewModel.saveBoolPref(bundle.primarySourceId, pref.key, v) },
                                            )
                                        }
                                    }
                                    ExtractedPreferenceType.LIST -> {
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            pref.entryValues.forEachIndexed { idx, value ->
                                                val label = pref.entries.getOrNull(idx) ?: value
                                                FilterChip(
                                                    selected = pref.currentValue == value,
                                                    onClick = { viewModel.saveTextPref(bundle.primarySourceId, pref.key, value) },
                                                    label = { Text(label) },
                                                    shape = MaterialTheme.shapes.small,
                                                )
                                            }
                                        }
                                    }
                                    ExtractedPreferenceType.MULTI_SELECT -> {
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            pref.entryValues.forEachIndexed { idx, value ->
                                                val label = pref.entries.getOrNull(idx) ?: value
                                                FilterChip(
                                                    selected = value in pref.multiValues,
                                                    onClick = { viewModel.toggleMultiSelectPref(bundle.primarySourceId, pref.key, value) },
                                                    label = { Text(label) },
                                                    shape = MaterialTheme.shapes.small,
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        var draft by remember(pref.key, pref.currentValue) { mutableStateOf(pref.currentValue) }
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { draft = it },
                                            singleLine = true,
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        TextButton(onClick = { viewModel.saveTextPref(bundle.primarySourceId, pref.key, draft) }) {
                                            Text("Save")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }

    if (showAddRepoDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var trusted by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddRepoDialog = false },
            title = { Text("Add Extension Repo Store") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter a Mihon/Tachiyomi repo URL (supports index.pb, repo.json, index.min.json, base repo URL, or tachiyomi://add-repo?url=...).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://.../index.pb or repo base") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Trust extensions from this repository", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = trusted, onCheckedChange = { trusted = it })
                    }
                }
            },
            confirmButton = {
                val trimmed = url.trim()
                val validInput = trimmed.startsWith("https://", true) ||
                    trimmed.startsWith("http://", true) ||
                    trimmed.startsWith("tachiyomi://", true) ||
                    trimmed.startsWith("mihon://", true)
                TextButton(
                    onClick = {
                        viewModel.addRepo(name, url, trusted)
                        showAddRepoDialog = false
                    },
                    enabled = validInput,
                ) { Text("Add Repo") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRepoDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SourcesList(
    sources: List<Source>,
    onGlobalSearch: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onOpenWebView: (String) -> Unit,
    onInspectSource: (Source) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(
                onClick = onGlobalSearch,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Global Search Across Sources & AniList")
            }
        }
        items(sources, key = { it.id }) { source ->
            MatteCard(
                onClick = { onOpenSource(source.id) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (source.id == LocalSource.LOCAL_SOURCE_ID) {
                                Icons.Rounded.Folder
                            } else {
                                Icons.Rounded.Extension
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(source.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (source.id == LocalSource.LOCAL_SOURCE_ID) {
                                    "SAF CBZ, CBR, ZIP, EPUB & folders"
                                } else {
                                    source.lang.uppercase().ifBlank { "ALL" }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (source is HttpSource) {
                            IconButton(onClick = { onInspectSource(source) }) {
                                Icon(Icons.Rounded.Tune, contentDescription = "Source settings & cookies")
                            }
                            IconButton(onClick = { onOpenWebView(source.baseUrl) }) {
                                Icon(Icons.Rounded.Public, contentDescription = "Open in WebView")
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Browse source",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionsList(
    state: BrowseUiState,
    onQueryChange: (String) -> Unit,
    onSelectTab: (ExtensionFilterTab) -> Unit,
    onSelectLang: (String) -> Unit,
    onInstall: (ExtensionInfo) -> Unit,
    onInstallSystem: (ExtensionInfo) -> Unit,
    onUninstall: (ExtensionInfo) -> Unit,
    onInspectExtension: (ExtensionInfo) -> Unit,
    onOpenSource: (Long) -> Unit,
) {
    val dynamicLangs = remember(state.installedExtensions, state.remoteExtensions) {
        val detected = (state.installedExtensions + state.remoteExtensions)
            .map { it.lang.lowercase() }
            .filter { it.isNotBlank() && it != "all" }
            .distinct()
        (listOf("all", "en", "tr", "ja", "ko", "zh") + detected).distinct()
    }

    val query = state.extensionQuery.trim()
    val list = when (state.extensionSubTab) {
        ExtensionFilterTab.INSTALLED -> state.installedExtensions
        ExtensionFilterTab.AVAILABLE -> state.remoteExtensions.filter {
            it.installState == ExtensionInstallState.AVAILABLE || it.installState == ExtensionInstallState.UNTRUSTED
        }
        ExtensionFilterTab.UPDATES -> state.remoteExtensions.filter {
            it.installState == ExtensionInstallState.UPDATE_AVAILABLE
        }
    }.filter { ext ->
        (state.showNsfw || !ext.isNsfw) &&
            (state.langFilter == "all" || ext.lang.equals(state.langFilter, ignoreCase = true)) &&
            (query.isBlank() || ext.name.contains(query, ignoreCase = true) ||
                ext.pkgName.contains(query, ignoreCase = true) ||
                ext.sources.any { it.name.contains(query, ignoreCase = true) })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.extensionQuery,
            onValueChange = onQueryChange,
            placeholder = { Text("Search extensions or source names...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ExtensionFilterTab.entries) { tab ->
                FilterChip(
                    selected = state.extensionSubTab == tab,
                    onClick = { onSelectTab(tab) },
                    label = { Text(tab.label) },
                )
            }
            items(dynamicLangs) { lang ->
                FilterChip(
                    selected = state.langFilter == lang,
                    onClick = { onSelectLang(lang) },
                    label = { Text(lang.uppercase()) },
                )
            }
        }

        if (state.loadErrors.isNotEmpty()) {
            MatteCard(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Extension Load Warnings", style = MaterialTheme.typography.titleSmall)
                    state.loadErrors.forEach { (name, err) ->
                        Text("$name: $err", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (list.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.Rounded.Extension,
                title = "No extensions in ${state.extensionSubTab.label}",
                message = "Add an index.pb / repo.json / index.min.json repository in the Repos tab to extract compatible extensions.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.take(200), key = { it.pkgName }) { ext ->
                    MatteCard(
                        onClick = if (ext.installState == ExtensionInstallState.INSTALLED) {
                            { onInspectExtension(ext) }
                        } else null,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!ext.iconUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ext.iconUrl,
                                        contentDescription = ext.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small),
                                    )
                                }
                                Column {
                                    Text(ext.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${ext.lang.uppercase()} • v${ext.versionName}" +
                                            (if (ext.sources.size > 1) " • ${ext.sources.size} sources" else "") +
                                            (if (ext.isNsfw) " • 18+" else "") +
                                            (if (ext.isSandboxInstalled) " • Sandbox" else ""),
                                        style = MaterialTheme.typography.labelMedium.tabular(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            when (ext.installState) {
                                ExtensionInstallState.INSTALLED -> {
                                    val primarySourceId = state.extensionPrimarySourceIds[ext.pkgName]
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (primarySourceId != null) {
                                            FilledTonalButton(
                                                onClick = { onOpenSource(primarySourceId) },
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text("Open")
                                            }
                                        }
                                        IconButton(onClick = { onInspectExtension(ext) }) {
                                            Icon(Icons.Rounded.Tune, contentDescription = "Extension settings & cookies")
                                        }
                                        OutlinedButton(onClick = { onUninstall(ext) }, shape = MaterialTheme.shapes.small) {
                                            Text("Remove")
                                        }
                                    }
                                }
                                ExtensionInstallState.UPDATE_AVAILABLE -> {
                                    FilledTonalButton(onClick = { onInstall(ext) }, shape = MaterialTheme.shapes.small) {
                                        Text("Update")
                                    }
                                }
                                else -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(onClick = { onInstall(ext) }, shape = MaterialTheme.shapes.small) {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Extract")
                                        }
                                        OutlinedButton(onClick = { onInstallSystem(ext) }, shape = MaterialTheme.shapes.small) {
                                            Text("OS")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReposList(
    repos: List<ExtensionRepo>,
    onToggle: (Long, Boolean) -> Unit,
    onToggleTrusted: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onAddClick: () -> Unit,
) {
    if (repos.isEmpty()) {
        ExpressiveEmptyState(
            icon = Icons.Rounded.Storage,
            title = "No extension repositories",
            message = "Tubiac does not bundle sources. Add an index.min.json extension repository URL to get started.",
            actionLabel = "Add Repository",
            onAction = onAddClick,
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(repos, key = { it.id }) { repo ->
                MatteCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(repo.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = repo.indexUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${repo.extensionCount} extensions",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                FilterChip(
                                    selected = repo.trusted,
                                    onClick = { onToggleTrusted(repo.id, !repo.trusted) },
                                    label = { Text(if (repo.trusted) "Trusted" else "Trust Repo") },
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                        Switch(checked = repo.enabled, onCheckedChange = { onToggle(repo.id, it) })
                        IconButton(onClick = { onDelete(repo.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove repository")
                        }
                    }
                }
            }
        }
    }
}

data class SourceSearchResult(
    val sourceId: Long,
    val sourceName: String,
    val manga: SManga,
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val aniListClient: AniListGraphQlClient,
    private val sourceManager: SourceManager,
    private val seriesDao: SeriesDao,
) : ViewModel() {
    val query = MutableStateFlow("")
    val anilistResults = MutableStateFlow<List<AniListMedia>>(emptyList())
    val sourceResults = MutableStateFlow<List<SourceSearchResult>>(emptyList())
    val loading = MutableStateFlow(false)

    fun updateQuery(value: String) {
        query.value = value
    }

    fun executeSearch() {
        val q = query.value.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            loading.value = true
            coroutineScope {
                val aniListDeferred = async(Dispatchers.IO) {
                    aniListClient.searchManga(q).getOrDefault(emptyList())
                }
                val sourcesDeferred = sourceManager.getCatalogueSources().map { src ->
                    async(Dispatchers.IO) {
                        runCatching { src.getSearchManga(1, q, FilterList()).mangas }
                            .getOrDefault(emptyList())
                            .map { SourceSearchResult(src.id, src.name, it) }
                    }
                }
                anilistResults.value = aniListDeferred.await()
                sourceResults.value = sourcesDeferred.awaitAll().flatten()
            }
            loading.value = false
        }
    }

    fun openOrCreateSeries(media: AniListMedia, onCreated: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = seriesDao.getByAniListId(media.id)
            val id = existing?.id ?: seriesDao.upsert(
                SeriesEntity(
                    sourceId = -1L,
                    url = "anilist://${media.id}",
                    title = media.preferredTitle,
                    description = media.description,
                    coverUrl = media.coverLarge,
                    bannerUrl = media.bannerImage,
                    genres = media.genres,
                    format = media.format,
                    publicationStatus = media.status,
                    inLibrary = false,
                    anilistId = media.id,
                    anilistScore = media.averageScore,
                ),
            )
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }

    fun openOrCreateSourceSeries(item: SourceSearchResult, onCreated: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = seriesDao.getBySourceAndUrl(item.sourceId, item.manga.url)
            val id = existing?.id ?: seriesDao.upsert(
                SeriesEntity(
                    sourceId = item.sourceId,
                    url = item.manga.url,
                    title = item.manga.title,
                    author = item.manga.author,
                    artist = item.manga.artist,
                    description = item.manga.description,
                    coverUrl = item.manga.thumbnail_url,
                    genres = item.manga.getGenres().orEmpty(),
                    publicationStatus = when (item.manga.status) {
                        SManga.ONGOING -> PublicationStatus.ONGOING
                        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> PublicationStatus.COMPLETED
                        SManga.ON_HIATUS -> PublicationStatus.HIATUS
                        SManga.CANCELLED -> PublicationStatus.CANCELLED
                        else -> PublicationStatus.UNKNOWN
                    },
                    inLibrary = false,
                ),
            )
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenSeriesDetail: (Long) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val aniResults by viewModel.anilistResults.collectAsStateWithLifecycle()
    val srcResults by viewModel.sourceResults.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "FEDERATED SEARCH",
                    title = "Global Search",
                    onBack = onBack,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::updateQuery,
                        placeholder = { Text("Search installed sources & AniList...") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.executeSearch() }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = viewModel::executeSearch, shape = MaterialTheme.shapes.small) {
                        Text("Search")
                    }
                }
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (srcResults.isNotEmpty()) {
                        item {
                            Text(
                                text = "INSTALLED SOURCES (${srcResults.size})",
                                style = MaterialTheme.typography.labelSmall.kicker,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(srcResults, key = { "${it.sourceId}_${it.manga.url}" }) { item ->
                            MatteCard(
                                onClick = { viewModel.openOrCreateSourceSeries(item, onOpenSeriesDetail) },
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(item.manga.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = item.sourceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (aniResults.isNotEmpty()) {
                        item {
                            Text(
                                text = "ANILIST (${aniResults.size})",
                                style = MaterialTheme.typography.labelSmall.kicker,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(aniResults, key = { it.id }) { media ->
                            MatteCard(
                                onClick = { viewModel.openOrCreateSeries(media, onOpenSeriesDetail) },
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(media.preferredTitle, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${media.format.name} • ${media.genres.take(3).joinToString(", ")}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class SourceMigrationViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val sourceManager: SourceManager,
) : ViewModel() {

    val candidates = MutableStateFlow<List<MigrationCandidate>>(emptyList())
    val migratedMessage = MutableStateFlow<String?>(null)

    fun loadCandidatesFor(seriesId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val series = seriesDao.getById(seriesId)?.toDomain() ?: return@launch
            val sources = sourceManager.getCatalogueSources().filter { it.id != series.sourceId }
            val list = sources.map { source ->
                MigrationCandidate(
                    targetSourceId = source.id,
                    targetSourceName = source.name,
                    targetSeriesUrl = series.url,
                    targetTitle = series.displayTitle,
                    coverUrl = series.displayCoverUrl,
                )
            }
            candidates.value = list
        }
    }

    fun migrateSeries(seriesId: Long, candidate: MigrationCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = seriesDao.getById(seriesId) ?: return@launch
            seriesDao.update(
                existing.copy(
                    sourceId = candidate.targetSourceId,
                    url = candidate.targetSeriesUrl,
                    lastUpdated = System.currentTimeMillis(),
                ),
            )
            migratedMessage.value = "Migrated to ${candidate.targetSourceName} preserving history, tags & AniList link."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceMigrationScreen(
    seriesId: Long,
    onBack: () -> Unit,
    viewModel: SourceMigrationViewModel = hiltViewModel(),
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val message by viewModel.migratedMessage.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(seriesId) {
        viewModel.loadCandidatesFor(seriesId)
    }

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "LIBRARY",
                    title = "Migrate Source",
                    onBack = onBack,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Transfer reading progress, categories, tags, and AniList binding to another installed source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                }
                if (candidates.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.SwapHoriz,
                        title = "No alternate sources installed",
                        message = "Install another extension or configure Local Source to migrate this series.",
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(candidates, key = { it.targetSourceId }) { candidate ->
                            MatteCard(
                                onClick = { viewModel.migrateSeries(seriesId, candidate) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(candidate.targetSourceName, style = MaterialTheme.typography.titleSmall)
                                        Text(candidate.targetTitle, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    FilledTonalButton(
                                        onClick = { viewModel.migrateSeries(seriesId, candidate) },
                                        shape = MaterialTheme.shapes.small,
                                    ) { Text("Migrate") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
