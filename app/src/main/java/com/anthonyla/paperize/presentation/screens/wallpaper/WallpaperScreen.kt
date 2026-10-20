package com.anthonyla.paperize.presentation.screens.wallpaper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.presentation.common.components.SectionLabel
import com.anthonyla.paperize.presentation.common.components.SettingsGroup
import com.anthonyla.paperize.presentation.common.components.SettingsRow
import com.anthonyla.paperize.presentation.common.components.ScrollingDemo
import com.anthonyla.paperize.presentation.common.components.SettingsRowTextStart
import com.anthonyla.paperize.presentation.common.components.SwitchRow
import com.anthonyla.paperize.presentation.common.theme.AppLayout
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import com.anthonyla.paperize.presentation.screens.wallpaper.components.AlbumSelectionBottomSheet
import com.anthonyla.paperize.presentation.screens.wallpaper.components.CurrentWallpaperPreview
import com.anthonyla.paperize.presentation.screens.wallpaper.components.ScalingPicker
import com.anthonyla.paperize.presentation.screens.wallpaper.components.SettingSwitchWithSlider
import com.anthonyla.paperize.presentation.screens.wallpaper.components.TimeIntervalPicker

/**
 * Settings are grouped rows at Android's list height (one rounded block per section), not a card per
 * switch: which screens rotate, their albums, when the wallpaper changes, then how it looks.
 */
@Composable
fun WallpaperScreen(
    albums: List<AlbumSummary>,
    persistedScheduleSettings: ScheduleSettings,
    appSettings: AppSettings,
    wallpaperMode: WallpaperMode,
    onToggleChanger: (Boolean) -> Unit,
    onSelectAlbum: (ScreenType, AlbumSummary?) -> Unit,
    onUpdateScheduleSettings: (ScheduleSettings) -> Unit,
    onUpdateScheduleSettingsDebounced: (ScheduleSettings) -> Unit,
    onChangeWallpaperNow: () -> Unit,
    onAddFolder: (ScreenType) -> Unit,
    onOpenVisualEffects: () -> Unit,
    homeWallpaperUri: String?,
    lockWallpaperUri: String?,
    liveWallpaperUri: String?,
    modifier: Modifier = Modifier,
    /** Shown just above Change wallpaper now, which stays the last thing on the page. */
    sponsored: (@Composable () -> Unit)? = null
) {
    var albumPickerScreen by rememberSaveable { mutableStateOf<ScreenType?>(null) }
    var showEmptyAlbumWarning by rememberSaveable { mutableStateOf(false) }
    val draft = rememberScheduleDraft(persistedScheduleSettings, onUpdateScheduleSettings, onUpdateScheduleSettingsDebounced)
    val scheduleSettings = draft.value

    fun updateSettingsDebounced(newSettings: ScheduleSettings) = draft.updateDebounced(newSettings)

    fun updateSettingsImmediate(newSettings: ScheduleSettings) = draft.update(newSettings)

    val homeEnabled = scheduleSettings.homeEnabled
    val lockEnabled = scheduleSettings.lockEnabled

    val selectedScaling = if (wallpaperMode == WallpaperMode.LIVE) scheduleSettings.liveScalingType else scheduleSettings.homeScalingType
    val hasAlbumSelected = scheduleSettings.activeScreens(wallpaperMode).isNotEmpty()
    val allRequiredAlbumsSelected = scheduleSettings.hasRequiredAlbums(wallpaperMode)
    // Nothing changes on its own while the changer is off, so when and how often show only while it is on.
    val changing = allRequiredAlbumsSelected && scheduleSettings.enableChanger

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // The whole width scrolls; the cards keep a readable column in a wide window.
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = AppLayout.contentMaxWidth)
            .padding(start = AppSpacing.medium, end = AppSpacing.medium, top = 4.dp, bottom = AppSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Which screens rotate, and from which album.
        if (wallpaperMode == WallpaperMode.STATIC) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScreenToggleCard(
                    title = stringResource(R.string.lock), icon = Icons.Default.Lock, enabled = lockEnabled,
                    onClick = { updateSettingsImmediate(scheduleSettings.copy(lockEnabled = !lockEnabled)) },
                    modifier = Modifier.weight(1f)
                )
                ScreenToggleCard(
                    title = stringResource(R.string.home), icon = Icons.Default.Home, enabled = homeEnabled,
                    onClick = { updateSettingsImmediate(scheduleSettings.copy(homeEnabled = !homeEnabled)) },
                    modifier = Modifier.weight(1f)
                )
            }
            val albumRows = mutableListOf<@Composable () -> Unit>()
            if (lockEnabled) albumRows.add {
                AlbumRow(
                    icon = Icons.Outlined.Lock, label = stringResource(R.string.lock_album_label),
                    albumId = scheduleSettings.lockAlbumId, albums = albums,
                    onClick = { albumPickerScreen = ScreenType.LOCK }
                )
            }
            if (homeEnabled) albumRows.add {
                AlbumRow(
                    icon = Icons.Outlined.Home, label = stringResource(R.string.home_album_label),
                    albumId = scheduleSettings.homeAlbumId, albums = albums,
                    onClick = { albumPickerScreen = ScreenType.HOME }
                )
            }
            if (albumRows.isNotEmpty()) SettingsGroup(*albumRows.toTypedArray())
        } else {
            SettingsGroup({
                AlbumRow(
                    icon = Icons.Outlined.PhotoLibrary, label = stringResource(R.string.currently_selected_album),
                    albumId = scheduleSettings.liveAlbumId, albums = albums,
                    onClick = { albumPickerScreen = ScreenType.LIVE }
                )
            })
        }

        // When it changes.
        val changeRows = mutableListOf<@Composable () -> Unit>()
        if (allRequiredAlbumsSelected) changeRows.add {
            SwitchRow(
                icon = Icons.Outlined.Autorenew,
                title = stringResource(R.string.wallpaper_changer),
                checked = scheduleSettings.enableChanger,
                onCheckedChange = onToggleChanger
            )
        }
        if (changing) {
            changeRows.add {
                SwitchRow(
                    icon = Icons.Outlined.LockOpen,
                    title = stringResource(R.string.change_on_unlock),
                    checked = scheduleSettings.changeOnUnlock,
                    onCheckedChange = { enabled -> updateSettingsImmediate(scheduleSettings.copy(changeOnUnlock = enabled)) }
                )
            }
            changeRows.add {
                SwitchRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.change_on_timer),
                    checked = scheduleSettings.intervalEnabled,
                    onCheckedChange = { enabled -> updateSettingsImmediate(scheduleSettings.copy(intervalEnabled = enabled)) }
                )
            }
            if (scheduleSettings.intervalEnabled) {
                if (wallpaperMode == WallpaperMode.STATIC && homeEnabled && lockEnabled) {
                    changeRows.add {
                        SwitchRow(
                            icon = Icons.Outlined.Tune,
                            title = stringResource(R.string.individual_scheduling),
                            checked = scheduleSettings.separateSchedules,
                            onCheckedChange = { enabled ->
                                updateSettingsImmediate(scheduleSettings.copy(separateSchedules = enabled))
                            }
                        )
                    }
                }
                changeRows.add {
                    Column(Modifier.padding(top = AppSpacing.small)) {
                        if (wallpaperMode == WallpaperMode.LIVE) {
                            TimeIntervalPicker(
                                title = stringResource(R.string.interval_text),
                                minutes = scheduleSettings.liveIntervalMinutes,
                                minimumMinutes = Constants.MIN_LIVE_INTERVAL_MINUTES,
                                onMinutesChange = { minutes ->
                                    updateSettingsImmediate(scheduleSettings.copy(liveIntervalMinutes = minutes))
                                }
                            )
                            Text(
                                text = stringResource(R.string.live_short_interval_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = SettingsRowTextStart, end = AppSpacing.medium, bottom = AppSpacing.small)
                            )
                        } else if (!scheduleSettings.appliesSeparateSchedules) {
                            TimeIntervalPicker(
                                title = stringResource(R.string.interval_text),
                                minutes = scheduleSettings.homeIntervalMinutes,
                                onMinutesChange = { minutes ->
                                    updateSettingsImmediate(
                                        scheduleSettings.copy(homeIntervalMinutes = minutes, lockIntervalMinutes = minutes)
                                    )
                                }
                            )
                        } else {
                            TimeIntervalPicker(
                                title = stringResource(R.string.lock_screen_btn),
                                minutes = scheduleSettings.lockIntervalMinutes,
                                onMinutesChange = { minutes ->
                                    updateSettingsImmediate(scheduleSettings.copy(lockIntervalMinutes = minutes))
                                }
                            )
                            TimeIntervalPicker(
                                title = stringResource(R.string.home_screen_btn),
                                minutes = scheduleSettings.homeIntervalMinutes,
                                onMinutesChange = { minutes ->
                                    updateSettingsImmediate(scheduleSettings.copy(homeIntervalMinutes = minutes))
                                }
                            )
                        }
                    }
                }
            }
        }
        if (changeRows.isNotEmpty()) SettingsGroup(*changeRows.toTypedArray())

        if (wallpaperMode == WallpaperMode.STATIC) {
            // The phone beside the switch swipes its home pages, so the switch shows what it does.
            // The launcher slides only a filled picture; any other scaling sets one that fits the screen exactly.
            // Only the home screen has pages to swipe, so the switch shows while Home rotates an album; its value is kept.
            val slides = scheduleSettings.homeScrollingEnabled && scheduleSettings.scalingType(ScreenType.HOME) == ScalingType.FILL
            if (homeEnabled && scheduleSettings.homeAlbumId != null) SettingsGroup({
                Column {
                    SwitchRow(
                        icon = Icons.Outlined.SwapHoriz,
                        title = stringResource(R.string.horizontal_wallpaper_scrolling),
                        supporting = stringResource(
                            when {
                                slides -> R.string.horizontal_scrolling_on
                                scheduleSettings.homeScrollingEnabled -> R.string.horizontal_scrolling_fill_only
                                else -> R.string.horizontal_scrolling_off
                            }
                        ),
                        checked = scheduleSettings.homeScrollingEnabled,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(scheduleSettings.copy(homeScrollingEnabled = enabled))
                        }
                    )
                    ScrollingDemo(
                        scrolling = slides,
                        animate = appSettings.animate,
                        modifier = Modifier.padding(start = SettingsRowTextStart, bottom = AppSpacing.medium)
                    )
                }
            })
            CurrentWallpaperPreview(
                homeWallpaperUri = homeWallpaperUri,
                lockWallpaperUri = lockWallpaperUri,
                animate = appSettings.animate
            )
        }

        // How it looks. Visual effects are one switch here; their sliders and a preview are a page away.
        // It all shapes the pictures an album sets, so it shows once one is picked.
        val lookRows = mutableListOf<@Composable () -> Unit>()
        lookRows.add {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(AppSpacing.medium))
                    Text(
                        text = stringResource(R.string.scaling),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Each choice drawn with the wallpaper showing now, as the lock screen shows it.
                ScalingPicker(
                    selected = selectedScaling,
                    wallpaperUri = when {
                        wallpaperMode == WallpaperMode.LIVE -> liveWallpaperUri
                        lockEnabled -> lockWallpaperUri ?: homeWallpaperUri
                        else -> homeWallpaperUri ?: lockWallpaperUri
                    },
                    animate = appSettings.animate,
                    onSelect = { scalingType ->
                        updateSettingsImmediate(
                            if (wallpaperMode == WallpaperMode.LIVE) {
                                scheduleSettings.copy(liveScalingType = scalingType)
                            } else {
                                scheduleSettings.copy(homeScalingType = scalingType, lockScalingType = scalingType)
                            }
                        )
                    }
                )
            }
        }
        lookRows.add {
            SwitchRow(
                icon = Icons.Outlined.Shuffle,
                title = stringResource(R.string.shuffle),
                checked = scheduleSettings.shuffleEnabled,
                onCheckedChange = { enabled -> updateSettingsImmediate(scheduleSettings.copy(shuffleEnabled = enabled)) }
            )
        }
        lookRows.add {
            SwitchRow(
                icon = Icons.Outlined.PhotoFilter,
                title = stringResource(R.string.visual_effects),
                checked = scheduleSettings.visualEffectsEnabled,
                onCheckedChange = { enabled -> updateSettingsImmediate(scheduleSettings.copy(visualEffectsEnabled = enabled)) }
            )
        }
        if (scheduleSettings.visualEffectsEnabled) lookRows.add {
            val chosen = chosenEffects(scheduleSettings, wallpaperMode).map { stringResource(it) }
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.adjust_visual_effects),
                onClick = onOpenVisualEffects,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (chosen.isEmpty()) stringResource(R.string.none) else chosen.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
        if (hasAlbumSelected) {
            SectionLabel(stringResource(R.string.wallpaper_effects_title))
            SettingsGroup(*lookRows.toTypedArray())
        }

        if (wallpaperMode == WallpaperMode.LIVE && hasAlbumSelected) {
            SectionLabel(stringResource(R.string.interactive_effects))
            SettingsGroup(
                {
                    SwitchRow(
                        icon = Icons.Outlined.TouchApp,
                        title = stringResource(R.string.double_tap_to_change),
                        checked = scheduleSettings.liveEffects.enableDoubleTap,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(liveEffects = scheduleSettings.liveEffects.copy(enableDoubleTap = enabled))
                            )
                        }
                    )
                },
                {
                    SettingSwitchWithSlider(
                        icon = Icons.Outlined.Layers,
                        title = R.string.parallax_effect,
                        description = R.string.wallpaper_moves_with_screen_scroll,
                        checked = scheduleSettings.liveEffects.enableParallax,
                        onCheckedChange = { enabled ->
                            updateSettingsImmediate(
                                scheduleSettings.copy(liveEffects = scheduleSettings.liveEffects.copy(enableParallax = enabled))
                            )
                        },
                        bothEnabled = false, // Never separate in Live Mode
                        homePercentage = scheduleSettings.liveEffects.parallaxIntensity,
                        lockPercentage = 0,
                        onPercentageChange = { homePercent, _ ->
                            updateSettingsDebounced(
                                scheduleSettings.copy(liveEffects = scheduleSettings.liveEffects.copy(parallaxIntensity = homePercent))
                            )
                        }
                    )
                }
            )
        }

        // Last on the page: what is sponsored, then the one action that acts at once.
        sponsored?.invoke()
        if (hasAlbumSelected) {
            Button(onClick = onChangeWallpaperNow, modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.small)) {
                Text(text = stringResource(R.string.change_wallpaper_now))
            }
        }
    }

    albumPickerScreen?.let { selection ->
        val selectedId = when (selection) {
            ScreenType.LOCK -> scheduleSettings.lockAlbumId
            ScreenType.LIVE -> scheduleSettings.liveAlbumId
            else -> scheduleSettings.homeAlbumId
        }
        AlbumSelectionBottomSheet(
            albums = albums,
            selectedAlbumId = selectedId,
            onAlbumSelect = { album ->
                when {
                    album.id == selectedId -> onSelectAlbum(selection, null)
                    album.wallpaperCount == 0 -> showEmptyAlbumWarning = true
                    else -> onSelectAlbum(selection, album)
                }
            },
            onAddFolder = {
                albumPickerScreen = null
                onAddFolder(selection)
            },
            onDismiss = { albumPickerScreen = null }
        )
    }

    if (showEmptyAlbumWarning) {
        AlertDialog(
            onDismissRequest = { showEmptyAlbumWarning = false },
            title = {
                Text(
                    text = stringResource(R.string.empty_album),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.empty_album_message),
                    maxLines = Constants.DIALOG_MESSAGE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = { showEmptyAlbumWarning = false }) {
                    Text(
                        text = stringResource(R.string.ok),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }
}

/** A screen to rotate: tinted when on, and says so in words too. */
@Composable
private fun ScreenToggleCard(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (enabled) R.string.enabled else R.string.disabled),
                    style = MaterialTheme.typography.bodySmall, color = contentColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The screen's album on one line: what it is on the left, the album on the right, opening the picker sheet. */
@Composable
private fun AlbumRow(icon: ImageVector, label: String, albumId: String?, albums: List<AlbumSummary>, onClick: () -> Unit) {
    val album = albums.find { it.id == albumId }
    SettingsRow(
        icon = icon,
        title = label,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = album?.name ?: stringResource(if (albumId == null) R.string.no_album_selected else R.string.loading_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (albumId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
