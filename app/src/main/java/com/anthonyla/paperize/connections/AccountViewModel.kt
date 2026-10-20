package com.anthonyla.paperize.connections

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.presentation.common.stateWhileSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val account: ConnectionsAccount,
    private val api: ConnectionsApi
) : ViewModel() {

    val state: StateFlow<AccountState> = account.state.stateWhileSubscribed(viewModelScope, AccountState.Loading)
    val signIn: StateFlow<SignInStatus> = account.signIn

    private val _showcase = MutableStateFlow<List<ConnectionsWallpaper>>(emptyList())
    /** A handful of gallery pictures for the sign-in screen. */
    val showcase: StateFlow<List<ConnectionsWallpaper>> = _showcase.asStateFlow()

    private val _freeNotice = MutableStateFlow(false)
    /** The one-time note that the app is free and asks only for an account is showing. */
    val freeNotice: StateFlow<Boolean> = _freeNotice.asStateFlow()

    init {
        // A storage error leaves the trial unstarted for now; the next launch tries again.
        viewModelScope.launch { runCatchingCancellable { account.startTrialIfNew() } }
    }

    /** Something worth keeping was just added: say once, while signed out, that the app is free. */
    fun offerFreeNotice() {
        viewModelScope.launch {
            // A storage error only skips the notice.
            if (runCatchingCancellable { account.claimFreeNotice() }.getOrDefault(false)) _freeNotice.value = true
        }
    }

    fun dismissFreeNotice() {
        _freeNotice.value = false
    }

    suspend fun signInIntent(): Intent = account.signInIntent()

    fun noBrowser(reason: String) = account.failSignIn(reason)

    fun dismissSignInError() = account.clearFailure()

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    fun loadShowcase() {
        if (_showcase.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { api.wallpapers("", null, null, null) }
                .onSuccess { page -> _showcase.value = page.items.take(SHOWCASE_SIZE) }
        }
    }

    private companion object {
        const val SHOWCASE_SIZE = 6
    }
}
