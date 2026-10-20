package com.anthonyla.paperize.presentation.screens.wallpaper_mode_selection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.presentation.common.theme.AppIconSizes
import com.anthonyla.paperize.presentation.common.theme.AppSpacing
import com.anthonyla.paperize.presentation.components.OnboardingLayout
import com.anthonyla.paperize.presentation.screens.settings.SettingsViewModel

@Composable
fun WallpaperModeSelectionScreen(
    onModeSelected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    var selectedMode by rememberSaveable { mutableStateOf<WallpaperMode?>(null) }

    OnboardingLayout(
        title = stringResource(R.string.choose_wallpaper_mode),
        modifier = modifier,
        iconContent = {
            Icon(
                imageVector = Icons.Default.Wallpaper,
                contentDescription = null,
                modifier = Modifier.size(AppIconSizes.huge),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        content = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                WallpaperModeOptions(
                    selected = selectedMode,
                    animate = appSettings?.animate ?: true,
                    onSelect = { selectedMode = it },
                    enabled = !isBusy
                )
                Text(
                    text = stringResource(R.string.change_later_in_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    selectedMode?.let { mode ->
                        viewModel.switchWallpaperMode(mode) { onModeSelected() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                enabled = selectedMode != null && !isBusy
            ) {
                Text(
                    text = stringResource(R.string.continue_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = AppSpacing.small)
                )
            }
        }
    )
}
