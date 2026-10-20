package com.anthonyla.paperize.connections.analytics

import android.content.Context
import android.util.Log
import com.anthonyla.paperize.connections.AccountState
import com.anthonyla.paperize.connections.ConnectionsAccount
import com.anthonyla.paperize.connections.analytics.AnalyticsPayload.Kind
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val UNKNOWN_TRIGGER = "unknown"
private const val TAG = "ConnectionsAnalytics"

/**
 * Sends what the member agreed to in the privacy notice to Connections Analytics. Nothing is recorded, queued
 * or sent until the current notice is agreed. [start] runs once from the Application.
 */
@Singleton
class ConnectionsAnalytics @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: AnalyticsStore,
    private val settingsRepository: SettingsRepository,
    private val albumRepository: AlbumRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val account: ConnectionsAccount
) {
    /** Null until the saved answer to the notice has been read. */
    val consent: StateFlow<AnalyticsConsent?> = store.consent

    // Analytics never takes the app down: a storage or encoding failure is logged and that one send is lost.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Connections Analytics failed", e) }
    )
    private val queue = AnalyticsQueue(File(context.noBackupFilesDir, "analytics/queue.jsonl"))
    private val sendLock = Mutex()
    private val started = AtomicBoolean(false)
    private var previous: Snapshot? = null
    private var lastIdentify: Map<String, Any?>? = null
    private var identifyJob: Job? = null
    private var flushJob: Job? = null
    /** The one retry waiting out its backoff after a failed upload; while it waits nothing else sends. */
    @Volatile private var retryJob: Job? = null
    private var failures = 0
    @Volatile private var userId: String? = null
    @Volatile private var hint: Pair<String, Long>? = null
    @Volatile private var albumNames: Map<String, String> = emptyMap()
    @Volatile private var lastScreen: String? = null

    /** Restores the queue, then once the current notice is agreed sends leftovers and starts watching the app. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            queue.load()
            consent.filterNotNull().first { it.agreedCurrent }
            flush()
            observe()
        }
    }

    /** The member agreed to the current notice. Sending starts from here. */
    fun recordAgreement() {
        scope.launch { store.recordAgreement() }
    }

    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        enqueue(Kind.TRACK, name = event, properties = properties)
    }

    fun screen(name: String) {
        // The same screen again is the activity recreated (a rotation, a dark/light switch), not a new visit.
        if (name == lastScreen) return
        lastScreen = name
        enqueue(Kind.SCREEN, name = name)
    }

    /** Says what is about to change the wallpaper, so the next "Wallpaper Changed" event can say why. */
    fun hintTrigger(trigger: String) {
        hint = trigger to System.currentTimeMillis()
    }

    /** Sends what is queued now. Called when the app goes to the background. */
    fun flushNow() {
        if (retryJob?.isActive == true) return
        scope.launch { flush() }
    }

    private suspend fun observe() = coroutineScope {
        launch { snapshots().collect { handle(it) } }
        launch { watchWallpapers() }
    }

    private fun snapshots(): Flow<Snapshot> = combine(
        settingsRepository.getAppSettingsFlow(),
        settingsRepository.getWallpaperModeFlow(),
        settingsRepository.getScheduleSettingsFlow(),
        albumRepository.getAlbumSummaries(),
        account.state
    ) { app, mode, schedule, albums, state -> Snapshot(app, mode, schedule, albums, state) }

    private suspend fun handle(current: Snapshot) {
        val before = previous
        previous = current
        albumNames = current.albums.associate { it.id to it.name }

        val sub = (current.account as? AccountState.SignedIn)?.session?.sub
        userId = sub
        if (sub != null && store.aliasedUserId() != sub) {
            enqueue(Kind.ALIAS, previousId = store.anonymousId(), userIdOverride = sub)
            store.markAliased(sub)
        }
        requestIdentify(identifyTraits(current))
        if (before == null) return

        val wasSub = (before.account as? AccountState.SignedIn)?.session?.sub
        if (before.account !is AccountState.Loading && current.account !is AccountState.Loading) {
            if (wasSub == null && sub != null) enqueue(Kind.TRACK, name = "Signed In")
            if (wasSub != null && sub == null) enqueue(Kind.TRACK, name = "Signed Out", userIdOverride = wasSub)
        }

        val oldSettings = settingsTraits(before.app, before.mode, before.schedule)
        val newSettings = settingsTraits(current.app, current.mode, current.schedule)
        val changed = newSettings.keys.filter { oldSettings[it] != newSettings[it] }
        if (changed.isNotEmpty()) {
            enqueue(
                Kind.TRACK,
                name = "Settings Changed",
                properties = mapOf("keys" to changed, "values" to changed.associateWith { newSettings[it] })
            )
        }

        val oldAlbums = before.albums.associateBy { it.id }
        val newAlbums = current.albums.associateBy { it.id }
        newAlbums.values.filter { it.id !in oldAlbums }
            .forEach { enqueue(Kind.TRACK, name = "Album Added", properties = albumProperties(it)) }
        oldAlbums.values.filter { it.id !in newAlbums }
            .forEach { enqueue(Kind.TRACK, name = "Album Removed", properties = albumProperties(it)) }
    }

    /** Which wallpaper each screen shows is read from the stored current-wallpaper rows, whatever started the change. */
    private suspend fun watchWallpapers() {
        val lastSeen = mutableMapOf<String, String>()
        settingsRepository.getScheduleSettingsFlow()
            .map { shownAlbums(it) }
            .distinctUntilChanged()
            .flatMapLatest { pairs ->
                if (pairs.isEmpty()) {
                    flowOf(emptyList<Shown>())
                } else {
                    combine(pairs.map { (screen, albumId) ->
                        wallpaperRepository.getCurrentWallpaperFlow(albumId, screen).map { Shown(screen, albumId, it) }
                    }) { it.toList() }
                }
            }
            .collect { shown ->
                shown.forEach { entry ->
                    val wallpaper = entry.wallpaper ?: return@forEach
                    val key = "${entry.screen}|${entry.albumId}"
                    val earlier = lastSeen.put(key, wallpaper.id)
                    // The first value seen is the baseline, not a change.
                    if (earlier != null && earlier != wallpaper.id) {
                        wallpaperChanged(entry.screen, entry.albumId, wallpaper, currentTrigger())
                    }
                }
            }
    }

    /** The live engine changed its picture, on its timer or a double tap. It keeps no current-wallpaper row, so it says so here. */
    fun liveWallpaperShown(albumId: String, wallpaper: Wallpaper) {
        wallpaperChanged(ScreenType.LIVE, albumId, wallpaper, "live")
    }

    private fun wallpaperChanged(screen: ScreenType, albumId: String, wallpaper: Wallpaper, trigger: String) {
        enqueue(
            Kind.TRACK,
            name = "Wallpaper Changed",
            properties = mapOf(
                "screen" to screen.name.lowercase(),
                "album_id" to albumId,
                "album_name" to albumNames[albumId],
                "file_name" to wallpaper.displayFileName,
                "trigger" to trigger
            )
        )
    }

    private fun requestIdentify(traits: Map<String, Any?>) {
        identifyJob?.cancel()
        if (traits == lastIdentify) return
        identifyJob = scope.launch {
            delay(AnalyticsConfig.IDENTIFY_DEBOUNCE_MS)
            lastIdentify = traits
            enqueue(Kind.IDENTIFY, traits = traits)
        }
    }

    private fun identifyTraits(s: Snapshot): Map<String, Any?> {
        val names = s.albums.associate { it.id to it.name }
        return settingsTraits(s.app, s.mode, s.schedule) + mapOf(
            "album_count" to s.albums.size,
            "wallpaper_count" to s.albums.sumOf { it.wallpaperCount },
            "albums" to s.albums.take(AnalyticsConfig.MAX_ALBUMS_IN_TRAITS).map { albumProperties(it) },
            "home_album_name" to s.schedule.homeAlbumId?.let { names[it] },
            "lock_album_name" to s.schedule.lockAlbumId?.let { names[it] },
            "live_album_name" to s.schedule.liveAlbumId?.let { names[it] },
            "account_state" to accountLabel(s.account),
            "trial_days_left" to (s.account as? AccountState.Trial)?.daysLeft,
            "install_time" to AnalyticsPayload.isoTime(AnalyticsDevice.snapshot(context).installedAt)
        )
    }

    private fun enqueue(
        kind: Kind,
        name: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        traits: Map<String, Any?> = emptyMap(),
        previousId: String? = null,
        userIdOverride: String? = userId
    ) {
        val timestamp = System.currentTimeMillis()
        scope.launch {
            val agreement = consent.value
            val decidedAt = agreement?.decidedAt
            if (agreement == null || !agreement.agreedCurrent || decidedAt == null) return@launch
            val message = AnalyticsPayload.message(
                kind = kind,
                anonymousId = store.anonymousId(),
                timestamp = timestamp,
                device = AnalyticsDevice.snapshot(context),
                decidedAt = decidedAt,
                userId = userIdOverride,
                name = name,
                properties = properties,
                traits = traits,
                previousId = previousId
            )
            queue.add(AnalyticsPayload.encode(message))
            scheduleFlush(AnalyticsConfig.FLUSH_DELAY_MS)
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        if (flushJob?.isActive == true || retryJob?.isActive == true) return
        flushJob = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Sends queued batches in order. A failed batch stays queued and is retried with backoff. */
    private suspend fun flush() {
        // A queue kept from an older notice waits until the current one is agreed.
        if (consent.value?.agreedCurrent != true) return
        sendLock.withLock {
            while (true) {
                val batch = queue.nextBatch()
                if (batch.isEmpty()) {
                    failures = 0
                    return
                }
                val body = AnalyticsPayload.batch(batch, System.currentTimeMillis())
                val userAgent = AnalyticsPayload.userAgent(AnalyticsDevice.snapshot(context))
                when (AnalyticsUploader.post(body.toByteArray(Charsets.UTF_8), userAgent)) {
                    Delivery.ACCEPTED, Delivery.REJECTED -> {
                        queue.drop(batch.size)
                        failures = 0
                    }
                    Delivery.FAILED -> {
                        failures++
                        val backoff = minOf(AnalyticsConfig.MAX_BACKOFF_MS, AnalyticsConfig.RETRY_BASE_MS shl minOf(failures, 10))
                        if (retryJob?.isActive != true) {
                            retryJob = scope.launch {
                                delay(backoff)
                                retryJob = null
                                flush()
                            }
                        }
                        return
                    }
                }
            }
        }
    }

    private fun currentTrigger(): String {
        val (name, at) = hint ?: return UNKNOWN_TRIGGER
        return if (System.currentTimeMillis() - at <= AnalyticsConfig.TRIGGER_HINT_TTL_MS) name else UNKNOWN_TRIGGER
    }

    private fun shownAlbums(s: ScheduleSettings): List<Pair<ScreenType, String>> = listOfNotNull(
        s.homeAlbumId?.let { ScreenType.HOME to it },
        s.lockAlbumId?.let { ScreenType.LOCK to it },
        s.homeAlbumId?.let { ScreenType.BOTH to it }
    )

    private data class Snapshot(
        val app: AppSettings,
        val mode: WallpaperMode,
        val schedule: ScheduleSettings,
        val albums: List<AlbumSummary>,
        val account: AccountState
    )

    private data class Shown(val screen: ScreenType, val albumId: String, val wallpaper: Wallpaper?)
}

private fun albumProperties(album: AlbumSummary): Map<String, Any?> = mapOf(
    "album_id" to album.id,
    "name" to album.name,
    "wallpaper_count" to album.wallpaperCount,
    "folder_count" to album.folderCount
)

private fun accountLabel(state: AccountState): String = when (state) {
    AccountState.Loading -> "loading"
    is AccountState.Trial -> "trial"
    AccountState.TrialOver -> "trial_over"
    is AccountState.SignedIn -> "signed_in"
}
