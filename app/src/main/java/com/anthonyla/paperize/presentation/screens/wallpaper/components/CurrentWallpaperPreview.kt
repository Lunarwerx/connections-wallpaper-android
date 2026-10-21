package com.anthonyla.paperize.presentation.screens.wallpaper.components
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import kotlin.math.max
import kotlin.math.min


/** Shows the last applied source URIs; WallpaperManager readback is restricted on modern Android.
 * Cropping and effects applied to the actual wallpaper are not included in this preview.
 */
@Composable
fun CurrentWallpaperPreview(
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** Opens the album the lock screen's picture comes from; null when no album is picked. */
    onOpenLock: (() -> Unit)? = null,
    onOpenHome: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current

    // Portrait-oriented preview: shorter screen dimension as width.
    val screenAspectRatio = remember(configuration) {
        val screenWidth = configuration.screenWidthDp.toFloat()
        val screenHeight = configuration.screenHeightDp.toFloat()
        min(screenWidth, screenHeight) / max(screenWidth, screenHeight)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.medium)
        ) {
            Text(
                text = stringResource(R.string.current_wallpapers),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = AppSpacing.small),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                WallpaperPreviewBox(
                    wallpaperUri = lockWallpaperUri,
                    label = stringResource(R.string.lock),
                    icon = Icons.Outlined.Lock,
                    aspectRatio = screenAspectRatio,
                    contentDescription = stringResource(R.string.content_desc_current_lock_wallpaper),
                    animate = animate,
                    onClick = onOpenLock,
                    modifier = Modifier.weight(1f)
                )

                WallpaperPreviewBox(
                    wallpaperUri = homeWallpaperUri,
                    label = stringResource(R.string.home),
                    icon = Icons.Outlined.Home,
                    aspectRatio = screenAspectRatio,
                    contentDescription = stringResource(R.string.content_desc_current_home_wallpaper),
                    animate = animate,
                    onClick = onOpenHome,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WallpaperPreviewBox(
    wallpaperUri: String?,
    label: String,
    icon: ImageVector,
    aspectRatio: Float,
    contentDescription: String,
    animate: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.open_album)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(if (onClick == null) Modifier else Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick))
        ) {
            // A screen this app has not set yet shows its own glyph, not a blank tile.
            if (wallpaperUri == null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(wallpaperUri)
                        .size(Size(Constants.PREVIEW_THUMBNAIL_WIDTH, Constants.PREVIEW_THUMBNAIL_HEIGHT))
                        .crossfade(animate)
                        .build(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.small))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
