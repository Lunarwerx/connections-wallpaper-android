package com.anthonyla.paperize.presentation.screens.wallpaper_mode_selection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.canPickLiveWallpaper
import com.anthonyla.paperize.presentation.common.components.LiveModeDemo
import com.anthonyla.paperize.presentation.common.components.StaticModeDemo
import com.anthonyla.paperize.presentation.common.theme.AppSpacing

private data class ModeCopy(@StringRes val title: Int, @StringRes val tagline: Int, val features: List<Int>)

private fun copyOf(mode: WallpaperMode) = when (mode) {
    WallpaperMode.STATIC -> ModeCopy(
        R.string.mode_static, R.string.mode_static_tagline,
        listOf(R.string.mode_static_feature_screens, R.string.mode_static_feature_battery)
    )
    WallpaperMode.LIVE -> ModeCopy(
        R.string.mode_live_wallpaper, R.string.mode_live_tagline,
        listOf(
            R.string.mode_live_feature_fade, R.string.mode_live_feature_glide,
            R.string.mode_live_feature_double_tap, R.string.mode_live_feature_often
        )
    )
}

/** Every wallpaper mode as a card that shows how it looks and what it is good at; one is [selected]. */
@Composable
fun WallpaperModeOptions(
    selected: WallpaperMode?,
    animate: Boolean,
    onSelect: (WallpaperMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val liveAvailable = remember(context) { canPickLiveWallpaper(context) }
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        WallpaperMode.entries.forEach { mode ->
            val available = mode != WallpaperMode.LIVE || liveAvailable
            ModeOption(mode, mode == selected, animate, enabled, available) { onSelect(mode) }
        }
    }
}

@Composable
private fun ModeOption(
    mode: WallpaperMode,
    selected: Boolean,
    animate: Boolean,
    enabled: Boolean,
    available: Boolean,
    onClick: () -> Unit
) {
    val copy = copyOf(mode)
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled && available,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton }
    ) {
        Row(Modifier.padding(AppSpacing.medium), horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
            when (mode) {
                WallpaperMode.STATIC -> StaticModeDemo(animate)
                WallpaperMode.LIVE -> LiveModeDemo(animate)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(copy.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    RadioButton(selected = selected, onClick = null, enabled = enabled && available)
                }
                Text(
                    stringResource(copy.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!available) {
                    Text(
                        stringResource(R.string.mode_live_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(AppSpacing.extraSmall))
                copy.features.forEach { feature ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(AppSpacing.extraSmall))
                        Text(stringResource(feature), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
