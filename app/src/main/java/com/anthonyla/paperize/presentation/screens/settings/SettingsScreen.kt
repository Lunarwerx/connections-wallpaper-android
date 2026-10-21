package com.anthonyla.paperize.presentation.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.anthonyla.paperize.core.util.openLiveWallpaperPicker
import com.anthonyla.paperize.presentation.common.components.SectionLabel
import com.anthonyla.paperize.presentation.common.components.SettingsGroup
import com.anthonyla.paperize.presentation.common.components.SettingsRow
import com.anthonyla.paperize.presentation.common.components.SwitchRow
import com.anthonyla.paperize.presentation.common.components.mouseScrollbar
import com.anthonyla.paperize.presentation.common.theme.AppLayout
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import com.anthonyla.paperize.presentation.screens.wallpaper_mode_selection.WallpaperModeOptions
import com.anthonyla.paperize.service.unlock.UnlockChangeService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val wallpaperMode by viewModel.wallpaperMode.collectAsStateWithLifecycle()
    val rotating by viewModel.rotating.collectAsStateWithLifecycle()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showModeSheet by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    var unlockNoticeShown by remember { mutableStateOf(UnlockChangeService.noticeShown(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        unlockNoticeShown = UnlockChangeService.noticeShown(context)
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
        val scroll = rememberScrollState()
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .mouseScrollbar(scroll.scrollIndicatorState)
                .verticalScroll(scroll)
                // The whole width scrolls; the rows keep a readable column in a wide window.
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = AppLayout.contentMaxWidth)
                .padding(horizontal = AppSpacing.medium)
        ) {
            failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // A restricted battery delays every scheduled change, so while one is scheduled it is the one
            // row that wears a problem colour and the one filled button on the page. With nothing
            // scheduled it delays nothing, so it is a plain row that still opens the setting.
            val batteryWarning = rotating && !isIgnoringBatteryOptimizations
            SectionLabel(stringResource(R.string.settings_section_wallpaper))
            val wallpaperRows = mutableListOf<@Composable () -> Unit>(
                {
                    SettingsRow(
                        icon = if (isIgnoringBatteryOptimizations) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                        iconTint = if (batteryWarning) MaterialTheme.colorScheme.error else null,
                        title = stringResource(R.string.battery_optimization),
                        supporting = stringResource(if (isIgnoringBatteryOptimizations) R.string.ignoring else R.string.optimizing),
                        supportingColor = if (batteryWarning) MaterialTheme.colorScheme.error else null,
                        onClick = if (isIgnoringBatteryOptimizations) null else ({ requestIgnoreBatteryOptimizations(context) }),
                        trailing = if (!batteryWarning) null else ({
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
                        onClick = if (isBusy) null else ({ showModeSheet = true })
                    )
                }
            )
            // Change on unlock is static mode's. Android shows its running notice, and only Android's page hides it.
            if (wallpaperMode == WallpaperMode.STATIC) wallpaperRows.add {
                SettingsRow(
                    icon = if (unlockNoticeShown) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                    title = stringResource(R.string.unlock_notice),
                    supporting = stringResource(if (unlockNoticeShown) R.string.notice_shown else R.string.notice_hidden),
                    onClick = { context.startActivity(UnlockChangeService.noticeSettings(context)) },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
            SettingsGroup(*wallpaperRows.toTypedArray())

            SectionLabel(stringResource(R.string.appearance))
            SettingsGroup(
                {
                    SettingsRow(
                        icon = Icons.Outlined.DarkMode,
                        title = stringResource(R.string.theme),
                        supporting = stringResource(themeLabel(appSettings?.darkMode)),
                        onClick = { showThemeDialog = true }
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
                        supporting = stringResource(R.string.animation_desc),
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
                        onClick = if (isBusy) null else ({ showResetDialog = true })
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

        // Follow the phone by default; Light or Dark pins the app's theme.
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text(stringResource(R.string.theme)) },
                text = {
                    Column(Modifier.selectableGroup()) {
                        listOf(null, false, true).forEach { dark ->
                            val selected = appSettings?.darkMode == dark
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        viewModel.updateDarkMode(dark)
                                        showThemeDialog = false
                                    })
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(AppSpacing.medium))
                                Text(stringResource(themeLabel(dark)), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Every mode with what it looks like; picking one switches at once and keeps albums and settings.
        if (showModeSheet) {
            ModalBottomSheet(onDismissRequest = { showModeSheet = false }) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.medium)
                        .padding(bottom = AppSpacing.large)
                ) {
                    Text(stringResource(R.string.wallpaper_mode_setting), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(AppSpacing.medium))
                    WallpaperModeOptions(
                        selected = wallpaperMode,
                        animate = appSettings?.animate ?: true,
                        enabled = !isBusy,
                        onSelect = { mode ->
                            showModeSheet = false
                            // Picking the current mode applies it again: Static replaces a live wallpaper left
                            // showing, Live asks for the live wallpaper when it is not set.
                            viewModel.switchWallpaperMode(mode) { needsLivePicker ->
                                if (needsLivePicker) openLiveWallpaperPicker(context)
                            }
                        }
                    )
                    Spacer(Modifier.height(AppSpacing.small))
                    Text(
                        stringResource(R.string.mode_switch_keeps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@StringRes
private fun themeLabel(dark: Boolean?): Int = when (dark) {
    null -> R.string.theme_system
    false -> R.string.theme_light
    true -> R.string.theme_dark
}
