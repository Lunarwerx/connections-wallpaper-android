package com.anthonyla.paperize.presentation.screens.wallpaper.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.presentation.common.components.SettingsRow
import com.anthonyla.paperize.presentation.common.components.SettingsRowTextStart
import com.anthonyla.paperize.presentation.common.components.SwitchRow
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import kotlin.math.roundToInt

/**
 * Effect control, one row of a settings group: a switch with its slider under it, or independent
 * HOME and LOCK switches and sliders when both screens rotate.
 */
@Composable
fun SettingSwitchWithSlider(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    bothEnabled: Boolean,
    homePercentage: Int,
    lockPercentage: Int,
    onPercentageChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    homeChecked: Boolean = checked,
    lockChecked: Boolean = checked,
    onHomeCheckedChange: (Boolean) -> Unit = onCheckedChange,
    onLockCheckedChange: (Boolean) -> Unit = onCheckedChange
) {
    var homeValue by remember(homePercentage) {
        mutableFloatStateOf(homePercentage.toFloat())
    }
    var lockValue by remember(lockPercentage) {
        mutableFloatStateOf(lockPercentage.toFloat())
    }
    // Controls under the row line up with its title, past the icon.
    val under = Modifier.padding(start = SettingsRowTextStart, end = AppSpacing.medium, bottom = 8.dp)

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        if (bothEnabled) {
            SettingsRow(icon = icon, title = stringResource(title))
            ScreenEffectControl(
                label = R.string.home,
                checked = homeChecked,
                onCheckedChange = onHomeCheckedChange,
                value = homeValue,
                onValueChange = {
                    homeValue = it
                    onPercentageChange(it.roundToInt(), lockValue.roundToInt())
                },
                modifier = under
            )
            ScreenEffectControl(
                label = R.string.lock,
                checked = lockChecked,
                onCheckedChange = onLockCheckedChange,
                value = lockValue,
                onValueChange = {
                    lockValue = it
                    onPercentageChange(homeValue.roundToInt(), it.roundToInt())
                },
                modifier = under
            )
        } else {
            SwitchRow(icon = icon, title = stringResource(title), checked = checked, onCheckedChange = onCheckedChange)
            if (checked) {
                PercentageSlider(
                    label = description,
                    value = homeValue,
                    onValueChange = {
                        homeValue = it
                        onPercentageChange(it.roundToInt(), it.roundToInt())
                    },
                    modifier = under
                )
            }
        }
    }
}

@Composable
private fun ScreenEffectControl(
    @StringRes label: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        if (checked) {
            PercentageSlider(label = label, value = value, onValueChange = onValueChange)
        }
    }
}

@Composable
private fun PercentageSlider(
    @StringRes label: Int,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${value.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = AppSpacing.medium)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = Constants.MIN_EFFECT_PERCENTAGE.toFloat()..
                Constants.MAX_EFFECT_PERCENTAGE.toFloat(),
            steps = Constants.SLIDER_EFFECT_STEPS
        )
    }
}
