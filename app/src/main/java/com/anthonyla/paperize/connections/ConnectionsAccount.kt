package com.anthonyla.paperize.connections

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anthonyla.paperize.core.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "connections_account")
private val SESSION = stringPreferencesKey("session")
private val TRIAL_STARTED_AT = longPreferencesKey("trial_started_at")
private val TRIAL_SEEN_AT = longPreferencesKey("trial_seen_at")
private val PENDING_STATE = stringPreferencesKey("pending_state")
private val PENDING_VERIFIER = stringPreferencesKey("pending_verifier")

/** A signed-in Connections member as this phone holds them: AEGIS tokens and who they are. */
@Serializable
data class ConnectionsSession(
    val sub: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val switchToken: String,
    val deviceId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val picture: String? = null
) {
    /** What the account button shows: the name, else the email, else nothing to show. */
    val label: String? get() = name ?: email
}

sealed interface AccountState {
    data object Loading : AccountState
    data class Trial(val daysLeft: Int) : AccountState
    data object TrialOver : AccountState
    data class SignedIn(val session: ConnectionsSession) : AccountState
}

sealed interface SignInStatus {
    data object Idle : SignInStatus
    /** The browser came back with a code and the app is redeeming it. */
    data object Finishing : SignInStatus
    data class Failed(val reason: String?) : SignInStatus
}

/** Connections answered 401: the session is gone, so the member is signed out. */
class AccountRefusedException(message: String) : IOException(message)

@Serializable
private data class SessionReply(
    val accessToken: String,
    val idToken: String? = null,
    val expiresIn: Long = 3600,
    val userSub: String,
    val switchToken: String? = null,
    val deviceId: String? = null
)

@Serializable
private data class Refusal(val error: String? = null, val code: String? = null)

/**
 * Sign in with Connections, the way a native app does it: the phone's own browser opens AEGIS with a
 * PKCE challenge, the wallpaper site's callback page hands the code to this app's
 * icu.connections.wallpaper://aegis-handoff link, and the app redeems it at /auth/refresh for an
 * access token plus a switch token that refreshes it. Everything is kept in app-private storage.
 *
 * The app is free. For [TRIAL_DAYS] after first launch it works without an account; after that the
 * app's screens ask for a free Connections sign-in. Wallpaper rotation never stops either way.
 */
@Singleton
class ConnectionsAccount @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val store = context.accountStore
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val random = SecureRandom()

    private val _signIn = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val signIn: StateFlow<SignInStatus> = _signIn.asStateFlow()

    val session: Flow<ConnectionsSession?> = store.data.map { it.session() }

    /** Re-reads the clock hourly, so the free week ends while the app is open too. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1.hours)
        }
    }

    val state: Flow<AccountState> = combine(store.data, clock) { prefs, now ->
        val session = prefs.session()
        val started = prefs[TRIAL_STARTED_AT]
        when {
            session != null -> AccountState.SignedIn(session)
            started == null -> AccountState.Trial(TRIAL_DAYS)
            else -> {
                // The latest time this phone has shown: turning the clock back never returns the week.
                val latest = maxOf(now, prefs[TRIAL_SEEN_AT] ?: started)
                val left = TRIAL_MS - (latest - started).coerceAtLeast(0)
                if (left > 0) AccountState.Trial(((left + DAY_MS - 1) / DAY_MS).toInt()) else AccountState.TrialOver
            }
        }
    }

    /** Starts the free week on first launch, and on every launch records the latest clock seen. */
    suspend fun startTrialIfNew() {
        val now = System.currentTimeMillis()
        store.edit {
            if (it[TRIAL_STARTED_AT] == null) it[TRIAL_STARTED_AT] = now
            if (now > (it[TRIAL_SEEN_AT] ?: 0L)) it[TRIAL_SEEN_AT] = now
        }
    }

    /** The intent that opens Connections sign-in in the phone's browser (never an in-app view). */
    suspend fun signInIntent(): Intent {
        val verifier = randomToken(32)
        val state = STATE_PREFIX + randomToken(16)
        store.edit {
            it[PENDING_STATE] = state
            it[PENDING_VERIFIER] = verifier
        }
        _signIn.value = SignInStatus.Idle
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }

    /** The browser came back to icu.connections.wallpaper://aegis-handoff?state=…&code=… */
    fun finishSignIn(uri: Uri) {
        scope.launch {
            // Only the link for the sign-in this phone started counts, and only once: a replayed or
            // forged link finds no pending sign-in and changes nothing on screen.
            val verifier = claimPending(uri.getQueryParameter("state")) ?: return@launch
            _signIn.value = SignInStatus.Finishing
            _signIn.value = runCatchingCancellable { redeem(uri, verifier) }
                .fold(onSuccess = { SignInStatus.Idle }, onFailure = { SignInStatus.Failed(it.message) })
        }
    }

    fun failSignIn(reason: String) {
        _signIn.value = SignInStatus.Failed(reason)
    }

    /** The member has seen why sign-in failed; the reason stops reopening the account sheet. */
    fun clearFailure() {
        if (_signIn.value is SignInStatus.Failed) _signIn.value = SignInStatus.Idle
    }

    /** The verifier of the pending sign-in whose state this is, removed so it redeems once. */
    private suspend fun claimPending(state: String?): String? {
        var verifier: String? = null
        store.edit {
            if (state != null && state == it[PENDING_STATE]) {
                verifier = it[PENDING_VERIFIER]
                it.remove(PENDING_STATE)
                it.remove(PENDING_VERIFIER)
            }
        }
        return verifier
    }

    /** A live access token, refreshed when it is about to run out; null when nobody is signed in. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val current = session.first() ?: return null
        if (!forceRefresh && current.accessExpiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return current.accessToken
        }
        val body = buildJsonObject {
            put("switchToken", current.switchToken)
            current.deviceId?.let { put("deviceId", it) }
        }
        val reply = try {
            call("POST", body)
        } catch (_: AccountRefusedException) {
            store.edit { if (it.session() == current) it.remove(SESSION) }
            return null
        }
        // A sign-out or a new sign-in that landed while this refresh was in flight wins.
        val next = sessionOf(reply, previous = current)
        store.edit { if (it.session() == current) it[SESSION] = json.encodeToString(next) }
        next.accessToken
    }

    /** Forgets the session here first, then asks Connections to revoke it (best effort). */
    suspend fun signOut() {
        val current = session.first() ?: return
        store.edit { it.remove(SESSION) }
        runCatchingCancellable {
            call("DELETE", buildJsonObject {
                put("switchToken", current.switchToken)
                current.deviceId?.let { put("deviceId", it) }
            })
        }
    }

    private suspend fun redeem(uri: Uri, verifier: String) {
        val code = uri.getQueryParameter("code")
            ?: throw IOException(uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error") ?: "No sign-in code came back")
        val reply = call("POST", buildJsonObject {
            put("code", code)
            put("codeVerifier", verifier)
            put("redirectUri", REDIRECT_URI)
        })
        val session = sessionOf(reply, previous = null)
        store.edit { it[SESSION] = json.encodeToString(session) }
    }

    private fun sessionOf(reply: SessionReply, previous: ConnectionsSession?): ConnectionsSession {
        val claims = claimsOf(reply.idToken)
        fun claim(name: String) = claims?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val fullName = claim("name")
            ?: listOfNotNull(claim("given_name"), claim("family_name")).joinToString(" ").ifBlank { null }
        return ConnectionsSession(
            sub = reply.userSub,
            accessToken = reply.accessToken,
            accessExpiresAt = System.currentTimeMillis() + reply.expiresIn * 1000,
            switchToken = reply.switchToken ?: previous?.switchToken ?: throw IOException("Connections sent no switch token"),
            deviceId = reply.deviceId ?: previous?.deviceId,
            name = fullName ?: previous?.name,
            email = claim("email") ?: previous?.email,
            picture = claim("picture") ?: previous?.picture
        )
    }

    private fun claimsOf(idToken: String?): JsonObject? = idToken?.split('.')?.getOrNull(1)?.let { part ->
        runCatching {
            val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            json.parseToJsonElement(String(decoded)).jsonObject
        }.getOrNull()
    }

    private suspend fun call(method: String, body: JsonObject): SessionReply = withContext(Dispatchers.IO) {
        val connection = (URL(REFRESH_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
            setRequestProperty("origin", ORIGIN)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 401) throw AccountRefusedException(refusal(text) ?: "Connections signed this phone out")
            if (status !in 200..299) throw IOException(refusal(text) ?: "Connections answered HTTP $status")
            if (method == "DELETE") SessionReply(accessToken = "", userSub = "") else json.decodeFromString(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun refusal(text: String): String? =
        runCatching { json.decodeFromString<Refusal>(text).error }.getOrNull()

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun challengeOf(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun Preferences.session(): ConnectionsSession? =
        this[SESSION]?.let { runCatching { json.decodeFromString<ConnectionsSession>(it) }.getOrNull() }

    companion object {
        const val TRIAL_DAYS = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val TRIAL_MS = TRIAL_DAYS * DAY_MS
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val TIMEOUT_MS = 20_000

        // AEGIS, the Connections sign-in. The client id is the public first-party one every
        // Connections site uses; the redirect is the wallpaper site's callback page, which forwards
        // a state starting with app-handoff.wallpaper. to this app's link.
        private const val AUTHORIZE_URL = "https://accounts.connectionsapi.com/oauth/authorize"
        private const val REFRESH_URL = "https://accounts.connectionsapi.com/auth/refresh"
        private const val CLIENT_ID = "9a497f1ab10116b5568f836718783c6c"
        private const val REDIRECT_URI = "https://wallpaper.connections.icu/aegis/callback"
        private const val ORIGIN = "https://wallpaper.connections.icu"
        private const val SCOPE = "profile photo"
        private const val STATE_PREFIX = "app-handoff.wallpaper."
    }
}
