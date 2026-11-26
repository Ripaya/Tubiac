package tube.maniac.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object ShelfRoute
@Serializable data object BrowseRoute
@Serializable data object SettingsRoute

@Serializable data object HistoryRoute
@Serializable data object DownloadsRoute
@Serializable data object UpdateErrorsRoute
@Serializable data object GlobalSearchRoute

@Serializable data class SeriesDetailRoute(val seriesId: Long)
@Serializable data class ReaderRoute(val seriesId: Long, val chapterId: Long, val initialPage: Int = 0)
@Serializable data class SourceMigrationRoute(val seriesId: Long)
