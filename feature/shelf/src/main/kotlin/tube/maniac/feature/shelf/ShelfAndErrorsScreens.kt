package tube.maniac.feature.shelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.CategoryDao
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.dao.TagDao
import tube.maniac.core.database.dao.UpdateErrorDao
import tube.maniac.core.database.entity.CategoryEntity
import tube.maniac.core.database.entity.SeriesCategoryCrossRef
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.designsystem.components.ErrorRecoveryCard
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.MatteSegmentedTabs
import tube.maniac.core.designsystem.components.SeriesCoverCard
import tube.maniac.core.designsystem.components.TabularBadge
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.model.Category
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.Series
import tube.maniac.core.model.ShelfDisplayMode
import tube.maniac.core.model.Tag
import tube.maniac.core.model.UpdateError
import javax.inject.Inject

enum class ShelfGroupingMode { CATEGORIES, ANILIST_STATUS }
enum class ShelfSyncFilter { ALL, LOCAL_ONLY, ANILIST_SYNCED }

data class ShelfUiState(
    val series: List<Series> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val updateErrorsCount: Int = 0,
    val displayMode: ShelfDisplayMode = ShelfDisplayMode.GRID,
    val showUnreadBadges: Boolean = true,
    val showDownloadBadges: Boolean = true,
    val groupingMode: ShelfGroupingMode = ShelfGroupingMode.CATEGORIES,
    val selectedCategoryId: Long? = null,
    val selectedStatus: ReadingStatus = ReadingStatus.CURRENT,
    val selectedTagId: Long? = null,
    val syncFilter: ShelfSyncFilter = ShelfSyncFilter.ALL,
    val query: String = "",
    val selectedSeriesIds: Set<Long> = emptySet(),
)

private data class ShelfFilters(
    val groupingMode: ShelfGroupingMode = ShelfGroupingMode.CATEGORIES,
    val selectedCategoryId: Long? = null,
    val selectedStatus: ReadingStatus = ReadingStatus.CURRENT,
    val selectedTagId: Long? = null,
    val syncFilter: ShelfSyncFilter = ShelfSyncFilter.ALL,
    val query: String = "",
    val selectedIds: Set<Long> = emptySet(),
)

@HiltViewModel
class ShelfViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val updateErrorDao: UpdateErrorDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val filtersFlow = MutableStateFlow(ShelfFilters())

    val uiState: StateFlow<ShelfUiState> = combine(
        combine(
            seriesDao.observeLibrary(),
            categoryDao.observeAll(),
            categoryDao.observeAllMappings(),
            tagDao.observeAll(),
            tagDao.observeAllMappings(),
        ) { series, categories, catMaps, tags, tagMaps ->
            Triple(
                series.map { it.toDomain() },
                categories.map { it.toDomain() } to catMaps,
                tags.map { it.toDomain() } to tagMaps,
            )
        },
        updateErrorDao.observeAll(),
        settingsRepository.settingsFlow,
        filtersFlow,
    ) { data, errors, settings, filter ->
        val (allSeries, catPair, tagPair) = data
        val (categories, catMaps) = catPair
        val (tags, tagMaps) = tagPair

        val seriesInCategory = filter.selectedCategoryId?.let { catId ->
            catMaps.filter { it.categoryId == catId }.map { it.seriesId }.toSet()
        }
        val seriesWithTag = filter.selectedTagId?.let { tagId ->
            tagMaps.filter { it.tagId == tagId }.map { it.seriesId }.toSet()
        }

        val visible = allSeries.filter { item ->
            val matchesQuery = filter.query.isBlank() ||
                item.displayTitle.contains(filter.query, ignoreCase = true)
            val matchesSync = when (filter.syncFilter) {
                ShelfSyncFilter.ALL -> true
                ShelfSyncFilter.LOCAL_ONLY -> item.localOnly || item.anilistId == null
                ShelfSyncFilter.ANILIST_SYNCED -> !item.localOnly && item.anilistId != null
            }
            val matchesGroup = when (filter.groupingMode) {
                ShelfGroupingMode.CATEGORIES -> seriesInCategory == null || item.id in seriesInCategory
                ShelfGroupingMode.ANILIST_STATUS -> item.readingStatus == filter.selectedStatus
            }
            val matchesTag = seriesWithTag == null || item.id in seriesWithTag
            matchesQuery && matchesSync && matchesGroup && matchesTag
        }

        ShelfUiState(
            series = visible,
            categories = categories.filterNot { it.isHidden },
            tags = tags,
            updateErrorsCount = errors.size,
            displayMode = settings.shelfDisplayMode,
            showUnreadBadges = settings.showUnreadBadges,
            showDownloadBadges = settings.showDownloadBadges,
            groupingMode = filter.groupingMode,
            selectedCategoryId = filter.selectedCategoryId,
            selectedStatus = filter.selectedStatus,
            selectedTagId = filter.selectedTagId,
            syncFilter = filter.syncFilter,
            query = filter.query,
            selectedSeriesIds = filter.selectedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfUiState())

    fun setGroupingMode(mode: ShelfGroupingMode) {
        filtersFlow.value = filtersFlow.value.copy(groupingMode = mode)
    }

    fun selectCategory(categoryId: Long?) {
        filtersFlow.value = filtersFlow.value.copy(selectedCategoryId = categoryId)
    }

    fun selectStatus(status: ReadingStatus) {
        filtersFlow.value = filtersFlow.value.copy(selectedStatus = status)
    }

    fun selectTag(tagId: Long?) {
        filtersFlow.value = filtersFlow.value.copy(selectedTagId = tagId)
    }

    fun setSyncFilter(syncFilter: ShelfSyncFilter) {
        filtersFlow.value = filtersFlow.value.copy(syncFilter = syncFilter)
    }

    fun updateQuery(query: String) {
        filtersFlow.value = filtersFlow.value.copy(query = query)
    }

    fun toggleSelection(seriesId: Long) {
        val current = filtersFlow.value.selectedIds
        val next = if (seriesId in current) current - seriesId else current + seriesId
        filtersFlow.value = filtersFlow.value.copy(selectedIds = next)
    }

    fun clearSelection() {
        filtersFlow.value = filtersFlow.value.copy(selectedIds = emptySet())
    }

    fun cycleDisplayMode() {
        viewModelScope.launch {
            settingsRepository.updateSettings { s ->
                val next = when (s.shelfDisplayMode) {
                    ShelfDisplayMode.GRID -> ShelfDisplayMode.LIST
                    ShelfDisplayMode.LIST -> ShelfDisplayMode.COMPACT
                    ShelfDisplayMode.COMPACT -> ShelfDisplayMode.GRID
                }
                s.copy(shelfDisplayMode = next)
            }
        }
    }

    fun createCategory(name: String, hidden: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val count = categoryDao.getAll().size
            categoryDao.upsert(
                CategoryEntity(
                    name = name.trim(),
                    sortOrder = count,
                    isHidden = hidden,
                ),
            )
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch { categoryDao.deleteById(categoryId) }
    }

    fun assignSelectedToCategory(categoryId: Long) {
        val ids = filtersFlow.value.selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { seriesId ->
                categoryDao.assignSeriesToCategory(SeriesCategoryCrossRef(seriesId, categoryId))
            }
            clearSelection()
        }
    }

    fun markSelectedRead(read: Boolean) {
        val ids = filtersFlow.value.selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { seriesId ->
                val chIds = chapterDao.getBySeries(seriesId).map { it.id }
                if (chIds.isNotEmpty()) chapterDao.setReadBatch(chIds, read)
            }
            clearSelection()
        }
    }

    fun removeSelectedFromLibrary() {
        val ids = filtersFlow.value.selectedIds.toList()
        viewModelScope.launch {
            ids.forEach { seriesDao.setInLibrary(it, inLibrary = false, dateAdded = 0L) }
            clearSelection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenSeriesDetail: (Long) -> Unit,
    onOpenUpdateErrors: () -> Unit,
    onOpenBrowse: () -> Unit,
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    val selectionActive = state.selectedSeriesIds.isNotEmpty()

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (selectionActive) {
                    TubiacTopBar(
                        kicker = "BATCH EDIT",
                        title = "${state.selectedSeriesIds.size} Selected",
                        actions = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                            }
                            IconButton(onClick = { showCategorySheet = true }) {
                                Icon(Icons.Rounded.Category, contentDescription = "Assign category")
                            }
                            IconButton(onClick = { viewModel.markSelectedRead(true) }) {
                                Icon(Icons.Rounded.DoneAll, contentDescription = "Mark read")
                            }
                            IconButton(onClick = viewModel::removeSelectedFromLibrary) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove from Shelf")
                            }
                        },
                    )
                } else {
                    TubiacTopBar(
                        kicker = "COLLECTION",
                        title = "Shelf",
                        actions = {
                            if (state.updateErrorsCount > 0) {
                                IconButton(onClick = onOpenUpdateErrors) {
                                    Icon(
                                        Icons.Rounded.Warning,
                                        contentDescription = "Update errors",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            IconButton(onClick = { showCategorySheet = true }) {
                                Icon(Icons.Rounded.Category, contentDescription = "Manage categories")
                            }
                            IconButton(onClick = viewModel::cycleDisplayMode) {
                                val icon = when (state.displayMode) {
                                    ShelfDisplayMode.GRID -> Icons.Rounded.GridView
                                    ShelfDisplayMode.LIST -> Icons.AutoMirrored.Rounded.ViewList
                                    ShelfDisplayMode.COMPACT -> Icons.Rounded.ViewAgenda
                                }
                                Icon(icon, contentDescription = "Change layout")
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                MatteSegmentedTabs(
                    options = listOf("Categories", "AniList Lists"),
                    selectedIndex = if (state.groupingMode == ShelfGroupingMode.CATEGORIES) 0 else 1,
                    onSelect = { idx ->
                        viewModel.setGroupingMode(
                            if (idx == 0) ShelfGroupingMode.CATEGORIES else ShelfGroupingMode.ANILIST_STATUS,
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // Tab Strip
                if (state.groupingMode == ShelfGroupingMode.CATEGORIES) {
                    val tabs = listOf<Category?>(null) + state.categories
                    val selectedIdx = tabs.indexOfFirst { it?.id == state.selectedCategoryId }.coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedIdx,
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                    ) {
                        tabs.forEach { cat ->
                            Tab(
                                selected = cat?.id == state.selectedCategoryId,
                                onClick = { viewModel.selectCategory(cat?.id) },
                                text = { Text(cat?.name ?: "All") },
                            )
                        }
                    }
                } else {
                    val statuses = ReadingStatus.entries
                    val selectedIdx = statuses.indexOf(state.selectedStatus).coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedIdx,
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                    ) {
                        statuses.forEach { status ->
                            Tab(
                                selected = status == state.selectedStatus,
                                onClick = { viewModel.selectStatus(status) },
                                text = {
                                    Text(
                                        status.name.lowercase().replaceFirstChar { it.uppercase() }
                                            .replace("Current", "Reading"),
                                    )
                                },
                            )
                        }
                    }
                }

                // Search + Local/AniList/Tag chips
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    placeholder = { Text("Filter Shelf") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ShelfSyncFilter.entries) { filter ->
                        FilterChip(
                            selected = state.syncFilter == filter,
                            onClick = { viewModel.setSyncFilter(filter) },
                            label = {
                                Text(
                                    when (filter) {
                                        ShelfSyncFilter.ALL -> "All"
                                        ShelfSyncFilter.LOCAL_ONLY -> "Local-Only"
                                        ShelfSyncFilter.ANILIST_SYNCED -> "AniList Synced"
                                    },
                                )
                            },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                    items(state.tags, key = { it.id }) { tag ->
                        FilterChip(
                            selected = state.selectedTagId == tag.id,
                            onClick = {
                                viewModel.selectTag(if (state.selectedTagId == tag.id) null else tag.id)
                            },
                            label = { Text("#${tag.name}") },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }

                if (state.series.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.LibraryBooks,
                        title = "Your Shelf is empty",
                        message = "Add manga, manhwa, or local CBZ folders from Browse or import your AniList library.",
                        actionLabel = "Browse Sources",
                        onAction = onOpenBrowse,
                    )
                } else {
                    when (state.displayMode) {
                        ShelfDisplayMode.GRID -> {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 114.dp),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(state.series, key = { it.id }) { series ->
                                    SeriesCoverCard(
                                        title = series.displayTitle,
                                        coverUrl = series.displayCoverUrl,
                                        unreadCount = series.unreadCount,
                                        downloadedCount = series.downloadedCount,
                                        showUnreadBadge = state.showUnreadBadges,
                                        showDownloadBadge = state.showDownloadBadges,
                                        selected = series.id in state.selectedSeriesIds,
                                        onClick = {
                                            if (selectionActive) viewModel.toggleSelection(series.id)
                                            else onOpenSeriesDetail(series.id)
                                        },
                                        onLongClick = { viewModel.toggleSelection(series.id) },
                                    )
                                }
                            }
                        }

                        ShelfDisplayMode.LIST, ShelfDisplayMode.COMPACT -> {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(state.series, key = { it.id }) { series ->
                                    MatteCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        selected = series.id in state.selectedSeriesIds,
                                        onClick = {
                                            if (selectionActive) viewModel.toggleSelection(series.id)
                                            else onOpenSeriesDetail(series.id)
                                        },
                                        onLongClick = { viewModel.toggleSelection(series.id) },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            AsyncImage(
                                                model = series.displayCoverUrl,
                                                contentDescription = series.displayTitle,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(
                                                        width = if (state.displayMode == ShelfDisplayMode.COMPACT) 40.dp else 54.dp,
                                                        height = if (state.displayMode == ShelfDisplayMode.COMPACT) 56.dp else 76.dp,
                                                    )
                                                    .clip(MaterialTheme.shapes.extraSmall)
                                                    .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                                        MaterialTheme.shapes.extraSmall,
                                                    ),
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = series.displayTitle,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = series.author ?: series.format.name.lowercase()
                                                        .replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                            if (state.showUnreadBadges && series.unreadCount > 0) {
                                                TabularBadge(
                                                    text = series.unreadCount.toString(),
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary,
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
        }
    }

    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectionActive) "Assign to Category" else "Manage Categories",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = { showAddCategoryDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
                state.categories.forEach { cat ->
                    MatteCard(
                        onClick = {
                            if (selectionActive) {
                                viewModel.assignSelectedToCategory(cat.id)
                                showCategorySheet = false
                            } else {
                                viewModel.selectCategory(cat.id)
                                showCategorySheet = false
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cat.isHidden) {
                                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(cat.name, style = MaterialTheme.typography.titleSmall)
                            }
                            if (!selectionActive) {
                                IconButton(onClick = { viewModel.deleteCategory(cat.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete category")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }

    if (showAddCategoryDialog) {
        var name by remember { mutableStateOf("") }
        var hidden by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("Create Category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Category name") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Hidden (Biometric/PIN protected)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = hidden, onCheckedChange = { hidden = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createCategory(name, hidden)
                    showAddCategoryDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@HiltViewModel
class UpdateErrorsViewModel @Inject constructor(
    private val updateErrorDao: UpdateErrorDao,
) : ViewModel() {
    val errors: StateFlow<List<UpdateError>> = updateErrorDao.observeAll()
        .combine(MutableStateFlow(Unit)) { list, _ -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissError(seriesId: Long) {
        viewModelScope.launch { updateErrorDao.deleteBySeries(seriesId) }
    }

    fun clearAll() {
        viewModelScope.launch { updateErrorDao.clearAll() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateErrorsScreen(
    onBack: () -> Unit,
    onOpenWebView: (String) -> Unit,
    onMigrateSource: (Long) -> Unit,
    viewModel: UpdateErrorsViewModel = hiltViewModel(),
) {
    val errors by viewModel.errors.collectAsStateWithLifecycle()

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "DIAGNOSTICS",
                    title = "Update Errors",
                    onBack = onBack,
                    actions = {
                        if (errors.isNotEmpty()) {
                            TextButton(onClick = viewModel::clearAll) { Text("Clear all") }
                        }
                    },
                )
            },
        ) { padding ->
            if (errors.isEmpty()) {
                ExpressiveEmptyState(
                    icon = Icons.Rounded.DoneAll,
                    title = "All sources healthy",
                    message = "No failed background updates right now.",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(errors, key = { it.id }) { error ->
                        ErrorRecoveryCard(
                            title = "${error.seriesTitle} • ${error.sourceName}",
                            reason = if (error.isCloudflare) {
                                "Cloudflare challenge paused updates. Open in WebView to clear."
                            } else {
                                error.reason
                            },
                            onRetry = { viewModel.dismissError(error.seriesId) },
                            onOpenWebView = { onOpenWebView(error.seriesUrl) },
                            onMigrateSource = { onMigrateSource(error.seriesId) },
                        )
                    }
                }
            }
        }
    }
}
