package com.anthonyla.paperize.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.BuildConfig
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.BatteryOptimizationUtil.isIgnoringBatteryOptimizations
import com.anthonyla.paperize.core.util.BatteryOptimizationUtil.requestIgnoreBatteryOptimizations
import com.anthonyla.paperize.presentation.common.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val isResetting by viewModel.isResetting.collectAsStateWithLifecycle()
    val resetFailed by viewModel.resetFailed.collectAsStateWithLifecycle()
    val wallpaperMode by viewModel.wallpaperMode.collectAsStateWithLifecycle()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var pendingMode by rememberSaveable { mutableStateOf<WallpaperMode?>(null) }
    val context = LocalContext.current

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.large)
        ) {
            if (resetFailed) Text(stringResource(R.string.reset_failed), color = MaterialTheme.colorScheme.error)
            if (isResetting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // A restricted battery delays every scheduled change, so it is the one row that wears a
            // problem colour and the one filled button on the page.
            SectionLabel(stringResource(R.string.settings_section_wallpaper))
            SettingsGroup(
                {
                    SettingsRow(
                        icon = if (isIgnoringBatteryOptimizations) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                        iconTint = if (isIgnoringBatteryOptimizations) null else MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.battery_optimization),
                        supporting = stringResource(if (isIgnoringBatteryOptimizations) R.string.ignoring else R.string.optimizing),
                        supportingColor = if (isIgnoringBatteryOptimizations) null else MaterialTheme.colorScheme.error,
                        onClick = if (isIgnoringBatteryOptimizations) null else ({ requestIgnoreBatteryOptimizations(context) }),
                        trailing = if (isIgnoringBatteryOptimizations) null else ({
                            Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                                Text(stringResource(R.string.battery_allow))
                            }
                        })
                    )
                },
                {
                    SettingsRow(
                        icon = Icons.Outlined.Wallpaper,
                        title = stringResource(R.string.wallpaper_mode_setting),
                        supporting = stringResource(
                            when (wallpaperMode) {
                                WallpaperMode.STATIC -> R.string.mode_static
                                WallpaperMode.LIVE -> R.string.mode_live_wallpaper
                            }
                        ),
                        onClick = if (isResetting) null else ({
                            pendingMode = when (wallpaperMode) {
                                WallpaperMode.STATIC -> WallpaperMode.LIVE
                                WallpaperMode.LIVE -> WallpaperMode.STATIC
                            }
                        })
                    )
                }
            )

            SectionLabel(stringResource(R.string.appearance))
            SettingsGroup(
                {
                    SwitchRow(
                        icon = Icons.Outlined.DarkMode,
                        title = stringResource(R.string.dark_mode),
                        checked = appSettings?.darkMode ?: isSystemInDarkTheme(),
                        onCheckedChange = { viewModel.updateDarkMode(it) }
                    )
                },
                {
                    SwitchRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.dynamic_theming),
                        supporting = stringResource(R.string.dynamic_theming_desc),
                        checked = appSettings?.dynamicTheming ?: false,
                        onCheckedChange = { viewModel.updateDynamicTheming(it) }
                    )
                },
                {
                    SwitchRow(
                        icon = Icons.Outlined.Animation,
                        title = stringResource(R.string.animation),
                        checked = appSettings?.animate ?: true,
                        onCheckedChange = { viewModel.updateAnimate(it) }
                    )
                }
            )

            SectionLabel(stringResource(R.string.about))
            SettingsGroup(
                {
                    SettingsRow(
                        icon = Icons.Outlined.PrivacyTip,
                        title = stringResource(R.string.privacy_policy),
                        onClick = onNavigateToPrivacy,
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                },
                {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.app_version),
                        supporting = BuildConfig.VERSION_NAME
                    )
                }
            )

            Spacer(modifier = Modifier.height(AppSpacing.large))
            SettingsGroup(
                {
                    SettingsRow(
                        icon = Icons.Outlined.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.reset_all_data),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = if (isResetting) null else ({ showResetDialog = true })
                    )
                }
            )

            Spacer(modifier = Modifier.height(AppSpacing.extraLarge))
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(stringResource(R.string.reset_to_default)) },
                text = { Text(stringResource(R.string.are_you_sure_you_want_to_reset_all_settings_and_data_to_default)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.resetAllData()
                            showResetDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (pendingMode != null) {
            AlertDialog(
                onDismissRequest = { pendingMode = null },
                title = {
                    val modeName = if (pendingMode == WallpaperMode.LIVE)
                        stringResource(R.string.mode_live_wallpaper)
                    else
                        stringResource(R.string.mode_static)
                    Text(stringResource(R.string.switch_mode_dialog_title, modeName))
                },
                text = {
                    Text(stringResource(R.string.switch_mode_dialog_description))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingMode?.let { viewModel.switchWallpaperMode(it) }
                            pendingMode = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.switch_mode_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMode = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = AppSpacing.large, top = AppSpacing.extraLarge, bottom = AppSpacing.small)
    )
}

/** Rows of one section, joined into one rounded block: large outer corners, small inner ones. */
@Composable
private fun SettingsGroup(vararg rows: @Composable () -> Unit) {
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

@Composable
private fun SettingsRow(
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
            .heightIn(min = 64.dp)
            .padding(horizontal = AppSpacing.large, vertical = AppSpacing.medium)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(AppSpacing.large))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.medium))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null
) {
    SettingsRow(
        icon = icon,
        title = title,
        supporting = supporting,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) }
    )
}
