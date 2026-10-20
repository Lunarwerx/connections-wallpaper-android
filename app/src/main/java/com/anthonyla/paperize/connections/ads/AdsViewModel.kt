package com.anthonyla.paperize.connections.ads

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.connections.analytics.ConnectionsAnalytics
import com.anthonyla.paperize.core.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The banner and the once-per-campaign pop-up Connections Ads placed, and their views and taps. */
@HiltViewModel
class AdsViewModel @Inject constructor(
    private val ads: ConnectionsAds,
    private val analytics: ConnectionsAnalytics
) : ViewModel() {
    private val _banner = MutableStateFlow<SponsoredAd?>(null)
    val banner: StateFlow<SponsoredAd?> = _banner.asStateFlow()

    private val _popup = MutableStateFlow<SponsoredAd?>(null)
    val popup: StateFlow<SponsoredAd?> = _popup.asStateFlow()

    private val viewed = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val placed = runCatchingCancellable { ads.placements() }
                .onFailure { Log.i(TAG, "No sponsored placements this time: ${it.message}") }
                .getOrNull() ?: return@launch
            _banner.value = placed[ConnectionsAds.BANNER_SLOT]?.shown()
            placed[ConnectionsAds.POPUP_SLOT]?.shown()?.let { ad ->
                // A storage error counts as seen: a pop-up is never worth risking twice.
                if (!runCatchingCancellable { ads.popupSeen(ad.campaignId) }.getOrDefault(true)) _popup.value = ad
            }
        }
    }

    /** What may be drawn: something to show, and a link only to a web page. */
    private fun SponsoredAd.shown(): SponsoredAd? {
        if (headline.isNullOrBlank() && body.isNullOrBlank() && imageUrl.isNullOrBlank()) return null
        val web = ctaUrl?.takeIf { url -> Uri.parse(url).let { it.scheme in WEB_SCHEMES && !it.host.isNullOrBlank() } }
        return copy(ctaUrl = web)
    }

    /** The ad in [slot] is on screen; counted once per campaign while this screen lives. A shown pop-up is not shown again. */
    fun viewed(ad: SponsoredAd, slot: String) {
        if (!viewed.add("$slot:${ad.campaignId}")) return
        analytics.track("Ad Viewed", props(ad, slot))
        if (slot == ConnectionsAds.POPUP_SLOT) viewModelScope.launch { runCatchingCancellable { ads.markPopupSeen(ad.campaignId) } }
    }

    fun tapped(ad: SponsoredAd, slot: String) {
        analytics.track("Ad Clicked", props(ad, slot))
        if (slot == ConnectionsAds.POPUP_SLOT) _popup.value = null
    }

    fun dismissPopup() {
        _popup.value?.let { analytics.track("Ad Dismissed", props(it, ConnectionsAds.POPUP_SLOT)) }
        _popup.value = null
    }

    private fun props(ad: SponsoredAd, slot: String) = mapOf(
        "campaign_id" to ad.campaignId,
        "slot" to slot,
        "format" to ad.format,
        "cta_url" to ad.ctaUrl
    )

    private companion object {
        const val TAG = "AdsViewModel"
        val WEB_SCHEMES = setOf("https", "http")
    }
}
