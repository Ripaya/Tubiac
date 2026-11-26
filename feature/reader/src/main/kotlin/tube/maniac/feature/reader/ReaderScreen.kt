package tube.maniac.feature.reader

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import tube.maniac.core.anilist.AniListSyncEngine
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.HistoryDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.DownloadEntity
import tube.maniac.core.database.entity.HistoryEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.ColorFilterConfig
import tube.maniac.core.model.ReaderBackground
import tube.maniac.core.model.ReaderMode
import tube.maniac.core.model.ReaderPreferences
import tube.maniac.core.model.Series
import tube.maniac.core.model.SmartReaderModeDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ReaderPageItem(
    val index: Int,
    val imageUrl: String,
)

data class ReaderUiState(
    val series: Series? = null,
    val chapter: Chapter? = null,
    val chapters: List<Chapter> = emptyList(),
    val pages: List<ReaderPageItem> = emptyList(),
    val activeMode: ReaderMode = ReaderMode.RTL,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val incognitoMode: Boolean = false,
    val currentPageIndex: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val downloadDao: DownloadDao,
    private val sourceManager: SourceManager,
    private val settingsRepository: SettingsRepository,
    private val aniListSyncEngine: AniListSyncEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    fun loadChapter(seriesId: Long, chapterId: Long, initialPage: Int, isLandscapeTablet: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val settings = settingsRepository.currentSettings()
            val series = seriesDao.getById(seriesId)?.toDomain()
            val chapters = chapterDao.getBySeries(seriesId).map { it.toDomain() }
            val chapter = chapters.firstOrNull { it.id == chapterId }

            if (series == null || chapter == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Chapter not found")
                return@launch
            }

            val resolvedMode = SmartReaderModeDetector.resolveMode(
                series = series,
                defaultMode = settings.readerPreferences.defaultMode,
                smartAutoDetect = settings.readerPreferences.smartAutoDetect,
                isLandscapeTablet = isLandscapeTablet,
            )

            val source = sourceManager.getSource(series.sourceId)
            var loadError: String? = null
            val fetchedPages = runCatching {
                if (source != null) {
                    val sChapter = SChapter.create().apply {
                        url = chapter.url
                        name = chapter.name
                        chapter_number = chapter.chapterNumber
                    }
                    source.getPageList(sChapter).mapIndexed { idx, p ->
                        ReaderPageItem(idx, p.imageUrl ?: p.url)
                    }
                } else {
                    emptyList()
                }
            }.onFailure { err ->
                loadError = err.message ?: "Failed to load chapter pages"
            }.getOrDefault(emptyList())

            _uiState.value = ReaderUiState(
                series = series,
                chapter = chapter,
                chapters = chapters,
                pages = fetchedPages,
                activeMode = resolvedMode,
                preferences = settings.readerPreferences,
                incognitoMode = settings.incognitoMode,
                currentPageIndex = initialPage.coerceIn(0, (fetchedPages.size - 1).coerceAtLeast(0)),
                isLoading = false,
                errorMessage = loadError,
            )

            triggerDownloadAhead(series, chapters, chapter, settings.downloadAheadCount)
        }
    }

    fun onPageChanged(pageIndex: Int) {
        val state = _uiState.value
        val series = state.series ?: return
        val chapter = state.chapter ?: return
        val total = state.pages.size.coerceAtLeast(1)
        val clamped = pageIndex.coerceIn(0, total - 1)
        _uiState.value = state.copy(currentPageIndex = clamped)

        val percent = ((clamped + 1) * 100) / total
        val markedRead = chapter.read || percent >= state.preferences.autoMarkReadThresholdPercent

        viewModelScope.launch(Dispatchers.IO) {
            if (!state.incognitoMode) {
                chapterDao.updateProgress(chapter.id, markedRead, clamped, total)
                val sourceName = sourceManager.getSource(series.sourceId)?.name ?: "Local Source"
                historyDao.upsert(
                    HistoryEntity(
                        seriesId = series.id,
                        chapterId = chapter.id,
                        seriesTitle = series.displayTitle,
                        coverUrl = series.displayCoverUrl,
                        chapterName = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        sourceName = sourceName,
                        lastReadAt = System.currentTimeMillis(),
                        lastPageRead = clamped,
                        totalPages = total,
                    ),
                )
                if (markedRead && !chapter.read) {
                    val completedCount = state.chapters.count { it.read } + 1
                    val token = settingsRepository.anilistTokenFlow.first()
                    aniListSyncEngine.pushSeriesProgress(series, completedCount, emptyList(), token)
                }
            }
        }
    }

    fun setSeriesReaderMode(mode: ReaderMode) {
        val series = _uiState.value.series ?: return
        _uiState.value = _uiState.value.copy(activeMode = mode)
        viewModelScope.launch(Dispatchers.IO) {
            seriesDao.update(series.copy(readerModeOverride = mode).toEntity())
        }
    }

    fun updateColorFilter(filter: ColorFilterConfig) {
        _uiState.value = _uiState.value.copy(
            preferences = _uiState.value.preferences.copy(colorFilter = filter),
        )
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(readerPreferences = it.readerPreferences.copy(colorFilter = filter))
            }
        }
    }

    fun updateBackground(bg: ReaderBackground) {
        _uiState.value = _uiState.value.copy(
            preferences = _uiState.value.preferences.copy(background = bg),
        )
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(readerPreferences = it.readerPreferences.copy(background = bg))
            }
        }
    }

    private suspend fun triggerDownloadAhead(
        series: Series,
        chapters: List<Chapter>,
        current: Chapter,
        aheadCount: Int,
    ) {
        if (aheadCount <= 0 || series.sourceId == 0L) return
        val ordered = chapters.sortedBy { it.chapterNumber }
        val idx = ordered.indexOfFirst { it.id == current.id }
        if (idx < 0) return
        ordered.drop(idx + 1).take(aheadCount).filterNot { it.downloaded }.forEach { upcoming ->
            downloadDao.upsert(
                DownloadEntity(
                    seriesId = series.id,
                    chapterId = upcoming.id,
                    seriesTitle = series.displayTitle,
                    chapterName = upcoming.name,
                    sourceId = series.sourceId,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    seriesId: Long,
    chapterId: Long,
    initialPage: Int,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val configuration = LocalConfiguration.current
    val isLandscapeTablet = configuration.screenWidthDp >= 720 &&
        configuration.screenWidthDp > configuration.screenHeightDp

    LaunchedEffect(seriesId, chapterId) {
        viewModel.loadChapter(seriesId, chapterId, initialPage, isLandscapeTablet)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showOverlay by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChapterDrawer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canvasBg = when (state.preferences.background) {
        ReaderBackground.BLACK -> Color.Black
        ReaderBackground.DARK_GRAY -> Color(0xFF1A1C20)
        ReaderBackground.WHITE -> Color.White
        ReaderBackground.SMART_EDGE -> Color(0xFF101114)
        ReaderBackground.DYNAMIC -> MaterialTheme.colorScheme.surface
    }

    val colorFilter = remember(state.preferences.colorFilter) {
        ReaderColorFilterBuilder.buildColorMatrix(state.preferences.colorFilter)
            ?.let { ColorFilter.colorMatrix(it) }
    }

    val batteryPercent = remember {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }
    val clockText = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvasBg),
    ) {
        val pages = state.pages
        if (pages.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { showOverlay = !showOverlay } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.errorMessage ?: "No pages available for this chapter.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else if (pages.isNotEmpty()) {
            when (state.activeMode) {
                ReaderMode.WEBTOON, ReaderMode.CONTINUOUS_VERTICAL -> {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPageIndex)
                    val sidePad = (state.preferences.webtoonSidePaddingPercent * 2).dp
                    val itemGap = if (state.activeMode == ReaderMode.WEBTOON) 0.dp else 10.dp

                    LaunchedEffect(listState.firstVisibleItemIndex) {
                        viewModel.onPageChanged(listState.firstVisibleItemIndex)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.preferences.tapZonePreset) {
                                detectTapGestures { offset ->
                                    val action = TapZoneResolver.resolve(
                                        normalizedX = offset.x / size.width.coerceAtLeast(1),
                                        normalizedY = offset.y / size.height.coerceAtLeast(1),
                                        preset = state.preferences.tapZonePreset,
                                        isRtl = false,
                                    )
                                    when (action) {
                                        ReaderTapAction.TOGGLE_MENU -> showOverlay = !showOverlay
                                        ReaderTapAction.NEXT -> scope.launch {
                                            listState.animateScrollToItem(
                                                (listState.firstVisibleItemIndex + 1).coerceAtMost(pages.lastIndex),
                                            )
                                        }
                                        ReaderTapAction.PREVIOUS -> scope.launch {
                                            listState.animateScrollToItem(
                                                (listState.firstVisibleItemIndex - 1).coerceAtLeast(0),
                                            )
                                        }
                                        ReaderTapAction.NONE -> Unit
                                    }
                                }
                            },
                        contentPadding = PaddingValues(horizontal = sidePad),
                        verticalArrangement = Arrangement.spacedBy(itemGap),
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.index }) { _, page ->
                            ZoomableAsyncImage(
                                model = page.imageUrl,
                                contentDescription = "Page ${page.index + 1}",
                                contentScale = ContentScale.FillWidth,
                                colorFilter = colorFilter,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                ReaderMode.LTR, ReaderMode.RTL, ReaderMode.DOUBLE_PAGE_SPREAD -> {
                    val isRtl = state.activeMode == ReaderMode.RTL
                    val spreadMode = state.activeMode == ReaderMode.DOUBLE_PAGE_SPREAD
                    val displayUnits = remember(pages, spreadMode) {
                        if (spreadMode) pages.chunked(2) else pages.map { listOf(it) }
                    }
                    val pagerState = rememberPagerState(
                        initialPage = (state.currentPageIndex / if (spreadMode) 2 else 1)
                            .coerceIn(0, (displayUnits.size - 1).coerceAtLeast(0)),
                        pageCount = { displayUnits.size },
                    )

                    LaunchedEffect(pagerState.currentPage) {
                        val firstPage = displayUnits.getOrNull(pagerState.currentPage)?.firstOrNull()?.index ?: 0
                        viewModel.onPageChanged(firstPage)
                    }

                    HorizontalPager(
                        state = pagerState,
                        reverseLayout = isRtl,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.preferences.tapZonePreset, isRtl) {
                                detectTapGestures { offset ->
                                    val action = TapZoneResolver.resolve(
                                        normalizedX = offset.x / size.width.coerceAtLeast(1),
                                        normalizedY = offset.y / size.height.coerceAtLeast(1),
                                        preset = state.preferences.tapZonePreset,
                                        isRtl = isRtl,
                                    )
                                    when (action) {
                                        ReaderTapAction.TOGGLE_MENU -> showOverlay = !showOverlay
                                        ReaderTapAction.NEXT -> scope.launch {
                                            pagerState.animateScrollToPage(
                                                (pagerState.currentPage + 1).coerceAtMost(displayUnits.lastIndex),
                                            )
                                        }
                                        ReaderTapAction.PREVIOUS -> scope.launch {
                                            pagerState.animateScrollToPage(
                                                (pagerState.currentPage - 1).coerceAtLeast(0),
                                            )
                                        }
                                        ReaderTapAction.NONE -> Unit
                                    }
                                }
                            },
                    ) { unitIndex ->
                        val pair = displayUnits[unitIndex]
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pair.forEach { page ->
                                ZoomableAsyncImage(
                                    model = page.imageUrl,
                                    contentDescription = "Page ${page.index + 1}",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = colorFilter,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Toggleable unobtrusive status overlay (page / time / battery)
        if (state.preferences.showStatusOverlay && pages.isNotEmpty()) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
            ) {
                Text(
                    text = "${state.currentPageIndex + 1} / ${pages.size}  •  $clockText  •  $batteryPercent%",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // Top & Bottom HUD Overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close reader")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.series?.displayTitle.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.chapter?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                },
                actions = {
                    if (state.incognitoMode) {
                        Icon(
                            Icons.Rounded.VisibilityOff,
                            contentDescription = "Incognito active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    IconButton(onClick = { showChapterDrawer = true }) {
                        Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Chapters")
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Reader settings")
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = showOverlay && pages.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    var sliderPage by remember(state.currentPageIndex) { mutableIntStateOf(state.currentPageIndex) }
                    Slider(
                        value = sliderPage.toFloat(),
                        onValueChange = { sliderPage = it.toInt() },
                        onValueChangeFinished = { viewModel.onPageChanged(sliderPage) },
                        valueRange = 0f..(pages.lastIndex.coerceAtLeast(1)).toFloat(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Page ${state.currentPageIndex + 1} of ${pages.size}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = Color.White,
                        )
                        Text(
                            text = state.activeMode.name.replace("_", " "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        val filter = state.preferences.colorFilter
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Reader Layout & Color Filter", style = MaterialTheme.typography.titleLarge)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderMode.entries.take(4).forEach { mode ->
                        FilterChip(
                            selected = state.activeMode == mode,
                            onClick = { viewModel.setSeriesReaderMode(mode) },
                            label = { Text(mode.name.replace("_", " ")) },
                        )
                    }
                }
                FilterChip(
                    selected = state.activeMode == ReaderMode.DOUBLE_PAGE_SPREAD,
                    onClick = { viewModel.setSeriesReaderMode(ReaderMode.DOUBLE_PAGE_SPREAD) },
                    label = { Text("Double-Page Spread (Landscape/Tablet)") },
                )

                Text("Background", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderBackground.entries.forEach { bg ->
                        FilterChip(
                            selected = state.preferences.background == bg,
                            onClick = { viewModel.updateBackground(bg) },
                            label = { Text(bg.name.replace("_", " ")) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Contrast, contentDescription = null)
                        Text("Custom Color Filter / E-Ink", style = MaterialTheme.typography.titleSmall)
                    }
                    Switch(
                        checked = filter.enabled,
                        onCheckedChange = { viewModel.updateColorFilter(filter.copy(enabled = it)) },
                    )
                }

                if (filter.enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter.eInkGrayscale,
                            onClick = { viewModel.updateColorFilter(filter.copy(eInkGrayscale = !filter.eInkGrayscale)) },
                            label = { Text("E-Ink High-Contrast") },
                        )
                        FilterChip(
                            selected = filter.invertColors,
                            onClick = { viewModel.updateColorFilter(filter.copy(invertColors = !filter.invertColors)) },
                            label = { Text("Invert") },
                        )
                    }
                    Text("Warm Tint", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = filter.warmTintStrength,
                        onValueChange = { viewModel.updateColorFilter(filter.copy(warmTintStrength = it)) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showChapterDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showChapterDrawer = false },
            shape = MaterialTheme.shapes.large,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text("Quick Chapter Switcher", style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(state.chapters) { _, ch ->
                    Surface(
                        onClick = {
                            showChapterDrawer = false
                            viewModel.loadChapter(seriesId, ch.id, ch.lastPageRead, isLandscapeTablet)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = if (ch.id == state.chapter?.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = ch.name,
                            style = MaterialTheme.typography.titleSmall.tabular(),
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        }
    }
}
