package `is`.walt.passes.image.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ImageDocumentId
import `is`.walt.passes.image.ImageFormat
import `is`.walt.passes.image.ui.theme.ImageSemantics
import `is`.walt.passes.image.ui.theme.ImageTheme
import `is`.walt.passes.ui.core.ArgbColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageTrustSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val semantics = ImageSemantics(
        captionBackground = ArgbColor(0xFF202020.toInt()),
        captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
        captionIconTint = ArgbColor(0xFFFFAA00.toInt()),
        tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
        tileForeground = ArgbColor(0xFF202020.toInt()),
        tileLabelForeground = ArgbColor(0xFF606060.toInt()),
        laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
        imageBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
        imageBadgeForeground = ArgbColor(0xFF202020.toInt()),
    )

    @Test
    fun imageTrustCaptionRendersTheVerbatimTrustText() {
        composeRule.setContent {
            ThemedHost { ImageTrustCaption() }
        }
        composeRule.onNodeWithText(
            "User-provided image. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun imageTrustCaptionTextConstantMatchesDisplayed() {
        assertThat(TRUST_CAPTION_TEXT)
            .isEqualTo("User-provided image. Walt has not verified the source.")
    }

    @Test
    fun imageDocumentTileWrapsDisplayLabelInBidiIsolates() {
        val doc = testDoc(displayLabel = "ticket-2026.jpg")
        composeRule.setContent {
            ThemedHost { ImageDocumentTile(doc = doc, thumbnail = null, onClick = {}) }
        }
        composeRule.onNodeWithText("⁨ticket-2026.jpg⁩").assertIsDisplayed()
    }

    @Test
    fun imageDocumentsLaneRendersHeaderAndCaptionAboveTiles() {
        val docs = listOf(testDoc(displayLabel = "photo.png"))
        composeRule.setContent {
            ThemedHost {
                ImageDocumentsLane(
                    images = docs,
                    thumbnails = emptyMap(),
                    onImageClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Images").assertIsDisplayed()
        composeRule.onNodeWithText(
            "User-provided image. Walt has not verified the source.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨photo.png⁩").assertIsDisplayed()
    }

    @Test
    fun imageDocumentsLaneRendersNothingForEmptyList() {
        composeRule.setContent {
            ThemedHost {
                ImageDocumentsLane(
                    images = emptyList(),
                    thumbnails = emptyMap(),
                    onImageClick = {},
                )
                androidx.compose.material3.Text("sentinel")
            }
        }
        composeRule.onNodeWithText("sentinel").assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "User-provided image. Walt has not verified the source.",
        ).fetchSemanticsNodes().let { nodes ->
            assertThat(nodes).isEmpty()
        }
    }

    private fun testDoc(displayLabel: String = "test.png") = ImageDocument(
        id = ImageDocumentId("img-1"),
        displayLabel = displayLabel,
        byteCount = 1024L,
        format = ImageFormat.Png,
        widthPx = 100,
        heightPx = 100,
        importedAtEpochMs = 0L,
    )

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            ImageTheme(semantics = semantics, content = content)
        }
    }
}
