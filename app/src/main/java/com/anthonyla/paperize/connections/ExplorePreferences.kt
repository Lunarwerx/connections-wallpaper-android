package com.anthonyla.paperize.connections

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.exploreStore: DataStore<Preferences> by preferencesDataStore(name = "connections_explore")
private val SHAPE = stringPreferencesKey("shape")

/** Explore's remembered choices: which shapes of picture it shows. */
@Singleton
class ExplorePreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val store = context.exploreStore

    /** A store that cannot be read opens Explore on what fits, as on a first visit. */
    suspend fun shape(): ExploreShape =
        ExploreShape.fromSaved(store.data.catch { emit(emptyPreferences()) }.first()[SHAPE])

    /** A store that cannot be written keeps the choice for this visit only. */
    suspend fun setShape(shape: ExploreShape) {
        try {
            store.edit { it[SHAPE] = shape.saved }
        } catch (e: IOException) {
            Log.w(TAG, "Could not remember the Explore shape", e)
        }
    }

    private companion object {
        const val TAG = "ExplorePreferences"
    }
}
