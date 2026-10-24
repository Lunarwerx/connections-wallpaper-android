package com.anthonyla.paperize.connections

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.usecase.CreateAlbumUseCase
import com.anthonyla.paperize.service.worker.ApplyPictureWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject

/** Why a remix came back empty, as the member reads it (the web's wallpaper-remix.ts remixFailure). */
enum class RemixFailure { OUT_OF_CREDITS, DECLINED, PAUSED, UNAVAILABLE, FAILED }

/** Where the Remix sheet is. */
sealed interface RemixStep {
    /** Asking the gallery whether the member's plan opens Remix. */
    data object Checking : RemixStep
    /** It does not: the sheet offers the plans instead. */
    data object Locked : RemixStep
    /** Typing a style; [failure] says why the last try came back empty. */
    data class Ready(val failure: RemixFailure? = null) : RemixStep
    data object Working : RemixStep
    /** The remixed picture, on the phone at [file]. */
    data class Done(val file: File, val width: Int, val height: Int) : RemixStep
}

data class RemixUiState(
    val wallpaper: ConnectionsWallpaper? = null,
    val step: RemixStep = RemixStep.Checking,
    /** The style the last remix was drawn in. */
    val prompt: String = "",
    val setting: Boolean = false,
    val adding: Boolean = false,
    val added: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    /** Why setting, adding, saving or submitting the result failed, in the error's own words. */
    val error: String? = null
)

private class RemixSignInNeeded : IOException("Remix needs a signed-in member")

/**
 * Remix (Michael, 2026-10-23: "add the remix this button to the individual items in the wallpaper app"): a member on a
 * paid plan types a style and the gallery draws the wallpaper again in it (POST /v1/remix, charged to their credits);
 * a plan without it is offered the plans. The result can be set, added to an album, saved to the phone, or submitted
 * to the gallery under the member's name, where it waits for an admin like any upload.
 */
@HiltViewModel
class RemixViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ConnectionsApi,
    private val account: ConnectionsAccount,
    private val albumRepository: AlbumRepository,
    private val createAlbumUseCase: CreateAlbumUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(RemixUiState())
    val state: StateFlow<RemixUiState> = _state.asStateFlow()

    private val _signInNeeded = Channel<Unit>(Channel.BUFFERED)
    /** The session ran out mid-remix: the sheet closes and the account sheet opens. */
    val signInNeeded: Flow<Unit> = _signInNeeded.receiveAsFlow()

    /** The name a submission shows by default: the member's own, never an email address. */
    suspend fun suggestedCredit(): String =
        account.session.first()?.name?.trim()?.takeUnless { looksLikeEmail(it) }.orEmpty()

    /** Opens the sheet on [wallpaper]; reopening it on the same one keeps what was there. */
    fun open(wallpaper: ConnectionsWallpaper) {
        if (_state.value.wallpaper?.id == wallpaper.id && _state.value.step != RemixStep.Locked) return
        _state.value = RemixUiState(wallpaper = wallpaper)
        askPlan()
    }

    /** Back from the plans: a plan bought there opens Remix without reopening the sheet. */
    fun recheck() {
        if (_state.value.step == RemixStep.Locked) askPlan()
    }

    private fun askPlan() {
        viewModelScope.launch {
            val me = runCatchingCancellable { withToken { api.me(it) } }
            val step = when {
                me.isSuccess -> if (me.getOrThrow().canRemix) RemixStep.Ready() else RemixStep.Locked
                me.exceptionOrNull() is RemixSignInNeeded -> return@launch _signInNeeded.send(Unit)
                // The remix itself answers 403 when the plan does not open it, so a failed check still lets them try.
                else -> RemixStep.Ready()
            }
            _state.update { if (it.step == RemixStep.Checking || it.step == RemixStep.Locked) it.copy(step = step) else it }
        }
    }

    fun remix(prompt: String) {
        val wallpaper = _state.value.wallpaper ?: return
        val style = prompt.trim()
        if (style.isEmpty() || style.length > PROMPT_MAX || _state.value.step == RemixStep.Working) return
        _state.update { it.copy(step = RemixStep.Working, prompt = style, error = null) }
        viewModelScope.launch {
            val made = runCatchingCancellable {
                val remix = withToken { api.remix(wallpaper.id, style, it) }
                RemixStep.Done(keep(wallpaper, remix), remix.width, remix.height)
            }
            val error = made.exceptionOrNull()
            if (error is RemixSignInNeeded) {
                _state.update { it.copy(step = RemixStep.Ready()) }
                _signInNeeded.send(Unit)
                return@launch
            }
            val step = made.getOrNull() ?: if ((error as? ConnectionsApiException)?.status == 403) RemixStep.Locked else RemixStep.Ready(failureOf(error))
            _state.update {
                it.copy(step = step, added = false, saved = false, submitted = false, setting = false, adding = false, saving = false)
            }
        }
    }

    /** Sets the result on [target] through the app's own wallpaper work, as a gallery picture is; [onSet] hears it landed. */
    fun setWallpaper(target: WallpaperTarget, onSet: () -> Unit) = act({ copy(setting = it) }) { done ->
        ApplyPictureWorker.set(context, contentUri(kept(done)).toString(), target.screen)
        onSet()
    }

    /** Adds the result to an album that exists, or a new one, by its own copy on the phone. */
    fun addToLibrary(target: LibraryTarget) = act({ copy(adding = it) }) { done ->
        val albumId = when (target) {
            is LibraryTarget.Existing -> target.album.id
            is LibraryTarget.New -> createAlbumUseCase(target.name).id
        }
        val entry = Wallpaper(
            id = generateId(),
            albumId = albumId,
            uri = contentUri(kept(done)).toString(),
            fileName = titleFor(_state.value),
            dateModified = System.currentTimeMillis()
        )
        albumRepository.addWallpapersToAlbum(albumId, listOf(entry))
        _state.update { it.copy(added = true) }
    }

    /** Saves the result into the phone's pictures, beside the originals saved from the gallery. */
    fun saveToPhone() = act({ copy(saving = it) }) { done ->
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, done.file.name)
                put(MediaStore.Images.Media.MIME_TYPE, JPEG)
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                ?: throw IOException("The phone refused to create the image file")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("The image file could not be opened"))
                    .use { output -> done.file.inputStream().use { it.copyTo(output) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        _state.update { it.copy(saved = true) }
    }

    /**
     * Submits the result to the gallery as the member's own upload, under [credit]: the original to Storage's private
     * locker, its 640px WebP preview to the public lane, then POST /v1/uploads, as the web's upload dialog does. It
     * waits as a draft until an admin publishes it.
     */
    fun submit(title: String, credit: String) = act({ copy(submitting = it) }) { done ->
        val source = checkNotNull(_state.value.wallpaper)
        withToken { token ->
            val bytes = withContext(Dispatchers.IO) { done.file.readBytes() }
            val preview = withContext(Dispatchers.Default) { previewOf(bytes, done.width, done.height) }
            val label = "${source.slug}-remix.jpg"
            val objectId = api.storeOriginal(bytes, JPEG, label, token)
            val previewUrl = api.storePreview(preview.bytes, label, token)
            api.submit(
                ConnectionsUpload(
                    title = title.trim().take(TITLE_MAX),
                    tags = source.tags.take(TAGS_MAX),
                    credit = credit.trim(),
                    rightsConfirmed = true,
                    width = done.width,
                    height = done.height,
                    bytes = bytes.size.toLong(),
                    contentType = JPEG,
                    color = preview.color,
                    originalObjectId = objectId,
                    previewUrl = previewUrl,
                    previewWidth = preview.width,
                    previewHeight = preview.height
                ),
                token
            )
        }
        _state.update { it.copy(submitted = true) }
    }

    /** The title a submission starts from. */
    fun titleFor(state: RemixUiState): String = "${state.wallpaper?.title.orEmpty()} remix".trim().take(TITLE_MAX)

    /** Runs [work] on the result, with [flag] up while it runs and its failure kept for the sheet to show. */
    private fun act(flag: RemixUiState.(Boolean) -> RemixUiState, work: suspend (RemixStep.Done) -> Unit) {
        val done = _state.value.step as? RemixStep.Done ?: return
        _state.update { it.flag(true).copy(error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { work(done) }
            _state.update { state -> state.flag(false).copy(error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }) }
            if (result.exceptionOrNull() is RemixSignInNeeded) _signInNeeded.send(Unit)
        }
    }

    /** Writes the remix the gallery sent where the sheet shows it from: the cache, until it is set or added. */
    private suspend fun keep(wallpaper: ConnectionsWallpaper, remix: ConnectionsRemix): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "remix").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "${wallpaper.slug}-remix-${System.currentTimeMillis()}.jpg")
        file.writeBytes(Base64.getMimeDecoder().decode(remix.data))
        file
    }

    /** The result's lasting copy (files/connections/remixes), which a wallpaper or an album keeps reading after the cache is cleared. */
    private suspend fun kept(done: RemixStep.Done): File = withContext(Dispatchers.IO) {
        val file = File(File(connectionsDir(context), "remixes").apply { mkdirs() }, done.file.name)
        if (!file.isFile) done.file.copyTo(file, overwrite = true)
        file
    }

    private fun contentUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.connections", file)

    /** Runs [block] with a live access token; one 401 earns one forced refresh and one retry. */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = account.accessToken() ?: throw RemixSignInNeeded()
        return try {
            block(token)
        } catch (e: ConnectionsApiException) {
            if (e.status != 401) throw e
            block(account.accessToken(forceRefresh = true) ?: throw RemixSignInNeeded())
        }
    }

    private class Preview(val bytes: ByteArray, val width: Int, val height: Int, val color: String)

    /** The gallery's preview of a [width] x [height] original: 640px wide (wallpaperPreviewSize), WebP, and its average color. */
    private fun previewOf(bytes: ByteArray, width: Int, height: Int): Preview {
        val previewWidth = minOf(PREVIEW_WIDTH, width)
        val previewHeight = maxOf(1, Math.round(height.toDouble() * previewWidth / width).toInt())
        val options = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(maxOf(1, width / previewWidth)) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("The remix could not be read")
        val preview = decoded.scale(previewWidth, previewHeight)
        val out = ByteArrayOutputStream()
        preview.compress(Bitmap.CompressFormat.WEBP_LOSSY, PREVIEW_QUALITY, out)
        val color = preview.scale(1, 1).getPixel(0, 0) and 0xFFFFFF
        return Preview(out.toByteArray(), previewWidth, previewHeight, "#%06x".format(color))
    }

    private fun failureOf(error: Throwable?): RemixFailure = when ((error as? ConnectionsApiException)?.status) {
        402 -> RemixFailure.OUT_OF_CREDITS
        422 -> RemixFailure.DECLINED
        429 -> RemixFailure.PAUSED
        502, 503 -> RemixFailure.UNAVAILABLE
        else -> RemixFailure.FAILED
    }

    companion object {
        /** WALLPAPER_REMIX_PROMPT_MAX, WALLPAPER_TITLE_MAX, WALLPAPER_TAGS_MAX, WALLPAPER_PREVIEW_WIDTH. */
        const val PROMPT_MAX = 500
        private const val TITLE_MAX = 120
        private const val TAGS_MAX = 8
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_QUALITY = 82
        private const val JPEG = "image/jpeg"
        private const val RELATIVE_PATH = "Pictures/Connections Wallpaper"

        /** The gallery's own test (wallpaperCreditLooksLikeEmail): a credit is a name, never an email address. */
        fun looksLikeEmail(credit: String): Boolean = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""").containsMatchIn(credit)
    }
}
