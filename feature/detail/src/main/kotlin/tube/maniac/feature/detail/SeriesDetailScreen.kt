package tube.maniac.feature.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.DownloadEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.MetadataSource
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.Series
import tube.maniac.core.model.SmartReaderModeDetector
import javax.inject.Inject

enum class ChapterFilter { ALL, UNREAD, DOWNLOADED, BOOKMARKED }

data class SeriesDetailUiState(
    val series: Series? = null,
    val chapters: List<Chapter> = emptyList(),
    val missingGaps: Map<Long, Int> = emptyMap(),
    val filter: ChapterFilter = ChapterFilter.ALL,
    val sortDescending: Boolean = true,
    val selectedChapterIds: Set<Long> = emptySet(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val chapterDao: ChapterDao,
    private val downloadDao: DownloadDao,
) : ViewModel() {

    private val seriesIdFlow = MutableStateFlow<Long?>(null)
    private val filterFlow = MutableStateFlow(ChapterFilter.ALL)
    private val sortDescFlow = MutableStateFlow(true)
    private val selectedChaptersFlow = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<SeriesDetailUiState> = seriesIdFlow.flatMapLatest { id ->
        if (id == null) {
            flowOf(SeriesDetailUiState())
        } else {
            combine(
                seriesDao.observeById(id),
                chapterDao.observeBySeries(id),
                filterFlow,
                sortDescFlow,
                selectedChaptersFlow,
            ) { entity, chapterEntities, filter, desc, selected ->
                val allChapters = chapterEntities.map { it.toDomain() }
                val gaps = SmartReaderModeDetector.detectMissingChapterGaps(allChapters)
                val filtered = allChapters.filter { ch ->
                    when (filter) {
                        ChapterFilter.ALL -> true
                        ChapterFilter.UNREAD -> !ch.read
                        ChapterFilter.DOWNLOADED -> ch.downloaded
                        ChapterFilter.BOOKMARKED -> ch.bookmark
                    }
                }.let { list ->
                    if (desc) list.sortedByDescending { it.chapterNumber }
                    else list.sortedBy { it.chapterNumber }
                }
                SeriesDetailUiState(
                    series = entity?.toDomain(),
                    chapters = filtered,
                    missingGaps = gaps,
                    filter = filter,
                    sortDescending = desc,
                    selectedChapterIds = selected,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesDetailUiState())

    fun load(seriesId: Long) {
        seriesIdFlow.value = seriesId
    }

    fun setFilter(filter: ChapterFilter) {
        filterFlow.value = filter
    }

    fun toggleSort() {
        sortDescFlow.value = !sortDescFlow.value
    }

    fun toggleChapterSelection(chapterId: Long) {
        val current = selectedChaptersFlow.value
        selectedChaptersFlow.value = if (chapterId in current) current - chapterId else current + chapterId
    }

    fun clearChapterSelection() {
        selectedChaptersFlow.value = emptySet()
    }

    fun toggleInLibrary() {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            seriesDao.setInLibrary(
                seriesId = series.id,
                inLibrary = !series.inLibrary,
                dateAdded = if (!series.inLibrary) System.currentTimeMillis() else 0L,
            )
        }
    }

    fun toggleMetadataSource() {
        val series = uiState.value.series ?: return
        val next = if (series.metadataSource == MetadataSource.ANILIST) {
            MetadataSource.EXTENSION_SOURCE
        } else {
            MetadataSource.ANILIST
        }
        viewModelScope.launch { seriesDao.update(series.copy(metadataSource = next).toEntity()) }
    }

    fun saveCustomMetadata(title: String, description: String) {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            seriesDao.update(
                series.copy(
                    customTitle = title.trim().ifBlank { null },
                    customDescription = description.trim().ifBlank { null },
                ).toEntity(),
            )
        }
    }

    fun updateTracking(status: ReadingStatus, score: Float?, notes: String, localOnly: Boolean) {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            seriesDao.update(
                series.copy(
                    readingStatus = status,
                    userScore = score,
                    privateNotes = notes.ifBlank { null },
                    localOnly = localOnly,
                ).toEntity(),
            )
        }
    }

    fun batchMarkRead(read: Boolean) {
        val ids = selectedChaptersFlow.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chapterDao.setReadBatch(ids, read)
            clearChapterSelection()
        }
    }

    fun batchBookmark(bookmarked: Boolean) {
        val ids = selectedChaptersFlow.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chapterDao.setBookmarkBatch(ids, bookmarked)
            clearChapterSelection()
        }
    }

    fun enqueueChapterDownload(chapter: Chapter) {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            downloadDao.upsert(
                DownloadEntity(
                    seriesId = series.id,
                    chapterId = chapter.id,
                    seriesTitle = series.displayTitle,
                    chapterName = chapter.name,
                    sourceId = series.sourceId,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onBack: () -> Unit,
    onOpenReader: (seriesId: Long, chapterId: Long, page: Int) -> Unit,
    onOpenWebView: (String) -> Unit,
    onOpenMigrate: (Long) -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(seriesId) { viewModel.load(seriesId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val series = state.series

    var expandedSynopsis by remember { mutableStateOf(false) }
    var showTrackerSheet by remember { mutableStateOf(false) }
    var showEditMetaDialog by remember { mutableStateOf(false) }

    val nextUnreadChapter = state.chapters.lastOrNull { !it.read } ?: state.chapters.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.selectedChapterIds.isNotEmpty()) {
                            "${state.selectedChapterIds.size} selected"
                        } else {
                            series?.displayTitle.orEmpty()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedChapterIds.isNotEmpty()) viewModel.clearChapterSelection()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.selectedChapterIds.isNotEmpty()) {
                        IconButton(onClick = { viewModel.batchMarkRead(true) }) {
                            Icon(Icons.Rounded.DoneAll, contentDescription = "Mark read")
                        }
                        IconButton(onClick = { viewModel.batchBookmark(true) }) {
                            Icon(Icons.Rounded.Bookmark, contentDescription = "Bookmark")
                        }
                    } else if (series != null) {
                        IconButton(onClick = { showEditMetaDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit custom metadata")
                        }
                        IconButton(onClick = { onOpenWebView(series.url) }) {
                            Icon(Icons.Rounded.Public, contentDescription = "Open in WebView")
                        }
                        IconButton(onClick = { onOpenMigrate(series.id) }) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Migrate Source")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (series != null && nextUnreadChapter != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onOpenReader(series.id, nextUnreadChapter.id, nextUnreadChapter.lastPageRead)
                    },
                    shape = CircleShape,
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    text = {
                        Text(
                            if (nextUnreadChapter.lastPageRead > 0) "Resume ${nextUnreadChapter.name}"
                            else "Start Reading",
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (series == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Series not found")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            // Dual-layered Hero Banner & Cover Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                ) {
                    AsyncImage(
                        model = series.bannerUrl ?: series.displayCoverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    ),
                                ),
                            ),
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AsyncImage(
                            model = series.displayCoverUrl,
                            contentDescription = series.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 100.dp, height = 144.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = series.displayTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    series.author,
                                    series.publicationStatus.name.lowercase().replaceFirstChar { it.uppercase() },
                                    series.anilistScore?.let { "★ $it%" },
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelLarge.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Action chips: Shelf, Tracker, Metadata Source toggle
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = series.inLibrary,
                        onClick = viewModel::toggleInLibrary,
                        leadingIcon = {
                            Icon(
                                if (series.inLibrary) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(if (series.inLibrary) "In Shelf" else "Add to Shelf") },
                    )
                    AssistChip(
                        onClick = { showTrackerSheet = true },
                        leadingIcon = {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(if (series.localOnly) "Local-Only" else "AniList Track") },
                    )
                    AssistChip(
                        onClick = viewModel::toggleMetadataSource,
                        label = {
                            Text(
                                if (series.metadataSource == MetadataSource.ANILIST) "Meta: AniList"
                                else "Meta: Source",
                            )
                        },
                    )
                }
            }

            // Expandable synopsis + genres
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (series.displayDescription.isNotBlank()) {
                        Text(
                            text = series.displayDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expandedSynopsis) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { expandedSynopsis = !expandedSynopsis },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(if (expandedSynopsis) "Show less" else "Read more")
                        }
                    }
                    if (series.genres.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            series.genres.forEach { genre ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Chapter Filter bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.chapters.size} Chapters",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                    )
                    TextButton(onClick = viewModel::toggleSort) {
                        Text(if (state.sortDescending) "Newest first" else "Oldest first")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChapterFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Chapters + Missing Chapter Gap Warning
            items(state.chapters, key = { it.id }) { chapter ->
                val missingBefore = state.missingGaps[chapter.id]
                if (missingBefore != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Missing $missingBefore chapter(s) before ${chapter.name}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                val selected = chapter.id in state.selectedChapterIds
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .combinedClickable(
                            onClick = {
                                if (state.selectedChapterIds.isNotEmpty()) {
                                    viewModel.toggleChapterSelection(chapter.id)
                                } else {
                                    onOpenReader(series.id, chapter.id, chapter.lastPageRead)
                                }
                            },
                            onLongClick = { viewModel.toggleChapterSelection(chapter.id) },
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (chapter.bookmark) {
                                    Icon(
                                        Icons.Rounded.Bookmark,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = chapter.name,
                                    style = MaterialTheme.typography.titleSmall.tabular(),
                                    color = if (chapter.read) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    chapter.scanlator,
                                    if (!chapter.read && chapter.lastPageRead > 0) "Page ${chapter.lastPageRead + 1}" else null,
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.enqueueChapterDownload(chapter) }) {
                            Icon(
                                imageVector = if (chapter.downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                contentDescription = "Download chapter",
                                tint = if (chapter.downloaded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTrackerSheet && series != null) {
        var selectedStatus by remember { mutableStateOf(series.readingStatus) }
        var scoreText by remember { mutableStateOf(series.userScore?.toString().orEmpty()) }
        var notes by remember { mutableStateOf(series.privateNotes.orEmpty()) }
        var localOnly by remember { mutableStateOf(series.localOnly) }

        ModalBottomSheet(
            onDismissRequest = { showTrackerSheet = false },
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("AniList Tracker & Privacy", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Local-Only (never sync this series)")
                    Switch(checked = localOnly, onCheckedChange = { localOnly = it })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReadingStatus.entries.forEach { st ->
                        FilterChip(
                            selected = selectedStatus == st,
                            onClick = { selectedStatus = st },
                            label = { Text(st.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { scoreText = it },
                    label = { Text("Personal Score (0-10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Private Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        viewModel.updateTracking(selectedStatus, scoreText.toFloatOrNull(), notes, localOnly)
                        showTrackerSheet = false
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }

    if (showEditMetaDialog && series != null) {
        var title by remember { mutableStateOf(series.customTitle ?: series.title) }
        var desc by remember { mutableStateOf(series.customDescription ?: series.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showEditMetaDialog = false },
            title = { Text("Edit Local Metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Custom Title") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Custom Synopsis") },
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCustomMetadata(title, desc)
                    showEditMetaDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditMetaDialog = false }) { Text("Cancel") }
            },
        )
    }
}
