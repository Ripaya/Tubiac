package tube.maniac

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tube.maniac.core.anilist.AniListAuthHelper
import tube.maniac.core.anilist.AniListGraphQlClient
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.entity.ExtensionRepoEntity
import tube.maniac.core.designsystem.components.ExpressiveEmptyState
import tube.maniac.core.designsystem.components.MatteBackground
import tube.maniac.core.designsystem.components.matteGrain
import tube.maniac.core.designsystem.theme.TubiacTheme
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.extension.loader.LocalSource
import tube.maniac.core.extension.loader.RepoIndexDecoder
import tube.maniac.core.network.NetworkRuntimeConfig
import tube.maniac.feature.browse.BrowseScreen
import tube.maniac.feature.browse.GlobalSearchScreen
import tube.maniac.feature.browse.SourceBrowseScreen
import tube.maniac.feature.browse.SourceMigrationScreen
import tube.maniac.feature.detail.SeriesDetailScreen
import tube.maniac.feature.downloads.DownloadsScreen
import tube.maniac.feature.home.HistoryScreen
import tube.maniac.feature.home.HomeScreen
import tube.maniac.feature.reader.ReaderScreen
import tube.maniac.feature.settings.SettingsAboutScreen
import tube.maniac.feature.settings.SettingsAppearanceScreen
import tube.maniac.feature.settings.SettingsBackupScreen
import tube.maniac.feature.settings.SettingsDownloadsScreen
import tube.maniac.feature.settings.SettingsPrivacyScreen
import tube.maniac.feature.settings.SettingsReaderScreen
import tube.maniac.feature.settings.SettingsScreen
import tube.maniac.feature.settings.SettingsSyncScreen
import tube.maniac.feature.shelf.ShelfScreen
import tube.maniac.feature.shelf.UpdateErrorsScreen
import tube.maniac.navigation.BrowseRoute
import tube.maniac.navigation.DownloadsRoute
import tube.maniac.navigation.GlobalSearchRoute
import tube.maniac.navigation.HistoryRoute
import tube.maniac.navigation.HomeRoute
import tube.maniac.navigation.ReaderRoute
import tube.maniac.navigation.SeriesDetailRoute
import tube.maniac.navigation.SettingsAboutRoute
import tube.maniac.navigation.SettingsAppearanceRoute
import tube.maniac.navigation.SettingsBackupRoute
import tube.maniac.navigation.SettingsDownloadsRoute
import tube.maniac.navigation.SettingsPrivacyRoute
import tube.maniac.navigation.SettingsReaderRoute
import tube.maniac.navigation.SettingsRoute
import tube.maniac.navigation.SettingsSyncRoute
import tube.maniac.navigation.ShelfRoute
import tube.maniac.navigation.SourceBrowseRoute
import tube.maniac.navigation.SourceMigrationRoute
import tube.maniac.navigation.UpdateErrorsRoute
import tube.maniac.ui.OnboardingScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var localSource: LocalSource
    @Inject lateinit var networkConfig: NetworkRuntimeConfig
    @Inject lateinit var aniListClient: AniListGraphQlClient
    @Inject lateinit var extensionRepoDao: ExtensionRepoDao

    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleDeepLinkIntent(intent)

        setContent {
            val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                initialValue = tube.maniac.core.model.AppSettings(),
            )

            // Sync runtime security & networking preferences
            LaunchedEffect(settings.secureScreenEnabled) {
                if (settings.secureScreenEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            LaunchedEffect(settings.dohProvider, settings.customUserAgent, settings.spoofXRequestedWith) {
                networkConfig.dohProvider.set(settings.dohProvider)
                val effectiveUa = if (settings.customUserAgent == tube.maniac.core.model.AppSettings.DEFAULT_USER_AGENT) {
                    runCatching { WebSettings.getDefaultUserAgent(this@MainActivity) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: settings.customUserAgent
                } else {
                    settings.customUserAgent
                }
                networkConfig.userAgent.set(effectiveUa)
                networkConfig.spoofXRequestedWith.set(settings.spoofXRequestedWith)
            }
            LaunchedEffect(settings.autoReportIssuesEnabled, settings.customIssueRelayUrl) {
                AutoIssueReporter.updatePreferences(
                    enabled = settings.autoReportIssuesEnabled,
                    customRelayUrl = settings.customIssueRelayUrl,
                )
                if (settings.autoReportIssuesEnabled && AutoIssueReporter.state.value.pendingCount > 0) {
                    AutoIssueReportWorker.enqueue(this@MainActivity)
                }
            }
            LaunchedEffect(settings.localSourceTreeUri) {
                localSource.setRootTreeUri(settings.localSourceTreeUri)
            }
            LaunchedEffect(settings.biometricLockEnabled) {
                if (!settings.biometricLockEnabled) {
                    unlocked = true
                } else if (!unlocked) {
                    promptBiometricUnlock()
                }
            }

            val localFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                    lifecycleScope.launch {
                        settingsRepository.updateSettings { it.copy(localSourceTreeUri = uri.toString()) }
                    }
                }
            }

            val openAniListOAuth = {
                startActivity(AniListLoginActivity.createIntent(this, settings.anilistClientId))
            }

            TubiacTheme(
                themeMode = settings.themeMode,
                useDynamicColor = settings.useDynamicColor,
                useMatteTexture = settings.useMatteTexture,
                animationSpeed = settings.animationSpeed,
            ) {
                when {
                    settings.biometricLockEnabled && !unlocked -> {
                        MatteBackground {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ExpressiveEmptyState(
                                    icon = Icons.Rounded.Lock,
                                    title = "Tubiac is Locked",
                                    message = "Authenticate with biometrics or device PIN to continue.",
                                    actionLabel = "Unlock",
                                    onAction = ::promptBiometricUnlock,
                                )
                            }
                        }
                    }

                    !settings.onboardingCompleted -> {
                        OnboardingScreen(
                            settings = settings,
                            onUpdateSettings = { transform ->
                                lifecycleScope.launch { settingsRepository.updateSettings(transform) }
                            },
                            onConnectAniList = openAniListOAuth,
                            onPickLocalFolder = { localFolderPicker.launch(null) },
                            onFinish = {
                                lifecycleScope.launch {
                                    settingsRepository.updateSettings { it.copy(onboardingCompleted = true) }
                                }
                            },
                        )
                    }

                    else -> {
                        TubiacMainShell(
                            onOpenWebView = { url -> startActivity(WebViewActivity.createIntent(this, url)) },
                            onOpenAniListLogin = openAniListOAuth,
                            onPickLocalFolder = { localFolderPicker.launch(null) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        when {
            data.startsWith(AniListAuthHelper.OAUTH_CALLBACK_URI) -> {
                val token = AniListAuthHelper.extractAccessTokenFromCallback(data) ?: return
                lifecycleScope.launch {
                    val viewer = aniListClient.fetchViewer(token).getOrNull()
                    settingsRepository.saveAniListAuth(token, viewer)
                }
            }
            data.startsWith("tachiyomi://add-repo", ignoreCase = true) ||
                data.startsWith("mihon://add-repo", ignoreCase = true) -> {
                val repoUrl = RepoIndexDecoder.extractRepoInputUrl(data)
                if (repoUrl.startsWith("http://") || repoUrl.startsWith("https://")) {
                    lifecycleScope.launch {
                        val name = repoUrl.substringAfter("://").substringBefore("/")
                        extensionRepoDao.upsert(
                            ExtensionRepoEntity(
                                name = name,
                                indexUrl = repoUrl,
                                enabled = true,
                                trusted = true,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun promptBiometricUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Tubiac")
            .setSubtitle("Verify your identity to access your Shelf")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { unlocked = true }
    }

    companion object {
        // Users/maintainers configure their registered AniList OAuth2 Client ID for release builds
        private const val ANILIST_CLIENT_ID = "21850"
    }
}

@Composable
private fun TubiacMainShell(
    onOpenWebView: (String) -> Unit,
    onOpenAniListLogin: () -> Unit,
    onPickLocalFolder: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val topLevelRoutes = listOf(HomeRoute, ShelfRoute, BrowseRoute, SettingsRoute)
    val showBottomBar = topLevelRoutes.any { route -> currentDest?.hasRoute(route::class) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(
                        thickness = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        modifier = Modifier.matteGrain(strength = 0.85f),
                    ) {
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(HomeRoute::class) == true,
                            onClick = { navController.navigateTopLevel(HomeRoute) },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                        )
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(ShelfRoute::class) == true,
                            onClick = { navController.navigateTopLevel(ShelfRoute) },
                            icon = { Icon(Icons.Rounded.CollectionsBookmark, contentDescription = "Shelf") },
                            label = { Text("Shelf") },
                        )
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(BrowseRoute::class) == true,
                            onClick = { navController.navigateTopLevel(BrowseRoute) },
                            icon = { Icon(Icons.Rounded.Explore, contentDescription = "Browse") },
                            label = { Text("Browse") },
                        )
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(SettingsRoute::class) == true,
                            onClick = { navController.navigateTopLevel(SettingsRoute) },
                            icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenGlobalSearch = { navController.navigate(GlobalSearchRoute) },
                    onOpenHistory = { navController.navigate(HistoryRoute) },
                    onOpenSeriesDetail = { id -> navController.navigate(SeriesDetailRoute(id)) },
                    onResumeChapter = { seriesId, chapterId, page ->
                        navController.navigate(ReaderRoute(seriesId, chapterId, page))
                    },
                    onOpenBrowse = { navController.navigateTopLevel(BrowseRoute) },
                )
            }

            composable<ShelfRoute> {
                ShelfScreen(
                    onOpenSeriesDetail = { id -> navController.navigate(SeriesDetailRoute(id)) },
                    onOpenUpdateErrors = { navController.navigate(UpdateErrorsRoute) },
                    onOpenBrowse = { navController.navigateTopLevel(BrowseRoute) },
                )
            }

            composable<BrowseRoute> {
                BrowseScreen(
                    onOpenGlobalSearch = { navController.navigate(GlobalSearchRoute) },
                    onOpenSource = { sourceId -> navController.navigate(SourceBrowseRoute(sourceId)) },
                    onOpenWebView = onOpenWebView,
                    onOpenMigration = { seriesId -> navController.navigate(SourceMigrationRoute(seriesId)) },
                )
            }

            composable<SourceBrowseRoute> { entry ->
                val args = entry.toRoute<SourceBrowseRoute>()
                SourceBrowseScreen(
                    sourceId = args.sourceId,
                    onBack = { navController.popBackStack() },
                    onOpenSeriesDetail = { id -> navController.navigate(SeriesDetailRoute(id)) },
                    onOpenWebView = onOpenWebView,
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenAppearance = { navController.navigate(SettingsAppearanceRoute) },
                    onOpenSync = { navController.navigate(SettingsSyncRoute) },
                    onOpenBackup = { navController.navigate(SettingsBackupRoute) },
                    onOpenDownloadsSettings = { navController.navigate(SettingsDownloadsRoute) },
                    onOpenReader = { navController.navigate(SettingsReaderRoute) },
                    onOpenPrivacy = { navController.navigate(SettingsPrivacyRoute) },
                    onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                )
            }

            composable<SettingsAppearanceRoute> {
                SettingsAppearanceScreen(onBack = { navController.popBackStack() })
            }

            composable<SettingsSyncRoute> {
                SettingsSyncScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAniListLogin = onOpenAniListLogin,
                )
            }

            composable<SettingsBackupRoute> {
                SettingsBackupScreen(onBack = { navController.popBackStack() })
            }

            composable<SettingsDownloadsRoute> {
                SettingsDownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDownloadQueue = { navController.navigate(DownloadsRoute) },
                    onPickLocalFolder = onPickLocalFolder,
                )
            }

            composable<SettingsReaderRoute> {
                SettingsReaderScreen(onBack = { navController.popBackStack() })
            }

            composable<SettingsPrivacyRoute> {
                SettingsPrivacyScreen(onBack = { navController.popBackStack() })
            }

            composable<SettingsAboutRoute> {
                SettingsAboutScreen(onBack = { navController.popBackStack() })
            }

            composable<HistoryRoute> {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onResumeChapter = { seriesId, chapterId, page ->
                        navController.navigate(ReaderRoute(seriesId, chapterId, page))
                    },
                )
            }

            composable<DownloadsRoute> {
                DownloadsScreen(onBack = { navController.popBackStack() })
            }

            composable<UpdateErrorsRoute> {
                UpdateErrorsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWebView = onOpenWebView,
                    onMigrateSource = { seriesId -> navController.navigate(SourceMigrationRoute(seriesId)) },
                )
            }

            composable<GlobalSearchRoute> {
                GlobalSearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSeriesDetail = { id -> navController.navigate(SeriesDetailRoute(id)) },
                )
            }

            composable<SeriesDetailRoute> { entry ->
                val args = entry.toRoute<SeriesDetailRoute>()
                SeriesDetailScreen(
                    seriesId = args.seriesId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { sId, cId, page -> navController.navigate(ReaderRoute(sId, cId, page)) },
                    onOpenWebView = onOpenWebView,
                    onOpenMigrate = { sId -> navController.navigate(SourceMigrationRoute(sId)) },
                )
            }

            composable<ReaderRoute> { entry ->
                val args = entry.toRoute<ReaderRoute>()
                ReaderScreen(
                    seriesId = args.seriesId,
                    chapterId = args.chapterId,
                    initialPage = args.initialPage,
                    onBack = { navController.popBackStack() },
                    onOpenWebView = onOpenWebView,
                )
            }

            composable<SourceMigrationRoute> { entry ->
                val args = entry.toRoute<SourceMigrationRoute>()
                SourceMigrationScreen(
                    seriesId = args.seriesId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
