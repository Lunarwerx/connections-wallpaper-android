package com.anthonyla.paperize.presentation.screens.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.core.util.applyWallpaperEffects
import com.anthonyla.paperize.core.util.getDeviceScreenSize
import com.anthonyla.paperize.core.util.retrieveBitmap
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.WallpaperEffects
import com.anthonyla.paperize.presentation.common.components.SettingsGroup
import com.anthonyla.paperize.presentation.common.components.SwitchRow
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import com.anthonyla.paperize.presentation.screens.home.HomeViewModel
import com.anthonyla.paperize.presentation.screens.wallpaper.components.SettingSwitchWithSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Visual effects page. It edits the Wallpaper tab's settings, through the same view model. */
@Composable
fun VisualEffectsScreen(onNavigateBack: () -> Unit, viewModel: HomeViewModel) {
    val settings by viewModel.scheduleSettings.collectAsStateWithLifecycle()
    val mode by viewModel.wallpaperMode.collectAsStateWithLifecycle()
    val homeUri by viewModel.currentHomeWallpaperUri.collectAsStateWithLifecycle()
    val lockUri by viewModel.currentLockWallpaperUri.collectAsStateWithLifecycle()
    val liveUri by viewModel.currentLiveWallpaperUri.collectAsStateWithLifecycle()
    // A slider value still waiting on its debounce is saved as the page starts to close (a back
    // gesture pauses it at once), so the Wallpaper tab never builds its next edit on the older copy.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.flushPendingSettings() }
    DisposableEffect(viewModel) { onDispose { viewModel.flushPendingSettings() } }
    val wallpaperMode = mode ?: return
    VisualEffectsContent(
        persistedScheduleSettings = settings,
        wallpaperMode = wallpaperMode,
        homeWallpaperUri = homeUri,
        lockWallpaperUri = lockUri,
        liveWallpaperUri = liveUri,
        onUpdateScheduleSettings = viewModel::updateScheduleSettings,
        onUpdateScheduleSettingsDebounced = viewModel::updateScheduleSettingsDebounced,
        onNavigateBack = {
            viewModel.flushPendingSettings()
            onNavigateBack()
        }
    )
}

/**
 * A preview of each screen that rotates, held in view, over the effect switches and sliders. Each
 * change redraws the preview the way the wallpaper itself is drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualEffectsContent(
    persistedScheduleSettings: ScheduleSettings,
    wallpaperMode: WallpaperMode,
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    liveWallpaperUri: String?,
    onUpdateScheduleSettings: (ScheduleSettings) -> Unit,
    onUpdateScheduleSettingsDebounced: (ScheduleSettings) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val draft = rememberScheduleDraft(persistedScheduleSettings, onUpdateScheduleSettings, onUpdateScheduleSettingsDebounced)
    val settings = draft.value
    val live = wallpaperMode == WallpaperMode.LIVE
    val homeEnabled = settings.homeEnabled
    val lockEnabled = settings.lockEnabled
    val bothEnabled = !live && homeEnabled && lockEnabled
    val primaryEffects = when {
        live -> settings.liveEffects
        homeEnabled -> settings.homeEffects
        else -> settings.lockEffects
    }

    fun updateEffects(
        home: (WallpaperEffects) -> WallpaperEffects,
        lock: (WallpaperEffects) -> WallpaperEffects = home,
        debounced: Boolean = false
    ) {
        val current = draft.value
        val updated = if (live) {
            current.copy(liveEffects = home(current.liveEffects))
        } else {
            current.copy(
                homeEffects = if (current.homeEnabled) home(current.homeEffects) else current.homeEffects,
                lockEffects = if (current.lockEnabled) lock(current.lockEffects) else current.lockEffects
            )
        }
        if (debounced) draft.updateDebounced(updated) else draft.update(updated)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visual_effects)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.large, Alignment.CenterHorizontally)
            ) {
                if (live) {
                    EffectPreview(
                        wallpaperUri = liveWallpaperUri, effects = settings.liveEffects,
                        adaptiveBrightness = settings.adaptiveBrightness, scaling = settings.liveScalingType,
                        description = R.string.preview_wallpaper
                    )
                } else {
                    if (lockEnabled) EffectPreview(
                        wallpaperUri = lockWallpaperUri, effects = settings.lockEffects,
                        adaptiveBrightness = settings.adaptiveBrightness, scaling = settings.lockScalingType,
                        description = R.string.preview_lock_screen, label = R.string.lock, icon = Icons.Outlined.Lock
                    )
                    if (homeEnabled) EffectPreview(
                        wallpaperUri = homeWallpaperUri, effects = settings.homeEffects,
                        adaptiveBrightness = settings.adaptiveBrightness, scaling = settings.homeScalingType,
                        description = R.string.preview_home_screen, label = R.string.home, icon = Icons.Outlined.Home
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.medium, end = AppSpacing.medium, top = AppSpacing.small, bottom = AppSpacing.medium)
            ) {
                val rows = mutableListOf<@Composable () -> Unit>()
                imageEffects.forEach { effect ->
                    rows.add {
                        SettingSwitchWithSlider(
                            icon = effect.icon,
                            title = effect.title,
                            description = effect.description,
                            checked = effect.enabled(primaryEffects),
                            onCheckedChange = { on -> updateEffects({ effect.withEnabled(it, on) }) },
                            homeChecked = effect.enabled(settings.homeEffects),
                            lockChecked = effect.enabled(settings.lockEffects),
                            onHomeCheckedChange = { on ->
                                val current = draft.value
                                draft.update(current.copy(homeEffects = effect.withEnabled(current.homeEffects, on)))
                            },
                            onLockCheckedChange = { on ->
                                val current = draft.value
                                draft.update(current.copy(lockEffects = effect.withEnabled(current.lockEffects, on)))
                            },
                            bothEnabled = bothEnabled,
                            homePercentage = effect.percentage(primaryEffects),
                            lockPercentage = effect.percentage(settings.lockEffects),
                            onPercentageChange = { home, lock ->
                                updateEffects({ effect.withPercentage(it, home) }, { effect.withPercentage(it, lock) }, debounced = true)
                            }
                        )
                    }
                }
                rows.add {
                    SwitchRow(
                        icon = Icons.Outlined.BrightnessAuto,
                        title = stringResource(R.string.adaptive_brightness),
                        supporting = stringResource(R.string.adjust_brightness_based_on_mode),
                        checked = settings.adaptiveBrightness,
                        onCheckedChange = { on -> draft.update(draft.value.copy(adaptiveBrightness = on)) }
                    )
                }
                SettingsGroup(*rows.toTypedArray())
            }
        }
    }
}

/** The wallpaper as it will look: the current picture (a sample until there is one) with [effects] drawn on it. */
@Composable
private fun EffectPreview(
    wallpaperUri: String?,
    effects: WallpaperEffects,
    adaptiveBrightness: Boolean,
    scaling: ScalingType,
    @StringRes description: Int,
    @StringRes label: Int? = null,
    icon: ImageVector? = null
) {
    val context = LocalContext.current
    val frame = remember(context) { PreviewFrame.of(context) }
    val picture by produceState<Bitmap?>(null, wallpaperUri, scaling) {
        value = withContext(Dispatchers.IO) { loadPicture(context, wallpaperUri, frame, scaling) }
    }
    // Each change starts a new drawing and drops the one in progress; the last finished one stays up meanwhile.
    val shown by produceState<Bitmap?>(null, picture, effects, adaptiveBrightness) {
        val source = picture ?: return@produceState
        value = withContext(previewDrawing) {
            // A copy the system cannot make (low memory) skips this redraw; the last one stays up.
            val copy: Bitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext value
            applyWallpaperEffects(context, copy, effects, adaptiveBrightness, frame.blurScale)
        }
    }
    val descriptionText = stringResource(description)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .height(PreviewHeight)
                .aspectRatio(frame.aspectRatio)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics {
                    contentDescription = descriptionText
                    role = Role.Image
                }
        ) {
            shown?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        if (label != null && icon != null) {
            Spacer(Modifier.height(AppSpacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The preview's size in pixels: the phone's shape, narrow enough that each redraw takes a few milliseconds. */
private class PreviewFrame(val width: Int, val height: Int, val blurScale: Float) {
    val aspectRatio: Float get() = width.toFloat() / height

    companion object {
        fun of(context: Context): PreviewFrame {
            val screen = getDeviceScreenSize(context)
            val short = minOf(screen.width, screen.height)
            val long = maxOf(screen.width, screen.height)
            val width = minOf(short, PREVIEW_WIDTH_PX)
            // Blur is a radius in pixels, so a narrower picture needs a smaller one to look the same.
            return PreviewFrame(width, (width.toLong() * long / short).toInt(), width.toFloat() / short)
        }
    }
}

private fun loadPicture(context: Context, uri: String?, frame: PreviewFrame, scaling: ScalingType): Bitmap {
    if (uri != null) {
        runCatchingCancellable { retrieveBitmap(context, uri.toUri(), frame.width, frame.height, scaling) }
            .onFailure { Log.w(TAG, "Could not load the current wallpaper for the effects preview", it) }
            .getOrNull()
            ?.let { return it }
    }
    return samplePicture(frame.width, frame.height)
}

/** Sky, sun and hills: colours for grayscale and brightness, edges for blur, corners for the vignette. */
private fun samplePicture(width: Int, height: Int): Bitmap {
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val w = width.toFloat()
    val h = height.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF1A73E8.toInt(), 0xFFF9AB00.toInt(), 0xFFD93025.toInt()), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = null
    paint.color = 0xFFFFF4D6.toInt()
    canvas.drawCircle(w * 0.5f, h * 0.36f, w * 0.18f, paint)
    paint.color = 0xFF1E8E3E.toInt()
    canvas.drawOval(RectF(-w * 0.3f, h * 0.68f, w * 0.9f, h * 1.2f), paint)
    paint.color = 0xFF0D652D.toInt()
    canvas.drawOval(RectF(w * 0.2f, h * 0.76f, w * 1.4f, h * 1.25f), paint)
    return bitmap
}

/** One adjustable image effect: a switch plus a percentage within [WallpaperEffects]. */
internal class ImageEffect(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val enabled: (WallpaperEffects) -> Boolean,
    val percentage: (WallpaperEffects) -> Int,
    private val setEnabled: (WallpaperEffects, Boolean) -> WallpaperEffects,
    val withPercentage: (WallpaperEffects, Int) -> WallpaperEffects,
    private val startPercentage: Int
) {
    /** An effect switched on at 0% would change nothing you can see, so it starts at a visible amount. */
    fun withEnabled(effects: WallpaperEffects, on: Boolean): WallpaperEffects {
        val switched = setEnabled(effects, on)
        return if (on && percentage(switched) == 0) withPercentage(switched, startPercentage) else switched
    }
}

internal val imageEffects = listOf(
    ImageEffect(
        Icons.Outlined.Brightness6, R.string.change_brightness, R.string.change_the_image_brightness,
        { it.enableDarken }, { it.darkenPercentage },
        { e, v -> e.copy(enableDarken = v) }, { e, v -> e.copy(darkenPercentage = v) }, startPercentage = 30
    ),
    ImageEffect(
        Icons.Outlined.BlurOn, R.string.change_blur, R.string.add_blur_to_the_image,
        { it.enableBlur }, { it.blurPercentage },
        { e, v -> e.copy(enableBlur = v) }, { e, v -> e.copy(blurPercentage = v) }, startPercentage = 40
    ),
    ImageEffect(
        Icons.Outlined.Vignette, R.string.change_vignette, R.string.darken_the_edges_of_the_image,
        { it.enableVignette }, { it.vignettePercentage },
        { e, v -> e.copy(enableVignette = v) }, { e, v -> e.copy(vignettePercentage = v) }, startPercentage = 50
    ),
    ImageEffect(
        Icons.Outlined.FilterBAndW, R.string.gray_filter, R.string.make_the_colors_grayscale,
        { it.enableGrayscale }, { it.grayscalePercentage },
        { e, v -> e.copy(enableGrayscale = v) }, { e, v -> e.copy(grayscalePercentage = v) }, startPercentage = 100
    )
)

/** The visual effects that are on for a screen that rotates, as string resources, for the Adjust row. */
internal fun chosenEffects(settings: ScheduleSettings, mode: WallpaperMode): List<Int> {
    val screens = if (mode == WallpaperMode.LIVE) {
        listOf(settings.liveEffects)
    } else {
        listOfNotNull(settings.lockEffects.takeIf { settings.lockEnabled }, settings.homeEffects.takeIf { settings.homeEnabled })
    }
    return imageEffects.filter { effect -> screens.any(effect.enabled) }.map { it.title } +
        listOfNotNull(R.string.adaptive_brightness.takeIf { settings.adaptiveBrightness })
}

private val PreviewHeight = 240.dp
private const val PREVIEW_WIDTH_PX = 540
private const val TAG = "VisualEffects"

/** One drawing at a time: a superseded one is dropped before it starts instead of queueing on the GPU. */
private val previewDrawing = Dispatchers.Default.limitedParallelism(1)
