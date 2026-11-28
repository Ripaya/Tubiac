package tube.maniac.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.SeriesEntity
import tube.maniac.core.designsystem.components.ErrorRecoveryCard
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteSegmentedTabs
import tube.maniac.core.designsystem.components.SeriesCoverCard
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.unwrapRoot
import tube.maniac.core.extension.loader.LocalSource
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.network.CloudflareChallengeException
import tube.maniac.core.network.IpBlockedException
import javax.inject.Inject

enum class SourceFeedTab(val label: String) {
    POPULAR("Popular"),
    LATEST("Latest"),
    SEARCH("Search"),
}

data class SourceBrowseUiState(
    val sourceId: Long = 0L,
    val sourceName: String = "Source",
    val supportsLatest: Boolean = false,
    val baseUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val filters: FilterList = FilterList(),
    val filterRevision: Int = 0,
    val activeTab: SourceFeedTab = SourceFeedTab.POPULAR,
    val query: String = "",
    val items: List<SManga> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val blockedUrl: String? = null,
)

@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val seriesDao: SeriesDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceBrowseUiState())
    val uiState: StateFlow<SourceBrowseUiState> = _uiState.asStateFlow()

    fun initSource(sourceId: Long) {
        if (_uiState.value.sourceId == sourceId && _uiState.value.items.isNotEmpty()) return
        val source = sourceManager.getSource(sourceId)
        val catalogue = source as? CatalogueSource
        val httpSource = source as? HttpSource
        val headerMap = buildMap<String, String> {
            httpSource?.let { hs ->
                runCatching { hs.headers }.getOrNull()?.let { h ->
                    for (i in 0 until h.size) put(h.name(i), h.value(i))
                }
            }
        }
        val initialFilters = runCatching { catalogue?.getFilterList() ?: FilterList() }.getOrDefault(FilterList())
        _uiState.value = SourceBrowseUiState(
            sourceId = sourceId,
            sourceName = source?.name ?: "Unknown Source",
            supportsLatest = catalogue?.supportsLatest == true,
            baseUrl = httpSource?.baseUrl,
            sourceHeaders = headerMap,
            filters = initialFilters,
        )
        loadPage(reset = true)
    }

    fun notifyFilterMutated() {
        _uiState.value = _uiState.value.copy(filterRevision = _uiState.value.filterRevision + 1)
    }

    fun resetFilters() {
        val catalogue = sourceManager.getSource(_uiState.value.sourceId) as? CatalogueSource
        val fresh = runCatching { catalogue?.getFilterList() ?: FilterList() }.getOrDefault(FilterList())
        _uiState.value = _uiState.value.copy(filters = fresh, filterRevision = _uiState.value.filterRevision + 1)
    }

    fun selectTab(tab: SourceFeedTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab, errorMessage = null, blockedUrl = null)
        if (tab != SourceFeedTab.SEARCH || _uiState.value.query.isNotBlank()) {
            loadPage(reset = true)
        }
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun executeSearch() {
        _uiState.value = _uiState.value.copy(activeTab = SourceFeedTab.SEARCH)
        loadPage(reset = true)
    }

    fun loadPage(reset: Boolean) {
        val current = _uiState.value
        val source = sourceManager.getSource(current.sourceId) as? CatalogueSource ?: run {
            _uiState.value = current.copy(errorMessage = "This source does not support catalog browsing.")
            return
        }
        val targetPage = if (reset) 1 else current.page + 1

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                blockedUrl = null,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    when (current.activeTab) {
                        SourceFeedTab.POPULAR -> source.getPopularManga(targetPage)
                        SourceFeedTab.LATEST -> source.getLatestUpdates(targetPage)
                        SourceFeedTab.SEARCH -> source.getSearchManga(targetPage, current.query.trim(), current.filters)
                    }
                }
            }.onSuccess { result ->
                val merged = if (reset) result.mangas else (current.items + result.mangas).distinctBy { it.url }
                _uiState.value = _uiState.value.copy(
                    items = merged,
                    page = targetPage,
                    hasNextPage = result.hasNextPage,
                    isLoading = false,
                )
            }.onFailure { err ->
                val root = err.unwrapRoot()
                val url = when (root) {
                    is CloudflareChallengeException -> root.challengeUrl
                    is IpBlockedException -> root.blockedUrl
                    else -> {
                        if (root !is kotlinx.coroutines.CancellationException) {
                            AutoIssueReporter.reportRuntimeBug(
                                category = "source-browse",
                                title = "[Source] ${source.name} failed ${current.activeTab.name.lowercase()}",
                                detail = "sourceId=${source.id}, tab=${current.activeTab}, page=$targetPage",
                                throwable = root,
                            )
                        }
                        current.baseUrl
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = root.message ?: "Failed to load catalog from ${source.name}",
                    blockedUrl = url,
                )
            }
        }
    }

    fun openManga(manga: SManga, onOpened: (Long) -> Unit) {
        val sourceId = _uiState.value.sourceId
        viewModelScope.launch(Dispatchers.IO) {
            val existing = seriesDao.getBySourceAndUrl(sourceId, manga.url)
            val id = existing?.id ?: seriesDao.upsert(
                SeriesEntity(
                    sourceId = sourceId,
                    url = manga.url,
                    title = manga.title,
                    author = manga.author,
                    artist = manga.artist,
                    description = manga.description,
                    coverUrl = manga.thumbnail_url,
                    genres = manga.getGenres().orEmpty(),
                    publicationStatus = manga.status.toPublicationStatus(),
                    inLibrary = false,
                ),
            )
            withContext(Dispatchers.Main) { onOpened(id) }
        }
    }
}

private fun Int.toPublicationStatus(): PublicationStatus = when (this) {
    SManga.ONGOING -> PublicationStatus.ONGOING
    SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> PublicationStatus.COMPLETED
    SManga.ON_HIATUS -> PublicationStatus.HIATUS
    SManga.CANCELLED -> PublicationStatus.CANCELLED
    else -> PublicationStatus.UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: Long,
    onBack: () -> Unit,
    onOpenSeriesDetail: (Long) -> Unit,
    onOpenWebView: (String) -> Unit,
    viewModel: SourceBrowseViewModel = hiltViewModel(),
) {
    LaunchedEffect(sourceId) { viewModel.initSource(sourceId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilterSheet by remember { mutableStateOf(false) }
    val tabs = if (state.supportsLatest) {
        SourceFeedTab.entries
    } else {
        listOf(SourceFeedTab.POPULAR, SourceFeedTab.SEARCH)
    }

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "SOURCE FEED",
                    title = state.sourceName,
                    onBack = onBack,
                    actions = {
                        if (state.filters.isNotEmpty()) {
                            IconButton(onClick = { showFilterSheet = true }) {
                                Icon(Icons.Rounded.FilterList, contentDescription = "Source Filters")
                            }
                        }
                        state.baseUrl?.let { baseUrl ->
                            IconButton(onClick = { onOpenWebView(baseUrl) }) {
                                Icon(Icons.Rounded.Public, contentDescription = "Open source in WebView")
                            }
                        }
                        IconButton(onClick = { viewModel.loadPage(reset = true) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
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
                MatteSegmentedTabs(
                    options = tabs.map { it.label },
                    selectedIndex = tabs.indexOf(state.activeTab).coerceAtLeast(0),
                    onSelect = { index -> viewModel.selectTab(tabs[index]) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (state.activeTab == SourceFeedTab.SEARCH) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            placeholder = { Text("Search ${state.sourceName}...") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.executeSearch() }),
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = viewModel::executeSearch, shape = MaterialTheme.shapes.small) {
                            Text("Go")
                        }
                    }
                }

                state.errorMessage?.let { err ->
                    Box(modifier = Modifier.padding(16.dp)) {
                        ErrorRecoveryCard(
                            title = "Could not load ${state.sourceName}",
                            reason = err,
                            onRetry = { viewModel.loadPage(reset = true) },
                            onOpenWebView = {
                                val target = state.blockedUrl ?: state.baseUrl ?: "https://anilist.co"
                                onOpenWebView(target)
                            },
                            onMigrateSource = onBack,
                        )
                    }
                }

                when {
                    state.isLoading && state.items.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    state.items.isEmpty() && state.errorMessage == null -> {
                        val emptyMsg = if (state.sourceId == LocalSource.LOCAL_SOURCE_ID) {
                            "No local series found. Select a Local Folder in Settings containing one subfolder per series (with CBZ/ZIP/EPUB files or image folders)."
                        } else {
                            "No titles returned for ${state.activeTab.label}."
                        }
                        ExpressiveEmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Nothing here yet",
                            message = emptyMsg,
                            actionLabel = "Retry",
                            onAction = { viewModel.loadPage(reset = true) },
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 114.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.items, key = { it.url }) { manga ->
                                SeriesCoverCard(
                                    title = manga.title,
                                    coverUrl = manga.thumbnail_url,
                                    coverHeaders = state.sourceHeaders,
                                    onClick = { viewModel.openManga(manga, onOpenSeriesDetail) },
                                )
                            }
                            if (state.hasNextPage) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.loadPage(reset = false) },
                                            enabled = !state.isLoading,
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            if (state.isLoading) {
                                                CircularProgressIndicator(modifier = Modifier.width(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(if (state.isLoading) "Loading..." else "Load More")
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

    if (showFilterSheet && state.filters.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            val rev = state.filterRevision
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Source Filters", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.resetFilters() }) { Text("Reset") }
                        FilledTonalButton(
                            onClick = {
                                showFilterSheet = false
                                viewModel.executeSearch()
                            },
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Apply") }
                    }
                }
                HorizontalDivider()
                @Suppress("UNUSED_VARIABLE")
                val unusedRev = rev
                state.filters.forEach { filter ->
                    SourceFilterControl(filter = filter, onMutated = viewModel::notifyFilterMutated)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceFilterControl(
    filter: Filter<*>,
    onMutated: () -> Unit,
) {
    when (filter) {
        is Filter.Header -> Text(filter.name, style = MaterialTheme.typography.titleSmall)
        is Filter.Separator -> HorizontalDivider()
        is Filter.Text -> {
            var draft by remember(filter, filter.state) { mutableStateOf(filter.state) }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    filter.state = it
                    onMutated()
                },
                label = { Text(filter.name) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is Filter.CheckBox -> {
            var checked by remember(filter, filter.state) { mutableStateOf(filter.state) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        filter.state = it
                        onMutated()
                    },
                )
            }
        }
        is Filter.TriState -> {
            var tri by remember(filter, filter.state) { mutableIntStateOf(filter.state) }
            val toggleState = when (tri) {
                Filter.TriState.STATE_INCLUDE -> ToggleableState.On
                Filter.TriState.STATE_EXCLUDE -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
                TriStateCheckbox(
                    state = toggleState,
                    onClick = {
                        tri = when (tri) {
                            Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
                            Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
                            else -> Filter.TriState.STATE_IGNORE
                        }
                        filter.state = tri
                        onMutated()
                    },
                )
            }
        }
        is Filter.Select<*> -> {
            var selectedIdx by remember(filter, filter.state) { mutableIntStateOf(filter.state) }
            Text(filter.name, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                filter.values.forEachIndexed { idx, value ->
                    FilterChip(
                        selected = selectedIdx == idx,
                        onClick = {
                            selectedIdx = idx
                            filter.state = idx
                            onMutated()
                        },
                        label = { Text(value?.toString().orEmpty()) },
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
        }
        is Filter.Sort -> {
            var selection by remember(filter, filter.state) { mutableStateOf(filter.state) }
            Text(filter.name, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                filter.values.forEachIndexed { idx, value ->
                    val isSelected = selection?.index == idx
                    val arrow = if (isSelected) (if (selection?.ascending == true) " ↑" else " ↓") else ""
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            val next = if (isSelected) {
                                Filter.Sort.Selection(idx, ascending = !(selection?.ascending ?: true))
                            } else {
                                Filter.Sort.Selection(idx, ascending = false)
                            }
                            selection = next
                            filter.state = next
                            onMutated()
                        },
                        label = { Text("$value$arrow") },
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
        }
        is Filter.Group<*> -> {
            Text(filter.name, style = MaterialTheme.typography.titleSmall)
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                filter.state.forEach { nested ->
                    if (nested is Filter<*>) {
                        SourceFilterControl(filter = nested, onMutated = onMutated)
                    }
                }
            }
        }
    }
}
