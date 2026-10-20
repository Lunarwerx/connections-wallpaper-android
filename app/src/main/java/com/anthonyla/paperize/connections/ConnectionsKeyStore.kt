package com.anthonyla.paperize.connections

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.connectionsStore: DataStore<Preferences> by preferencesDataStore(name = "connections")
private val API_KEY = stringPreferencesKey("api_key")

/** The member's Connections key (a key holding wallpaper:read), kept only in this app's private storage. */
@Singleton
class ConnectionsKeyStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val store = context.connectionsStore

    val key: Flow<String?> = store.data.map { it[API_KEY] }

    suspend fun current(): String? = key.first()

    suspend fun save(value: String) {
        store.edit { it[API_KEY] = value.trim() }
    }

    suspend fun clear() {
        store.edit { it.remove(API_KEY) }
    }
}
