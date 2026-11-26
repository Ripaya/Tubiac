package tube.maniac.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tube.maniac.core.database.dao.CategoryDao
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.dao.HistoryDao
import tube.maniac.core.database.dao.PendingMutationDao
import tube.maniac.core.database.dao.SavedSearchDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.dao.TagDao
import tube.maniac.core.database.dao.UpdateErrorDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TubiacDatabase =
        Room.databaseBuilder(
            context,
            TubiacDatabase::class.java,
            TubiacDatabase.DATABASE_NAME,
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideSeriesDao(db: TubiacDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideChapterDao(db: TubiacDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideCategoryDao(db: TubiacDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTagDao(db: TubiacDatabase): TagDao = db.tagDao()
    @Provides fun provideHistoryDao(db: TubiacDatabase): HistoryDao = db.historyDao()
    @Provides fun provideDownloadDao(db: TubiacDatabase): DownloadDao = db.downloadDao()
    @Provides fun provideExtensionRepoDao(db: TubiacDatabase): ExtensionRepoDao = db.extensionRepoDao()
    @Provides fun provideUpdateErrorDao(db: TubiacDatabase): UpdateErrorDao = db.updateErrorDao()
    @Provides fun provideSavedSearchDao(db: TubiacDatabase): SavedSearchDao = db.savedSearchDao()
    @Provides fun providePendingMutationDao(db: TubiacDatabase): PendingMutationDao = db.pendingMutationDao()
}
