package tube.maniac.feature.downloads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.MatteCard
import tube.maniac.core.designsystem.components.TubiacTopBar
import tube.maniac.core.designsystem.theme.tabular
import tube.maniac.core.model.DownloadState
import tube.maniac.core.model.DownloadTask
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val queue: StateFlow<List<DownloadTask>> = downloadDao.observeQueue()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resumeQueue() {
        viewModelScope.launch {
            queue.value.filter { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
                .forEach { downloadDao.updateState(it.id, DownloadState.QUEUED) }
            val settings = settingsRepository.currentSettings()
            DownloadWorker.enqueue(context, settings.wifiOnlyDownloads)
        }
    }

    fun pauseTask(id: Long) {
        viewModelScope.launch { downloadDao.updateState(id, DownloadState.PAUSED) }
    }

    fun cancelTask(id: Long) {
        viewModelScope.launch { downloadDao.deleteById(id) }
    }

    fun clearAll() {
        viewModelScope.launch { downloadDao.clearAll() }
    }

    fun moveItem(index: Int, delta: Int) {
        val list = queue.value
        val target = index + delta
        if (target !in list.indices) return
        viewModelScope.launch {
            val a = list[index]
            val b = list[target]
            downloadDao.updateOrder(a.id, target)
            downloadDao.updateOrder(b.id, index)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()

    MatteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TubiacTopBar(
                    kicker = "OFFLINE STORAGE",
                    title = "Download Queue",
                    onBack = onBack,
                    actions = {
                        if (queue.isNotEmpty()) {
                            IconButton(onClick = viewModel::resumeQueue) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Start/Resume queue")
                            }
                            IconButton(onClick = viewModel::clearAll) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear queue")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (queue.isEmpty()) {
                ExpressiveEmptyState(
                    icon = Icons.Rounded.DownloadDone,
                    title = "Queue is clear",
                    message = "Queued offline chapters and Download Ahead tasks will appear here.",
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(queue, key = { _, t -> t.id }) { index, task ->
                        MatteCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(task.seriesTitle, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "${task.chapterName} • ${task.state.name} (${task.progressPercent}%)",
                                            style = MaterialTheme.typography.labelMedium.tabular(),
                                            color = if (task.state == DownloadState.FAILED) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        task.errorMessage?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Row {
                                        IconButton(onClick = { viewModel.moveItem(index, -1) }) {
                                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                                        }
                                        IconButton(onClick = { viewModel.moveItem(index, 1) }) {
                                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                                        }
                                        IconButton(onClick = {
                                            if (task.state == DownloadState.PAUSED) viewModel.resumeQueue()
                                            else viewModel.pauseTask(task.id)
                                        }) {
                                            Icon(
                                                if (task.state == DownloadState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                                contentDescription = "Pause or resume",
                                            )
                                        }
                                        IconButton(onClick = { viewModel.cancelTask(task.id) }) {
                                            Icon(Icons.Rounded.Cancel, contentDescription = "Cancel")
                                        }
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { task.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(MaterialTheme.shapes.extraSmall),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
