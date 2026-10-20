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

    init {
        viewModelScope.launch { account.startTrialIfNew() }
    }

    suspend fun signInIntent(): Intent = account.signInIntent()

    fun noBrowser(reason: String) = account.failSignIn(reason)

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    fun loadShowcase() {
        if (_showcase.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { api.wallpapers("", null, null) }
                .onSuccess { page -> _showcase.value = page.items.take(SHOWCASE_SIZE) }
        }
    }

    private companion object {
        const val SHOWCASE_SIZE = 6
    }
}
