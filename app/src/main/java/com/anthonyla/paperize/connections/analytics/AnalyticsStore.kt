package com.anthonyla.paperize.connections.analytics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// A damaged file starts over (the notice is asked again) rather than leaving the app waiting on it.
private val Context.analyticsStore: DataStore<Preferences> by preferencesDataStore(
    name = "analytics_consent",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)
private val POLICY_VERSION = stringPreferencesKey("policy_version")
private val DECIDED_AT = longPreferencesKey("decided_at")
private val ANONYMOUS_ID = stringPreferencesKey("anonymous_id")
private val ALIASED_USER_ID = stringPreferencesKey("aliased_user_id")

/** The member's answer to the notice: the policy version they agreed to and the moment they did. */
data class AnalyticsConsent(val policyVersion: String?, val decidedAt: Long?) {
    val agreedCurrent: Boolean get() = policyVersion == AnalyticsConfig.POLICY_VERSION && decidedAt != null
}

/** Saved in app-private DataStore: the consent answer, the random anonymous id and the last alias sent. */
@Singleton
class AnalyticsStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val store = context.analyticsStore

    /** Null until the saved answer has been read. */
    val consent: StateFlow<AnalyticsConsent?> = store.data
        .map<Preferences, AnalyticsConsent?> { AnalyticsConsent(it[POLICY_VERSION], it[DECIDED_AT]) }
        // The start screen waits for this answer: a file that cannot be read counts as not agreed.
        .catch { emit(AnalyticsConsent(null, null)) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    suspend fun recordAgreement(now: Long = System.currentTimeMillis()) {
        store.edit {
            it[POLICY_VERSION] = AnalyticsConfig.POLICY_VERSION
            it[DECIDED_AT] = now
        }
    }

    /** Created once, the first time it is asked for, and kept. */
    suspend fun anonymousId(): String {
        val saved = store.edit {
            if (it[ANONYMOUS_ID] == null) it[ANONYMOUS_ID] = UUID.randomUUID().toString()
        }
        return saved[ANONYMOUS_ID].orEmpty()
    }

    suspend fun aliasedUserId(): String? = store.data.first()[ALIASED_USER_ID]

    suspend fun markAliased(userId: String) {
        store.edit { it[ALIASED_USER_ID] = userId }
    }
}
