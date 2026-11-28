package tube.maniac.feature.detail

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.ChapterEntity
import tube.maniac.core.database.entity.DownloadEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.designsystem.components.ErrorRecoveryCard
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.TabularBadge
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.unwrapRoot
import tube.maniac.core.extension.loader.ExtensionExtrasExtractor
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.AniListMedia
import tube.maniac.core.model.AniListReview
import tube.maniac.core.model.AniListThread
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.ExtractedComment
import tube.maniac.core.model.MetadataSource
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.Series
import tube.maniac.core.model.SmartReaderModeDetector
import tube.maniac.core.network.CloudflareChallengeException
import tube.maniac.core.network.IpBlockedException
import tube.maniac.feature.downloads.DownloadWorker
import javax.inject.Inject

enum class ChapterFilter { ALL, UNREAD, DOWNLOADED, BOOKMARKED }

data class SeriesDetailUiState(
    val series: Series? = null,
    val chapters: List<Chapter> = emptyList(),
    val missingGaps: Map<Long, Int> = emptyMap(),
    val filter: ChapterFilter = ChapterFilter.ALL,
    val sortDescending: Boolean = true,
    val selectedChapterIds: Set<Long> = emptySet(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val blockedUrl: String? = null,
    val sourceComments: List<ExtractedComment> = emptyList(),
    val anilistReviews: List<AniListReview> = emptyList(),
    val anilistThreads: List<AniListThread> = emptyList(),
    val loadingCommunity: Boolean = false,
    val anilistSearchMatches: List<AniListMedia> = emptyList(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val seriesDao: SeriesDao,
    private val chapterDao: ChapterDao,
    private val downloadDao: DownloadDao,
    private val sourceManager: SourceManager,
    private val settingsRepository: SettingsRepository,
    private val aniListClient: AniListGraphQlClient,
    private val extrasExtractor: ExtensionExtrasExtractor,
) : ViewModel() {

    private val seriesIdFlow = MutableStateFlow<Long?>(null)
    private val filterFlow = MutableStateFlow(ChapterFilter.ALL)
    private val sortDescFlow = MutableStateFlow(true)
    private val selectedChaptersFlow = MutableStateFlow<Set<Long>>(emptySet())
    private val refreshingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val commentsFlow = MutableStateFlow<List<ExtractedComment>>(emptyList())
    private val anilistCommunityFlow = MutableStateFlow<Pair<List<AniListReview>, List<AniListThread>>>(emptyList<AniListReview>() to emptyList())
    private val loadingCommunityFlow = MutableStateFlow(false)
    private val anilistSearchFlow = MutableStateFlow<List<AniListMedia>>(emptyList())

    val uiState: StateFlow<SeriesDetailUiState> = seriesIdFlow.flatMapLatest { id ->
        if (id == null) {
            flowOf(SeriesDetailUiState())
        } else {
            combine(
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
                        if (desc) {
                            list.sortedWith(
                                compareByDescending<Chapter> { it.chapterNumber }.thenBy { it.sourceOrder },
                            )
                        } else {
                            list.sortedWith(
                                compareBy<Chapter> { it.chapterNumber }.thenByDescending { it.sourceOrder },
                            )
                        }
                    }
                    SeriesDetailUiState(
                        series = entity?.toDomain(),
                        chapters = filtered,
                        missingGaps = gaps,
                        filter = filter,
                        sortDescending = desc,
                        selectedChapterIds = selected,
                    )
                },
                refreshingFlow,
                errorFlow,
                combine(
                    commentsFlow,
                    anilistCommunityFlow,
                    loadingCommunityFlow,
                    anilistSearchFlow,
                ) { comments, community, loadingComm, matches ->
                    listOf(comments, community, loadingComm, matches)
                },
            ) { base, refreshing, err, extra ->
                @Suppress("UNCHECKED_CAST")
                val comments = extra[0] as List<ExtractedComment>
                @Suppress("UNCHECKED_CAST")
                val community = extra[1] as Pair<List<AniListReview>, List<AniListThread>>
                val loadingComm = extra[2] as Boolean
                @Suppress("UNCHECKED_CAST")
                val matches = extra[3] as List<AniListMedia>
                base.copy(
                    isRefreshing = refreshing,
                    errorMessage = err.first,
                    blockedUrl = err.second,
                    sourceComments = comments,
                    anilistReviews = community.first,
                    anilistThreads = community.second,
                    loadingCommunity = loadingComm,
                    anilistSearchMatches = matches,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesDetailUiState())

    fun loadCommunityAndComments() {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            loadingCommunityFlow.value = true
            if (series.sourceId > 0L) {
                val source = sourceManager.getSource(series.sourceId)
                commentsFlow.value = extrasExtractor
                    .extractCommentsFromSourcePage(source, series.url)
                    .getOrDefault(emptyList())
            }
            series.anilistId?.let { mediaId ->
                anilistCommunityFlow.value = aniListClient
                    .fetchMediaCommunity(mediaId)
                    .getOrDefault(emptyList<AniListReview>() to emptyList())
            }
            loadingCommunityFlow.value = false
        }
    }

    fun searchAniListToBind(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            anilistSearchFlow.value = aniListClient.searchManga(query.trim(), perPage = 8)
                .getOrDefault(emptyList())
        }
    }

    fun bindAniListMedia(media: AniListMedia) {
        val series = uiState.value.series ?: return
        viewModelScope.launch {
            seriesDao.update(
                series.copy(
                    anilistId = media.id,
                    anilistScore = media.averageScore,
                    bannerUrl = series.bannerUrl ?: media.bannerImage,
                    coverUrl = series.coverUrl ?: media.coverLarge,
                    description = series.description ?: media.description,
                    genres = if (series.genres.isEmpty()) media.genres else series.genres,
                ).toEntity(),
            )
            anilistSearchFlow.value = emptyList()
            loadCommunityAndComments()
        }
    }

    fun load(seriesId: Long) {
        seriesIdFlow.value = seriesId
        viewModelScope.launch(Dispatchers.IO) {
            val series = seriesDao.getById(seriesId) ?: return@launch
            val existing = chapterDao.getBySeries(seriesId)
            if (existing.isEmpty() && series.sourceId >= 0L) {
                refreshFromSource(seriesId)
            }
        }
    }

    fun refreshFromSource(targetSeriesId: Long? = seriesIdFlow.value) {
        val seriesId = targetSeriesId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val entity = seriesDao.getById(seriesId) ?: return@launch
            if (entity.sourceId < 0L) return@launch
            val source = sourceManager.getSource(entity.sourceId) ?: return@launch
            refreshingFlow.value = true
            errorFlow.value = null to null
            runCatching {
                val sManga = SManga.create().apply {
                    url = entity.url
                    title = entity.title
                }
                val httpSource = source as? HttpSource
                val existingEntities = chapterDao.getBySeries(seriesId)
                val existingSChapters = existingEntities.map { ch ->
                    SChapter.create().apply {
                        url = ch.url
                        name = ch.name
                        chapter_number = ch.chapterNumber
                        scanlator = ch.scanlator
                        date_upload = ch.dateUpload
                    }
                }
                val update = source.getMangaUpdate(
                    manga = sManga,
                    chapters = existingSChapters,
                    fetchDetails = true,
                    fetchChapters = true,
                )
                val details = runCatching { update.manga() }.getOrNull()
                if (details != null) {
                    sManga.copyFrom(details)
                    seriesDao.update(
                        entity.copy(
                            author = details.author ?: entity.author,
                            artist = details.artist ?: entity.artist,
                            description = details.description ?: entity.description,
                            coverUrl = details.thumbnail_url ?: entity.coverUrl,
                            bannerUrl = details.banner ?: entity.bannerUrl,
                            genres = details.genres.takeIf { it.isNotEmpty() } ?: entity.genres,
                            publicationStatus = when (details.status) {
                                SManga.ONGOING -> PublicationStatus.ONGOING
                                SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> PublicationStatus.COMPLETED
                                SManga.ON_HIATUS -> PublicationStatus.HIATUS
                                SManga.CANCELLED -> PublicationStatus.CANCELLED
                                else -> entity.publicationStatus
                            },
                            lastUpdated = System.currentTimeMillis(),
                        ),
                    )
                }
                val remoteChapters = update.chapters()
                val existingUrls = existingEntities.map { it.url }.toSet()
                val newEntities = remoteChapters
                    .filterNot { it.url in existingUrls }
                    .mapIndexed { idx, ch ->
                        httpSource?.let { hs -> runCatching { hs.prepareNewChapter(ch, sManga) } }
                        ChapterEntity(
                            seriesId = seriesId,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapter_number,
                            scanlator = ch.scanlator,
                            dateUpload = ch.date_upload,
                            dateFetch = System.currentTimeMillis(),
                            sourceOrder = idx,
                        )
                    }
                if (newEntities.isNotEmpty()) {
                    chapterDao.upsertAll(newEntities)
                }
            }.onFailure { err ->
                val root = err.unwrapRoot()
                val url = when (root) {
                    is CloudflareChallengeException -> root.challengeUrl
                    is IpBlockedException -> root.blockedUrl
                    else -> {
                        if (root !is kotlinx.coroutines.CancellationException) {
                            AutoIssueReporter.reportRuntimeBug(
                                category = "series-detail",
                                title = "[Detail] Failed chapter refresh via ${source.name}",
                                detail = "series=${entity.title}, url=${entity.url}",
                                throwable = root,
                            )
                        }
                        (source as? HttpSource)?.let {
                            runCatching {
                                it.getMangaUrl(SManga.create().apply { url = entity.url; title = entity.title })
                            }.getOrNull()
                        }
                    }
                }
                errorFlow.value = (root.message ?: "Failed to refresh chapters") to url
            }
            refreshingFlow.value = false
        }
    }

    fun resolveWebViewUrl(series: Series): String {
        val source = sourceManager.getSource(series.sourceId) as? HttpSource
        if (source != null) {
            return source.getMangaUrl(SManga.create().apply { url = series.url; title = series.title })
        }
        if (series.url.startsWith("anilist://") && series.anilistId != null) {
            return "https://anilist.co/manga/${series.anilistId}"
        }
        return series.url
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
            val settings = settingsRepository.currentSettings()
            DownloadWorker.enqueue(context, settings.wifiOnlyDownloads)
        }
    }

    fun downloadAllUnread() {
        val series = uiState.value.series ?: return
        val unread = uiState.value.chapters.filter { !it.read && !it.downloaded }
        if (unread.isEmpty()) return
        viewModelScope.launch {
            unread.forEach { chapter ->
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
            val settings = settingsRepository.currentSettings()
            DownloadWorker.enqueue(context, settings.wifiOnlyDownloads)
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
    var showAniListLinkDialog by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }

    val nextUnreadChapter = if (state.sortDescending) {
        state.chapters.lastOrNull { !it.read } ?: state.chapters.lastOrNull()
    } else {
        state.chapters.firstOrNull { !it.read } ?: state.chapters.firstOrNull()
    }

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = if (state.selectedChapterIds.isNotEmpty()) "CHAPTER SELECTION" else "SERIES ARCHIVE",
                    title = if (state.selectedChapterIds.isNotEmpty()) {
                        "${state.selectedChapterIds.size} Selected"
                    } else {
                        series?.displayTitle.orEmpty()
                    },
                    onBack = {
                        if (state.selectedChapterIds.isNotEmpty()) viewModel.clearChapterSelection()
                        else onBack()
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
                            if (series.sourceId >= 0L) {
                                IconButton(onClick = { viewModel.refreshFromSource(series.id) }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh chapters")
                                }
                            }
                            IconButton(onClick = { showEditMetaDialog = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit custom metadata")
                            }
                            IconButton(onClick = { onOpenWebView(viewModel.resolveWebViewUrl(series)) }) {
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
                        shape = MaterialTheme.shapes.small,
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
                // Editorial Matte Banner & Cover Header
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp),
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
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.2f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
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
                                    .clip(MaterialTheme.shapes.medium)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

            // Action chips: Shelf, Tracker, Metadata Source, Direct AniList Link & Comments
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    AssistChip(
                        onClick = { showAniListLinkDialog = true },
                        leadingIcon = {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(if (series.anilistId != null) "AL #${series.anilistId}" else "Link AniList") },
                    )
                    AssistChip(
                        onClick = {
                            showCommunity = !showCommunity
                            if (showCommunity && state.sourceComments.isEmpty() && state.anilistReviews.isEmpty()) {
                                viewModel.loadCommunityAndComments()
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(if (showCommunity) "Hide Comments" else "Comments & Reviews") },
                    )
                }
            }

            if (showCommunity) {
                item {
                    MatteCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("COMMENTS, REVIEWS & THREADS", style = MaterialTheme.typography.labelSmall.kicker, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = viewModel::loadCommunityAndComments) { Text("Refresh") }
                            }
                            if (state.loadingCommunity) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (state.sourceComments.isEmpty() && state.anilistReviews.isEmpty() && state.anilistThreads.isEmpty() && !state.loadingCommunity) {
                                Text(
                                    text = "No extracted source comments or AniList threads yet. Link an AniList ID or open in WebView for embedded discussions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.sourceComments.forEach { comment ->
                                MatteCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("${comment.author} • ${comment.origin}", style = MaterialTheme.typography.labelLarge)
                                        Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            state.anilistReviews.forEach { review ->
                                MatteCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("AniList Review by ${review.userName.ifBlank { "Member" }} • ${review.score}/100", style = MaterialTheme.typography.labelLarge)
                                        Text(review.summary.ifBlank { review.body }, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            state.anilistThreads.forEach { thread ->
                                MatteCard(
                                    onClick = { if (thread.siteUrl.isNotBlank()) onOpenWebView(thread.siteUrl) },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(thread.title, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "AniList Thread • ${thread.replyCount} replies • by ${thread.authorName}",
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
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

            if (state.isRefreshing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            state.errorMessage?.let { err ->
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ErrorRecoveryCard(
                            title = "Source error",
                            reason = err,
                            onRetry = { viewModel.refreshFromSource(series.id) },
                            onOpenWebView = { onOpenWebView(state.blockedUrl ?: viewModel.resolveWebViewUrl(series)) },
                            onMigrateSource = { onOpenMigrate(series.id) },
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (series.sourceId > 0L && state.chapters.any { !it.read && !it.downloaded }) {
                            TextButton(onClick = viewModel::downloadAllUnread) {
                                Text("Download unread")
                            }
                        }
                        TextButton(onClick = viewModel::toggleSort) {
                            Text(if (state.sortDescending) "Newest first" else "Oldest first")
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ChapterFilter.entries) { filter ->
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
                MatteCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    selected = selected,
                    onClick = {
                        if (state.selectedChapterIds.isNotEmpty()) {
                            viewModel.toggleChapterSelection(chapter.id)
                        } else {
                            onOpenReader(series.id, chapter.id, chapter.lastPageRead)
                        }
                    },
                    onLongClick = { viewModel.toggleChapterSelection(chapter.id) },
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

    if (showAniListLinkDialog && series != null) {
        var searchQuery by remember { mutableStateOf(series.displayTitle) }
        AlertDialog(
            onDismissRequest = { showAniListLinkDialog = false },
            title = { Text("Link Direct to AniList") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Manga title") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.searchAniListToBind(searchQuery) }) { Text("Find") }
                    }
                    state.anilistSearchMatches.forEach { media ->
                        MatteCard(
                            onClick = {
                                viewModel.bindAniListMedia(media)
                                showAniListLinkDialog = false
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(media.preferredTitle, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "#${media.id} • ${media.format.name}" + (media.averageScore?.let { " • ★ $it%" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAniListLinkDialog = false }) { Text("Close") }
            },
        )
    }
}
