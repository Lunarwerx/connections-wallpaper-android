package com.anthonyla.paperize.presentation.screens.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anthonyla.paperize.R
import com.anthonyla.paperize.connections.AccountButton
import com.anthonyla.paperize.connections.AccountState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    title: String,
    accountState: AccountState,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            AnimatedContent(targetState = title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "home-title") {
                Text(it)
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_screen)
                )
            }
            AccountButton(state = accountState, onClick = onAccountClick)
        }
    )
}
