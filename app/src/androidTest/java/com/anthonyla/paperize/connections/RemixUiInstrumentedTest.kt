package com.anthonyla.paperize.connections

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.common.theme.ViewerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Remix sheet's steps as a member meets them: the upsell, the style, the drawing, the result, and submitting it to the gallery. */
class RemixUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val wallpaper = ConnectionsWallpaper(
        id = "00000000-0000-4000-8000-000000000001",
        slug = "misty-lake",
        title = "Misty lake",
        width = 3840,
        height = 2160,
        previewUrl = "https://example.invalid/misty-lake.webp",
        previewWidth = 640,
        previewHeight = 360,
        color = "#6b7f8c",
        credit = "Ada L.",
        license = "cc0"
    )

    private fun text(id: Int) = context.getString(id)

    private fun show(state: RemixUiState, onRemix: (String) -> Unit = {}, onSeePlans: () -> Unit = {}, onSubmit: () -> Unit = {}) {
        compose.setContent {
            ViewerTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    RemixContent(wallpaper, state, onRemix, onSeePlans, onSet = {}, onAddToLibrary = {}, onSave = {}, onSubmit = onSubmit)
                }
            }
        }
    }

    /** Keeps what the test drew, for a person to look at (pulled from the app's files after the run). */
    private fun shot(node: SemanticsNodeInteraction, name: String) {
        val dir = File(checkNotNull(context.getExternalFilesDir(null)), "remix-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun aPlanWithoutRemixIsOfferedThePlansNotTheStyle() {
        var plans = 0
        show(RemixUiState(wallpaper, RemixStep.Locked), onSeePlans = { plans++ })
        compose.onNodeWithText(text(R.string.connections_remix_paid)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connections_remix_style)).assertDoesNotExist()
        shot(compose.onRoot(), "remix-locked")
        compose.onNodeWithText(text(R.string.connections_see_plans)).performClick()
        assertEquals(1, plans)
    }

    /** It opens on the style alone, the picture left to the viewer behind, and nothing is drawn until Generate. */
    @Test fun aPaidPlanRemixesInTheStyleTypedOrOneTheDiceSuggests() {
        var asked: String? = null
        show(RemixUiState(wallpaper, RemixStep.Ready()), onRemix = { asked = it })
        compose.onNodeWithContentDescription(wallpaper.title).assertDoesNotExist()
        val remix = compose.onNode(hasText(text(R.string.connections_generate)) and hasClickAction())
        remix.assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.connections_remix_random)).performClick()
        remix.performClick()
        assertTrue("the dice fills a listed style, got $asked", asked in REMIX_STYLES)
        compose.onNodeWithText(text(R.string.connections_remix_style)).performTextReplacement("Watercolor")
        shot(compose.onRoot(), "remix-ready")
        remix.performClick()
        assertEquals("Watercolor", asked)
    }

    @Test fun whileTheModelDrawsTheCardShowsThePictureAndHowLongItHasRun() {
        show(RemixUiState(wallpaper, RemixStep.Working(SystemClock.elapsedRealtime() - 12_000), prompt = "Watercolor"))
        compose.onNodeWithContentDescription(wallpaper.title).assertIsDisplayed()
        val drawing = { seconds: Long -> hasText(context.getString(R.string.connections_remixing, seconds)) }
        compose.onNode(drawing(12L) or drawing(13L)).assertIsDisplayed()
        shot(compose.onRoot(), "remix-working")
    }

    @Test fun aRemixCanBeSubmittedToTheGallery() {
        val file = File(context.cacheDir, "remix-test.jpg")
        file.outputStream().use { Bitmap.createBitmap(1024, 576, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        var submits = 0
        show(RemixUiState(wallpaper, RemixStep.Done(file, 1024, 576), prompt = "Watercolor"), onSubmit = { submits++ })
        compose.onNodeWithText(text(R.string.connections_set_wallpaper)).assertIsDisplayed()
        shot(compose.onRoot(), "remix-done")
        compose.onNodeWithText(text(R.string.connections_submit_to_gallery)).performClick()
        assertEquals(1, submits)
    }

    @Test fun submittingTakesANameThatIsNotAnEmailAndTheMembersWord() {
        var sent: Pair<String, String>? = null
        compose.setContent {
            ViewerTheme {
                SubmitRemixDialog("Misty lake remix", "ada@example.com", submitting = false, error = null, onSubmit = { t, c -> sent = t to c }, onDismiss = {})
            }
        }
        val submit = compose.onNodeWithText(text(R.string.connections_submit_for_review))
        compose.onNodeWithText(text(R.string.connections_credit_not_email)).assertIsDisplayed()
        submit.assertIsNotEnabled()
        compose.onNodeWithText("ada@example.com").performTextReplacement("Ada L.")
        submit.assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.connections_rights_confirm)).performClick()
        shot(compose.onNode(isDialog()), "remix-submit")
        submit.assertIsEnabled().performClick()
        assertEquals("Misty lake remix" to "Ada L.", sent)
    }
}
