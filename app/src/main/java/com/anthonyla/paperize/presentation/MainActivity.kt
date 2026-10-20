package com.anthonyla.paperize.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyla.paperize.connections.AccountState
import com.anthonyla.paperize.connections.AccountViewModel
import com.anthonyla.paperize.connections.SignInWall
import com.anthonyla.paperize.presentation.common.navigation.HomeRoute
import com.anthonyla.paperize.presentation.common.navigation.NavigationGraph
import com.anthonyla.paperize.presentation.common.navigation.StartupRoute
import com.anthonyla.paperize.presentation.common.theme.PaperizeTheme
import com.anthonyla.paperize.presentation.screens.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val accountViewModel: AccountViewModel = hiltViewModel()

            val currentSettings by settingsViewModel.appSettings.collectAsStateWithLifecycle()
            val accountState by accountViewModel.state.collectAsStateWithLifecycle()

            PaperizeTheme(
                darkMode = currentSettings?.darkMode,
                dynamicTheming = currentSettings?.dynamicTheming ?: false
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (currentSettings != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavigationGraph(
                                startDestination = if (currentSettings?.firstLaunch != false) StartupRoute else HomeRoute,
                                animate = currentSettings?.animate ?: true,
                                onFirstLaunchComplete = {
                                    settingsViewModel.updateFirstLaunch(false)
                                }
                            )
                            // After the free week the screens wait for a free Connections sign-in;
                            // rotation keeps running in the background either way.
                            if (accountState == AccountState.TrialOver && currentSettings?.firstLaunch == false) {
                                SignInWall(viewModel = accountViewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
