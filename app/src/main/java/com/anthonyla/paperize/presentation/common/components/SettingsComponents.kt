package com.anthonyla.paperize.presentation.common.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.presentation.common.theme.AppSpacing

/** Where a row's text starts: past the 16dp edge, the 24dp icon and the 16dp gap. Content under a row lines up here. */
val SettingsRowTextStart = 56.dp

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = AppSpacing.medium, top = AppSpacing.medium, bottom = 8.dp)
    )
}

/** Rows of one section, joined into one rounded block: large outer corners, small inner ones. */
@Composable
fun SettingsGroup(vararg rows: @Composable () -> Unit) {
    val outer = 24.dp
    val inner = 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            val shape: Shape = RoundedCornerShape(
                topStart = if (index == 0) outer else inner,
                topEnd = if (index == 0) outer else inner,
                bottomStart = if (index == rows.lastIndex) outer else inner,
                bottomEnd = if (index == rows.lastIndex) outer else inner
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) { row() }
        }
    }
}

/** One settings line at Material's list height: 56dp with one line of text, taller only when its text needs it. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    iconTint: Color? = null,
    titleColor: Color? = null,
    supportingColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = AppSpacing.medium, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(AppSpacing.medium))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.small))
            trailing()
        }
    }
}

/** The whole row toggles, as in Android's own settings, not just the switch. */
@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true
) {
    SettingsRow(
        icon = icon,
        title = title,
        supporting = supporting,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
    )
}

@Composable
fun InteractiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedRadius: Dp = 32.dp,
    defaultRadius: Dp = 16.dp,
    elevation: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) pressedRadius else defaultRadius,
        label = "card_corner_radius"
    )

    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(cornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation, pressedElevation = elevation + 2.dp),
        interactionSource = interactionSource,
        content = content
    )
}
