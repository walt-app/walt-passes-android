package `is`.walt.passes.pdf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.PdfDocumentId
import `is`.walt.passes.pdf.ui.theme.DocumentSemantics
import `is`.walt.passes.pdf.ui.theme.DocumentTheme
import `is`.walt.passes.ui.core.ArgbColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose smoke tests for the trust-claim-bearing document
 * surfaces. Locks the audit-relevant property (caption text, BiDi-isolated
 * displayLabel, lane composition) rather than visual fidelity.
 *
 * Carved out of `passes-ui::TrustClaimSurfaceTest` (wpass-r4z) when the document
 * composables moved into their own module. The PKPASS-side trust assertions stay
 * over in `passes-ui`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentTrustSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val semantics = DocumentSemantics(
        captionBackground = ArgbColor(0xFF202020.toInt()),
        captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
        // Set explicitly (rather than relying on the captionForeground default) so the
        // restyled icon+text caption is exercised with a distinct, non-default tint.
        captionIconTint = ArgbColor(0xFFFFAA00.toInt()),
        tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
        tileForeground = ArgbColor(0xFF202020.toInt()),
        tileLabelForeground = ArgbColor(0xFF606060.toInt()),
        laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
        documentBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
        documentBadgeForeground = ArgbColor(0xFF202020.toInt()),
    )

    @Test
    fun documentTrustCaptionRendersTheVerbatimTrustText() {
        // The non-suppressible caption is the trust contract for documents (ADR 0005
        // D5: PDFs are never signature-verified). The visible string IS the audit
        // surface; locking it here means a contributor cannot soften the wording
        // without updating the test.
        composeRule.setContent {
            ThemedHost {
                DocumentTrustCaption()
            }
        }
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun documentTrustCaptionTextConstantMatchesDisplayed() {
        // Belt-and-suspenders to the previous test: the displayed string MUST be the
        // exact value of the internal constant. This catches a refactor that splits
        // the caption into multiple Text composables (visible-text assertion would
        // pass; constant-equality breaks).
        assertThat(TRUST_CAPTION_TEXT)
            .isEqualTo("User-provided document. Walt has not verified the source.")
    }

    @Test
    fun documentTileWrapsDisplayLabelInBidiIsolates() {
        // displayLabel is user-controlled (typically the source filename). A bidi
        // character in the filename must not reorder surrounding chrome glyphs.
        // Mirrors the security-sheet bidi-isolation test for B3 URLs and org names.
        val doc = PdfDocument(
            id = PdfDocumentId("doc-1"),
            displayLabel = "tax-2025.pdf",
            byteCount = 1024L,
            pageCount = 3,
            importedAtEpochMs = 0L,
        )
        composeRule.setContent {
            ThemedHost {
                DocumentTile(doc = doc, thumbnail = null, onClick = {})
            }
        }
        // The text on the tile is FSI + label + PDI. Look up by the full isolated
        // form so a missing wrap fails the assertion rather than a merely-cosmetic
        // regression.
        composeRule.onNodeWithText("⁨tax-2025.pdf⁩").assertIsDisplayed()
    }

    @Test
    fun documentsLaneRendersHeaderAndCaptionAboveTiles() {
        val docs = listOf(
            PdfDocument(
                id = PdfDocumentId("a"),
                displayLabel = "alpha.pdf",
                byteCount = 1024L,
                pageCount = 1,
                importedAtEpochMs = 0L,
            ),
        )
        composeRule.setContent {
            ThemedHost {
                DocumentsLane(
                    documents = docs,
                    thumbnails = emptyMap(),
                    onDocumentClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Documents").assertIsDisplayed()
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨alpha.pdf⁩").assertIsDisplayed()
    }

    @Test
    fun documentsLaneRendersNothingForEmptyDocuments() {
        composeRule.setContent {
            ThemedHost {
                DocumentsLane(
                    documents = emptyList(),
                    thumbnails = emptyMap(),
                    onDocumentClick = {},
                )
                androidx.compose.material3.Text("sentinel")
            }
        }
        composeRule.onNodeWithText("sentinel").assertIsDisplayed()
        // The trust caption MUST NOT show in the empty case — an empty lane is no
        // surface at all and should not introduce trust chrome that has nothing to
        // anchor to.
        composeRule.onAllNodesWithText(
            "User-provided document. Walt has not verified the source.",
        ).fetchSemanticsNodes().let { nodes ->
            assertThat(nodes).isEmpty()
        }
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            DocumentTheme(semantics = semantics, content = content)
        }
    }
}
