package com.anthonyla.paperize.connections.analytics

/**
 * Where analytics go and the limits the batch door enforces. Bump [POLICY_VERSION] whenever the notice in
 * the app's privacy strings changes: members who agreed to an older version are asked again.
 */
internal object AnalyticsConfig {
    const val ENDPOINT = "https://analytics.connectionsapi.com/v1/batch"

    /** The Connections Wallpaper product's public ingest key; it already ships in the public web page. */
    const val WRITE_KEY = "ak_d1242d5f31f658e189e97fc281315539445f"
    const val POLICY_VERSION = "wallpaper-2026-10-20"
    const val LIBRARY_NAME = "analytics-android"
    const val LIBRARY_VERSION = "1"

    const val MAX_MESSAGE_BYTES = 32 * 1024
    const val MAX_BATCH_BYTES = 500 * 1024
    const val MAX_MESSAGES_PER_REQUEST = 100
    const val MAX_QUEUED_MESSAGES = 2000
    /** Albums listed in the member's traits; album_count still counts them all, and the list stays inside one message. */
    const val MAX_ALBUMS_IN_TRAITS = 50

    const val IDENTIFY_DEBOUNCE_MS = 5_000L
    const val FLUSH_DELAY_MS = 10_000L
    const val RETRY_BASE_MS = 30_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L
    const val TRIGGER_HINT_TTL_MS = 10_000L
    const val TIMEOUT_MS = 15_000
}
