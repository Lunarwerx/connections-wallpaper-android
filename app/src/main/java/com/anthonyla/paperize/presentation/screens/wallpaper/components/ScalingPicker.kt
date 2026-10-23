package com.anthonyla.paperize.presentation.screens.wallpaper.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.calculateDecodeSize
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.presentation.common.components.drawDemoPhoto
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val PHONE_SHAPE = RoundedCornerShape(12.dp)

/**
 * Every scaling as a phone showing [wallpaperUri] drawn that way, so the choice shows what it does.
 * Before a wallpaper is set, a drawn landscape photo stands in. A picture already the screen's shape draws the same
 * under Fill, Fit and Stretch, and a line under the phones says so: matching phones otherwise read as a choice that
 * does nothing (owner report, 2026-10-22).
 */
@Composable
fun ScalingPicker(
    selected: ScalingType,
    wallpaperUri: String?,
    animate: Boolean,
    onSelect: (ScalingType) -> Unit,
    /** The other screen's picture when the choice scales it too: the fits-your-screen note must hold for it as well. */
    otherWallpaperUri: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // The panel the wallpaper is drawn for, not this window: a split screen must not change the previews.
    val screen = remember(context, configuration) { getDeviceScreenSize(context) }
    val screenAspect = min(screen.width, screen.height).toFloat() / max(screen.width, screen.height).coerceAtLeast(1)
    // Only Original depends on the picture's own pixel size.
    val original by produceState<IntSize?>(null, wallpaperUri) {
        value = null
        value = wallpaperUri?.let { uri -> withContext(Dispatchers.IO) { pictureSize(context, uri) } }
    }
    // Width over height as the picture shows, read from the drawn thumbnail so a rotated photo counts as it stands.
    var pictureAspect by remember(wallpaperUri) { mutableStateOf<Float?>(null) }
    val otherSize by produceState<IntSize?>(null, otherWallpaperUri) {
        value = null
        value = otherWallpaperUri?.let { uri -> withContext(Dispatchers.IO) { pictureSize(context, uri) } }
    }
    val otherAspect = otherSize?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height }
    fun fits(aspect: Float?) = aspect != null && abs(aspect - screenAspect) <= screenAspect * FITS_SCREEN_TOLERANCE
    val fitsScreen = fits(pictureAspect) && (otherWallpaperUri == null || fits(otherAspect))
    val options = listOf(
        ScalingType.FILL to R.string.fill,
        ScalingType.FIT to R.string.fit,
        ScalingType.STRETCH to R.string.stretch,
        ScalingType.NONE to R.string.scaling_original
    )
    Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        options.forEach { (scaling, label) ->
            val isSelected = scaling == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(scaling) })
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(screenAspect)
                        .clip(PHONE_SHAPE)
                        .background(Color.Black)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = PHONE_SHAPE
                        )
                ) {
                    if (wallpaperUri == null) {
                        Canvas(Modifier.fillMaxSize()) { drawDemoPhoto(scaling) }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(wallpaperUri)
                                .size(coil3.size.Size(Constants.PREVIEW_THUMBNAIL_WIDTH, Constants.PREVIEW_THUMBNAIL_HEIGHT))
                                .crossfade(animate)
                                .build(),
                            contentDescription = null,
                            contentScale = contentScaleFor(scaling, original, screen.width, screen.height),
                            onSuccess = { state ->
                                state.painter.intrinsicSize.takeIf { it.height > 0f }?.let { pictureAspect = it.width / it.height }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.extraSmall))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (fitsScreen) {
        Row(Modifier.padding(top = AppSpacing.small), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.scaling_fits_screen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    }
}

/** How far a picture's shape may sit from the screen's and still draw alike under Fill, Fit and Stretch. */
private const val FITS_SCREEN_TOLERANCE = 0.03f

/** How [scaling] draws [wallpaperUri] on the phone's own panel, for a thumbnail shaped like that panel. */
@Composable
internal fun rememberScalingContentScale(wallpaperUri: String, scaling: ScalingType): ContentScale {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screen = remember(context, configuration) { getDeviceScreenSize(context) }
    val original by produceState<IntSize?>(null, wallpaperUri, scaling) {
        value = null
        if (scaling == ScalingType.NONE) value = withContext(Dispatchers.IO) { pictureSize(context, wallpaperUri) }
    }
    return contentScaleFor(scaling, original, screen.width, screen.height)
}

private fun contentScaleFor(scaling: ScalingType, original: IntSize?, screenWidth: Int, screenHeight: Int): ContentScale =
    when (scaling) {
        ScalingType.FILL -> ContentScale.Crop
        ScalingType.FIT -> ContentScale.Fit
        ScalingType.STRETCH -> ContentScale.FillBounds
        // Until the picture's size is read, Fit stands in rather than the thumbnail's own pixel size.
        ScalingType.NONE -> original?.let { OriginalSize(it, screenWidth, screenHeight) } ?: ContentScale.Fit
    }

/** The picture at the size Original sets on the screen, shrunk by as much as the thumbnail shrinks the screen. */
private class OriginalSize(private val original: IntSize, private val screenWidth: Int, private val screenHeight: Int) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        // The file's bounds ignore its rotation; the decoded picture's orientation is the true one.
        val rotated = (srcSize.width > srcSize.height) != (original.width > original.height)
        val (width, height) = if (rotated) original.height to original.width else original.width to original.height
        val (shownWidth, _) = calculateDecodeSize(width, height, screenWidth, screenHeight, ScalingType.NONE)
        val scale = shownWidth * (dstSize.width / screenWidth) / srcSize.width
        return ScaleFactor(scale, scale)
    }
}

/** The picture's size as it shows: bounds are as stored, and a photo its EXIF orientation turns shows them swapped. */
private fun pictureSize(context: Context, uri: String): IntSize? = try {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it, null, options) }
    val turned = runCatching {
        context.contentResolver.openInputStream(uri.toUri())?.use { ExifInterface(it).rotationDegrees % 180 != 0 }
    }.getOrNull() == true
    when {
        options.outWidth <= 0 || options.outHeight <= 0 -> null
        turned -> IntSize(options.outHeight, options.outWidth)
        else -> IntSize(options.outWidth, options.outHeight)
    }
} catch (_: Exception) {
    null
}
