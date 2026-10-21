package com.anthonyla.paperize.presentation

import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
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
import com.anthonyla.paperize.connections.analytics.ConnectionsAnalytics
import com.anthonyla.paperize.presentation.common.navigation.HomeRoute
import com.anthonyla.paperize.presentation.common.navigation.NavigationGraph
import com.anthonyla.paperize.presentation.common.navigation.StartupRoute
import com.anthonyla.paperize.presentation.common.components.AppShortcuts
import com.anthonyla.paperize.presentation.common.components.LocalAppShortcuts
import com.anthonyla.paperize.presentation.common.components.MouseAware
import com.anthonyla.paperize.presentation.common.theme.PaperizeTheme
import com.anthonyla.paperize.presentation.screens.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var analytics: ConnectionsAnalytics

    private val shortcuts = AppShortcuts()

    // Only keys no focused field or control took reach the activity, so these never steal typing keys.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        shortcuts.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        shortcuts.dispatch(event) || super.onKeyUp(keyCode, event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.add(AppShortcuts.helperGroup(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val accountViewModel: AccountViewModel = hiltViewModel()

            val currentSettings by settingsViewModel.appSettings.collectAsStateWithLifecycle()
            val accountState by accountViewModel.state.collectAsStateWithLifecycle()
            val consent by analytics.consent.collectAsStateWithLifecycle()

            PaperizeTheme(
                darkMode = currentSettings?.darkMode,
                dynamicTheming = currentSettings?.dynamicTheming ?: false
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Wait for the saved notice answer too, so the start screen is picked from both.
                    if (currentSettings != null && consent != null) {
                        // A first launch shows the notice; an install that agreed to an older notice sees it once more.
                        val firstLaunch = currentSettings?.firstLaunch != false
                        val agreed = consent?.agreedCurrent == true
                        CompositionLocalProvider(LocalAppShortcuts provides shortcuts.events) {
                        MouseAware {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavigationGraph(
                                startDestination = if (firstLaunch || !agreed) StartupRoute else HomeRoute,
                                animate = currentSettings?.animate ?: true,
                                onFirstLaunchComplete = {
                                    settingsViewModel.updateFirstLaunch(false)
                                },
                                reAgree = !firstLaunch && !agreed,
                                onPolicyAgreed = { analytics.recordAgreement() },
                                onScreen = { analytics.screen(it) }
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
    }
}
