package tube.maniac.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.extension.loader.ExtensionRepoManager
import tube.maniac.core.extension.loader.LocalSource
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.AniListMedia
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.model.ExtensionRepo
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

data class BrowseUiState(
    val activeTab: BrowseSubTab = BrowseSubTab.SOURCES,
    val sources: List<Source> = emptyList(),
    val installedExtensions: List<ExtensionInfo> = emptyList(),
    val loadErrors: Map<String, String> = emptyMap(),
    val remoteExtensions: List<ExtensionInfo> = emptyList(),
    val repos: List<ExtensionRepo> = emptyList(),
    val showNsfw: Boolean = false,
    val extensionSubTab: ExtensionFilterTab = ExtensionFilterTab.INSTALLED,
    val langFilter: String = "all",
    val statusMessage: String? = null,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repoManager: ExtensionRepoManager,
    private val repoDao: ExtensionRepoDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val activeTabFlow = MutableStateFlow(BrowseSubTab.SOURCES)
    private val extSubTabFlow = MutableStateFlow(ExtensionFilterTab.INSTALLED)
    private val langFilterFlow = MutableStateFlow("all")
    private val remoteExtensionsFlow = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    private val statusMessageFlow = MutableStateFlow<String?>(null)

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
                loadErrors = installed.mapNotNull { ext ->
                    ext.loadError?.let { ext.info.name to it }
                }.toMap(),
                remoteExtensions = remote,
                repos = repos.map { it.toDomain() },
            )
        },
        settingsRepository.settingsFlow,
        extSubTabFlow,
        langFilterFlow,
        statusMessageFlow,
    ) { base, settings, extTab, lang, message ->
        base.copy(
            showNsfw = settings.showNsfwExtensions,
            extensionSubTab = extTab,
            langFilter = lang,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    init {
        sourceManager.reloadExtensions()
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

    fun refreshAll() {
        sourceManager.reloadExtensions()
        refreshRepos()
    }

    fun refreshRepos() {
        viewModelScope.launch {
            statusMessageFlow.value = "Refreshing repository indices..."
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
            statusMessageFlow.value = "Downloading ${ext.name}..."
            repoManager.installExtensionApk(ext)
                .onSuccess { statusMessageFlow.value = "Confirm installation prompt for ${ext.name}..." }
                .onFailure { statusMessageFlow.value = it.message ?: "Install failed" }
        }
    }

    fun uninstallExtension(pkgName: String) {
        repoManager.requestUninstall(pkgName)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
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
                    shape = CircleShape,
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
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                BrowseSubTab.entries.forEachIndexed { idx, tab ->
                    SegmentedButton(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(idx, BrowseSubTab.entries.size),
                    ) { Text(tab.label) }
                }
            }

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
                )

                BrowseSubTab.EXTENSIONS -> ExtensionsList(
                    state = state,
                    onSelectTab = viewModel::selectExtensionTab,
                    onSelectLang = viewModel::selectLang,
                    onInstall = viewModel::installExtension,
                    onUninstall = viewModel::uninstallExtension,
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

    if (showAddRepoDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var trusted by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddRepoDialog = false },
            title = { Text("Add Extension Repo Store") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter a Tachiyomi/Mihon compatible index.min.json repository URL.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("index.min.json URL") },
                        placeholder = { Text("https://.../index.min.json") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Trust third-party APK signatures", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = trusted, onCheckedChange = { trusted = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addRepo(name, url, trusted)
                        showAddRepoDialog = false
                    },
                    enabled = url.trim().startsWith("https://") || url.trim().startsWith("http://"),
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
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(
                onClick = onGlobalSearch,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Global Search Across Sources & AniList")
            }
        }
        items(sources, key = { it.id }) { source ->
            Card(
                onClick = { onOpenSource(source.id) },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
    onSelectTab: (ExtensionFilterTab) -> Unit,
    onSelectLang: (String) -> Unit,
    onInstall: (ExtensionInfo) -> Unit,
    onUninstall: (String) -> Unit,
) {
    val dynamicLangs = remember(state.installedExtensions, state.remoteExtensions) {
        val detected = (state.installedExtensions + state.remoteExtensions)
            .map { it.lang.lowercase() }
            .filter { it.isNotBlank() && it != "all" }
            .distinct()
        (listOf("all", "en", "tr", "ja", "ko", "zh") + detected).distinct()
    }

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
            (state.langFilter == "all" || ext.lang.equals(state.langFilter, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
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
                message = "Add a Tachiyomi/Mihon repository in the Repos tab to browse compatible extensions.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.pkgName }) { ext ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                                            .size(36.dp)
                                            .clip(MaterialTheme.shapes.small),
                                    )
                                }
                                Column {
                                    Text(ext.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${ext.lang.uppercase()} • v${ext.versionName}" +
                                            (if (ext.isNsfw) " • 18+" else "") +
                                            (if (ext.installState == ExtensionInstallState.UNTRUSTED) " • Untrusted repo" else ""),
                                        style = MaterialTheme.typography.labelMedium.tabular(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            when (ext.installState) {
                                ExtensionInstallState.INSTALLED -> {
                                    OutlinedButton(onClick = { onUninstall(ext.pkgName) }, shape = CircleShape) {
                                        Text("Uninstall")
                                    }
                                }
                                ExtensionInstallState.UPDATE_AVAILABLE -> {
                                    FilledTonalButton(onClick = { onInstall(ext) }, shape = CircleShape) {
                                        Text("Update")
                                    }
                                }
                                else -> {
                                    FilledTonalButton(onClick = { onInstall(ext) }, shape = CircleShape) {
                                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Install")
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
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
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
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = viewModel::executeSearch, shape = CircleShape) {
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
                            text = "Installed Sources (${srcResults.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(srcResults, key = { "${it.sourceId}_${it.manga.url}" }) { item ->
                        Card(
                            onClick = { viewModel.openOrCreateSourceSeries(item, onOpenSeriesDetail) },
                            shape = MaterialTheme.shapes.medium,
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
                            text = "AniList (${aniResults.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(aniResults, key = { it.id }) { media ->
                        Card(
                            onClick = { viewModel.openOrCreateSeries(media, onOpenSeriesDetail) },
                            shape = MaterialTheme.shapes.medium,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Migrate Source") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
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
                        Card(
                            onClick = { viewModel.migrateSeries(seriesId, candidate) },
                            shape = MaterialTheme.shapes.medium,
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
                                    shape = CircleShape,
                                ) { Text("Migrate") }
                            }
                        }
                    }
                }
            }
        }
    }
}
