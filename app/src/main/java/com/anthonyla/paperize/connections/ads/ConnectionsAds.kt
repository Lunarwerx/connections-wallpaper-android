package com.anthonyla.paperize.connections.ads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private val Context.adsStore: DataStore<Preferences> by preferencesDataStore(name = "connections_ads")
private val SEEN_POPUPS = stringSetPreferencesKey("seen_popups")

/** One placement's creative as the Connections Ads plane serves it. Only [campaignId] is always there. */
@Serializable
data class SponsoredAd(
    val campaignId: String,
    val headline: String? = null,
    val body: String? = null,
    val ctaLabel: String? = null,
    val ctaUrl: String? = null,
    val imageUrl: String? = null,
    val format: String? = null
)

@Serializable
private data class SlotsReply(val slots: Map<String, SponsoredAd?> = emptyMap())

/**
 * What the Connections Ads plane places in this app's two slots: a banner on the Wallpaper page and a
 * pop-up that also carries announcements. The plane decides what runs; an empty slot shows nothing.
 */
@Singleton
class ConnectionsAds @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val store = context.adsStore
    private val json = Json { ignoreUnknownKeys = true }

    /** The ad in each slot that has one, by slot key. */
    suspend fun placements(): Map<String, SponsoredAd> = withContext(Dispatchers.IO) {
        val url = "$SERVE_URL?slots=$BANNER_SLOT,$POPUP_SLOT"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("accept", "application/json")
            // The Ads plane serves a slot only to the site it was registered for.
            setRequestProperty("origin", ORIGIN)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Connections Ads answered HTTP $status")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<SlotsReply>(text).slots.mapNotNull { (slot, ad) -> ad?.let { slot to it } }.toMap()
        } finally {
            connection.disconnect()
        }
    }

    /** Whether [campaignId]'s pop-up has already been on screen on this phone; each pop-up shows once. */
    suspend fun popupSeen(campaignId: String): Boolean = campaignId in store.data.first()[SEEN_POPUPS].orEmpty()

    /** Called once the pop-up is on screen, so one that never got shown is still due next time. */
    suspend fun markPopupSeen(campaignId: String) {
        store.edit { it[SEEN_POPUPS] = it[SEEN_POPUPS].orEmpty() + campaignId }
    }

    companion object {
        const val BANNER_SLOT = "cnx_slot_f2ea5d53213f035a3d3e9867"
        const val POPUP_SLOT = "cnx_slot_a5f06b306d5276f744f17d33"
        private const val SERVE_URL = "https://ads.connectionsapi.com/functions/ads-public-api"
        private const val ORIGIN = "https://wallpaper.connections.icu"
        private const val TIMEOUT_MS = 15_000
    }
}
