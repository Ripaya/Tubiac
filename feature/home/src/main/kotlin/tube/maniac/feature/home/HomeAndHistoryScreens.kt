package tube.maniac.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.HistoryDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.SeriesEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.SeriesCoverCard
import tube.maniac.core.designsystem.components.TubiacSectionHeader
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.model.AniListMedia
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.HistoryEntry
import tube.maniac.core.model.Series
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val incognitoMode: Boolean = false,
    val continueReading: List<HistoryEntry> = emptyList(),
    val latestReleased: List<Chapter> = emptyList(),
    val lastAdded: List<Series> = emptyList(),
    val popularAniList: List<AniListMedia> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    historyDao: HistoryDao,
    chapterDao: ChapterDao,
    private val seriesDao: SeriesDao,
    private val aniListClient: AniListGraphQlClient,
) : ViewModel() {

    private val popularFlow = MutableStateFlow<List<AniListMedia>>(emptyList())

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settingsFlow,
        historyDao.observeContinueReading(12),
        chapterDao.observeLatestReleased(15),
        seriesDao.observeLastAdded(12),
        popularFlow,
    ) { settings, history, latest, added, popular ->
        HomeUiState(
            incognitoMode = settings.incognitoMode,
            continueReading = history.map { it.toDomain() },
            latestReleased = latest.map { it.toDomain() },
            lastAdded = added.map { it.toDomain() },
            popularAniList = popular,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refreshPopular()
    }

    fun refreshPopular() {
        viewModelScope.launch {
            aniListClient.fetchPopularManga(page = 1, perPage = 16).onSuccess {
                popularFlow.value = it
            }
        }
    }

    fun toggleIncognito() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(incognitoMode = !it.incognitoMode) }
        }
    }

    fun openPopularSeries(media: AniListMedia, onCreated: (Long) -> Unit) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenGlobalSearch: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSeriesDetail: (Long) -> Unit,
    onResumeChapter: (seriesId: Long, chapterId: Long, page: Int) -> Unit,
    onOpenBrowse: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tubiac", style = MaterialTheme.typography.headlineMedium)
                        if (state.incognitoMode) {
                            Text(
                                text = "Incognito active — history & sync paused",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGlobalSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Global Search")
                    }
                    IconButton(onClick = viewModel::toggleIncognito) {
                        Icon(
                            imageVector = Icons.Rounded.VisibilityOff,
                            contentDescription = "Toggle Incognito Mode",
                            tint = if (state.incognitoMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "Reading History")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Continue Reading Carousel
            item {
                TubiacSectionHeader(
                    title = "Continue Reading",
                    actionLabel = if (state.continueReading.isNotEmpty()) "History" else null,
                    onActionClick = onOpenHistory,
                )
                if (state.continueReading.isEmpty()) {
                    ExpressiveEmptyState(
                        icon = Icons.Rounded.AutoStories,
                        title = "Ready when you are",
                        message = "Open a series from your Shelf or Browse to jump right back in here.",
                        actionLabel = "Explore Sources",
                        onAction = onOpenBrowse,
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.continueReading, key = { it.id }) { entry ->
                            ContinueReadingCard(
                                entry = entry,
                                onResume = { onResumeChapter(entry.seriesId, entry.chapterId, entry.lastPageRead) },
                                onOpenDetail = { onOpenSeriesDetail(entry.seriesId) },
                            )
                        }
                    }
                }
            }

            // 2. Latest Released
            if (state.latestReleased.isNotEmpty()) {
                item {
                    TubiacSectionHeader(title = "Latest Released")
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.latestReleased.take(6).forEach { chapter ->
                            Card(
                                onClick = { onResumeChapter(chapter.seriesId, chapter.id, chapter.lastPageRead) },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                                        Text(
                                            text = chapter.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = chapter.scanlator ?: "New chapter",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { onResumeChapter(chapter.seriesId, chapter.id, chapter.lastPageRead) },
                                        shape = CircleShape,
                                    ) {
                                        Text("Read")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Last Added
            if (state.lastAdded.isNotEmpty()) {
                item {
                    TubiacSectionHeader(title = "Last Added")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.lastAdded, key = { it.id }) { series ->
                            SeriesCoverCard(
                                title = series.displayTitle,
                                coverUrl = series.displayCoverUrl,
                                onClick = { onOpenSeriesDetail(series.id) },
                                modifier = Modifier.width(124.dp),
                            )
                        }
                    }
                }
            }

            // 4. Popular on AniList
            item {
                TubiacSectionHeader(
                    title = "Popular",
                    actionLabel = "Refresh",
                    onActionClick = viewModel::refreshPopular,
                )
                if (state.popularAniList.isEmpty()) {
                    Text(
                        text = "No trending titles loaded yet. Connect to fetch AniList Popular.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.popularAniList, key = { it.id }) { media ->
                            SeriesCoverCard(
                                title = media.preferredTitle,
                                coverUrl = media.coverLarge,
                                subtitle = media.averageScore?.let { "★ $it%" },
                                onClick = { viewModel.openPopularSeries(media, onOpenSeriesDetail) },
                                modifier = Modifier.width(132.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    entry: HistoryEntry,
    onResume: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val progress = if (entry.totalPages > 0) {
        (entry.lastPageRead.toFloat() / entry.totalPages.toFloat()).coerceIn(0f, 1f)
    } else {
        0.1f
    }

    Card(
        onClick = onOpenDetail,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(268.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = entry.seriesTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 68.dp, height = 98.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.chapterName} • p.${entry.lastPageRead + 1}",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                )
                FilledTonalButton(
                    onClick = onResume,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Resume", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

enum class HistoryDateBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    OLDER("Older"),
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyDao: HistoryDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    val incognitoMode: StateFlow<Boolean> = settingsRepository.settingsFlow
        .combine(searchQuery) { settings, _ -> settings.incognitoMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val groupedHistory: StateFlow<Map<HistoryDateBucket, List<HistoryEntry>>> = combine(
        historyDao.observeHistory(),
        searchQuery,
    ) { entries, query ->
        val filtered = entries.map { it.toDomain() }.filter { entry ->
            query.isBlank() ||
                entry.seriesTitle.contains(query, ignoreCase = true) ||
                entry.chapterName.contains(query, ignoreCase = true)
        }
        groupByBucket(filtered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun updateQuery(query: String) {
        searchQuery.value = query
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { historyDao.deleteById(id) }
    }

    fun clearAll() {
        viewModelScope.launch { historyDao.clearAll() }
    }

    private fun groupByBucket(entries: List<HistoryEntry>): Map<HistoryDateBucket, List<HistoryEntry>> {
        val now = Calendar.getInstance()
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfYesterday = startOfToday - 86_400_000L
        val startOfWeek = now.timeInMillis - (7 * 86_400_000L)

        return entries.groupBy { entry ->
            when {
                entry.lastReadAt >= startOfToday -> HistoryDateBucket.TODAY
                entry.lastReadAt >= startOfYesterday -> HistoryDateBucket.YESTERDAY
                entry.lastReadAt >= startOfWeek -> HistoryDateBucket.THIS_WEEK
                else -> HistoryDateBucket.OLDER
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onResumeChapter: (seriesId: Long, chapterId: Long, page: Int) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val grouped by viewModel.groupedHistory.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val incognito by viewModel.incognitoMode.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm • MMM d", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (grouped.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear History")
                        }
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
            if (incognito) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Incognito Mode is on. Reading sessions will not be recorded.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::updateQuery,
                placeholder = { Text("Search reading history") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (grouped.isEmpty()) {
                ExpressiveEmptyState(
                    icon = Icons.Rounded.History,
                    title = "No reading history",
                    message = "Chapters you read appear here grouped by day for 1-tap resume.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HistoryDateBucket.entries.forEach { bucket ->
                        val itemsInBucket = grouped[bucket].orEmpty()
                        if (itemsInBucket.isNotEmpty()) {
                            item(key = "header_${bucket.name}") {
                                Text(
                                    text = bucket.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(itemsInBucket, key = { it.id }) { entry ->
                                Card(
                                    onClick = { onResumeChapter(entry.seriesId, entry.chapterId, entry.lastPageRead) },
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        AsyncImage(
                                            model = entry.coverUrl,
                                            contentDescription = entry.seriesTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 48.dp, height = 68.dp)
                                                .clip(MaterialTheme.shapes.extraSmall),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.seriesTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${entry.chapterName} • ${entry.sourceName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = timeFormatter.format(Date(entry.lastReadAt)),
                                                style = MaterialTheme.typography.labelSmall.tabular(),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onResumeChapter(entry.seriesId, entry.chapterId, entry.lastPageRead) },
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                                        }
                                        IconButton(onClick = { viewModel.deleteEntry(entry.id) }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Remove entry")
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes your local chronological reading log without deleting your library progress.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}
