package tube.maniac.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import tube.maniac.core.designsystem.theme.LocalIsDarkSurface
import tube.maniac.core.designsystem.theme.LocalMatteTextureEnabled
import tube.maniac.core.designsystem.theme.kicker
import tube.maniac.core.designsystem.theme.tabular

/**
 * Generates an ultra-lightweight, deterministic procedural paper-grain ImageBitmap (96x96)
 * once per process, giving every matte card and canvas subtle tactile print grain without APK asset bloat.
 */
private object MatteGrainCache {
    private const val TILE_SIZE = 96

    val lightGrainBitmap: ImageBitmap by lazy { buildGrainBitmap(isDark = false) }
    val darkGrainBitmap: ImageBitmap by lazy { buildGrainBitmap(isDark = true) }

    private fun buildGrainBitmap(isDark: Boolean): ImageBitmap {
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        var seed = if (isDark) 0x5A3E9127L else 0x1B7C4D33L
        for (i in pixels.indices) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val luma = ((seed ushr 24) and 0xFFL).toInt()
            val alpha = when {
                luma < 90 -> if (isDark) 11 else 9
                luma > 180 -> if (isDark) 8 else 11
                else -> 0
            }
            val rgb = if (luma > 128) Color.White else Color.Black
            pixels[i] = rgb.copy(alpha = alpha / 255f).toArgb()
        }
        return android.graphics.Bitmap.createBitmap(
            pixels,
            TILE_SIZE,
            TILE_SIZE,
            android.graphics.Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }
}

/**
 * Draws a subtle matte micro-grain shader overlay over composables when enabled in Appearance settings.
 */
@Composable
fun Modifier.matteGrain(
    enabled: Boolean = LocalMatteTextureEnabled.current,
    strength: Float = 1f,
): Modifier {
    val isDark = LocalIsDarkSurface.current
    if (!enabled) return this
    val bmp = if (isDark) MatteGrainCache.darkGrainBitmap else MatteGrainCache.lightGrainBitmap
    return drawWithCache {
        val brush = ShaderBrush(
            ImageShader(
                image = bmp,
                tileModeX = TileMode.Repeated,
                tileModeY = TileMode.Repeated,
            ),
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush, alpha = strength.coerceIn(0f, 1.5f))
        }
    }
}

/** Full-screen matte archival backdrop container. */
@Composable
fun MatteBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .matteGrain(strength = 0.85f),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        content()
    }
}

/** Flat editorial matte card with hairline 1.dp border and optional click/long-click handling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatteCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    containerColor: Color = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    },
    borderColor: Color = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    },
    cornerRadius: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactiveMod = when {
        onLongClick != null || onClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
        else -> Modifier
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .then(interactiveMod),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.matteGrain(strength = 0.95f), content = content)
    }
}

/** Consistent top bar across every screen with matte surface and hairline bottom divider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TubiacTopBar(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (!kicker.isNullOrBlank()) {
                        Text(
                            text = kicker.uppercase(),
                            style = MaterialTheme.typography.labelSmall.kicker,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = title,
                        style = if (!kicker.isNullOrBlank()) {
                            MaterialTheme.typography.headlineMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = actions,
        )
        HorizontalDivider(
            thickness = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
    }
}

/** Consistent editorial segmented switcher framed with hairline matte styling. */
@Composable
fun MatteSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onSelect(index) },
                    shape = RoundedCornerShape(7.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                    } else {
                        null
                    },
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Crisp settings navigation row used across categorized setting index screens. */
@Composable
fun MatteNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    MatteCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!badge.isNullOrBlank()) {
                TabularBadge(
                    text = badge,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lightweight Compose-drawn Tubiac emblem matching res/drawable/ic_launcher_foreground.xml. */
@Composable
fun TubiacAppMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF111216),
        border = BorderStroke(1.dp, Color(0xFF2E323C)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = this.size.width / 108f
            val sy = this.size.height / 108f

            val topBar = Path().apply {
                moveTo(28f * sx, 30f * sy)
                lineTo(80f * sx, 30f * sy)
                lineTo(74f * sx, 43f * sy)
                lineTo(28f * sx, 43f * sy)
                close()
            }
            drawPath(topBar, color = Color(0xFFD9483B))

            val stem = Path().apply {
                moveTo(47f * sx, 47f * sy)
                lineTo(61f * sx, 47f * sy)
                lineTo(61f * sx, 78f * sy)
                lineTo(47f * sx, 78f * sy)
                close()
            }
            drawPath(stem, color = Color(0xFFFAF8F5))

            val fold = Path().apply {
                moveTo(74f * sx, 43f * sy)
                lineTo(80f * sx, 30f * sy)
                lineTo(80f * sx, 43f * sy)
                close()
            }
            drawPath(fold, color = Color(0xFFE09F3E))
        }
    }
}

@Composable
fun TubiacSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!kicker.isNullOrBlank()) {
                Text(
                    text = kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall.kicker,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesCoverCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    unreadCount: Int = 0,
    downloadedCount: Int = 0,
    showUnreadBadge: Boolean = true,
    showDownloadBadge: Boolean = true,
    selected: Boolean = false,
    progressFraction: Float? = null,
    coverHeaders: Map<String, String> = emptyMap(),
) {
    val shape = MaterialTheme.shapes.medium // 12.dp matte archival card
    Surface(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .matteGrain(strength = 0.6f),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                val safeCoverUrl = if (coverUrl.startsWith("//")) "https:$coverUrl" else coverUrl
                val context = LocalContext.current
                val model: Any = if (coverHeaders.isEmpty()) {
                    safeCoverUrl
                } else {
                    val nh = NetworkHeaders.Builder().apply {
                        coverHeaders.forEach { (k, v) -> set(k, v) }
                    }.build()
                    ImageRequest.Builder(context).data(safeCoverUrl).httpHeaders(nh).build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showUnreadBadge && unreadCount > 0) {
                    TabularBadge(
                        text = unreadCount.toString(),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (showDownloadBadge && downloadedCount > 0) {
                    TabularBadge(
                        text = downloadedCount.toString(),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = Icons.Rounded.DownloadDone,
                    )
                }
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.86f),
                            ),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFF5F2EC),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = Color(0xFFD2CBC0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (progressFraction != null) {
                        LinearProgressIndicator(
                            progress = { progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TabularBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(11.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.tabular(),
            )
        }
    }
}

@Composable
fun ExpressiveEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier
                .size(58.dp)
                .matteGrain(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Replaces raw exception strings with direct, plain recovery actions inside a matte editorial card.
 */
@Composable
fun ErrorRecoveryCard(
    title: String,
    reason: String,
    onRetry: () -> Unit,
    onOpenWebView: () -> Unit,
    onMigrateSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MatteCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onRetry, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Retry")
                }
                OutlinedButton(onClick = onOpenWebView, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("WebView")
                }
                OutlinedButton(onClick = onMigrateSource, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Migrate")
                }
            }
        }
    }
}
