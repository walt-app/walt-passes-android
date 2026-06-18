package `is`.walt.passes.document.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.ImageDocumentId
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import `is`.walt.passes.document.ui.theme.DocumentSemantics
import `is`.walt.passes.document.ui.theme.DocumentTheme
import `is`.walt.passes.ui.core.ArgbColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

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

    @Test
    fun imageDocumentViewRendersTheNonSuppressibleTrustCaption() {
        // The image arm (wpass-i9x step 4) reuses DocumentTrustCaption verbatim, so the same
        // non-suppressible caption that anchors the PDF surface anchors the image surface — and
        // it shows regardless of decode outcome. A fake decoder that rejects keeps the image
        // region empty; the caption must still display, which is the trust claim under test.
        val doc = ImageDocument(
            id = ImageDocumentId("img-1"),
            displayLabel = "ticket.png",
            byteCount = 2048L,
            widthPx = 1080,
            heightPx = 1920,
            importedAtEpochMs = 0L,
        )
        val rejectingDecoder = object : ImageDecodeBinder {
            override suspend fun decode(
                image: ParcelFileDescriptor,
                maxWidthPx: Int,
                maxHeightPx: Int,
            ): ImageDecodeResult = ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
        }
        val file = File.createTempFile("walt-image-doc", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            composeRule.setContent {
                ThemedHost {
                    DocumentView(doc = doc, imageFile = pfd, imageDecoder = rejectingDecoder)
                }
            }
            composeRule.onNodeWithText(
                "User-provided document. Walt has not verified the source.",
            ).assertIsDisplayed()
        } finally {
            pfd.close()
            file.delete()
        }
    }

    @Test
    fun documentViewHostedPlacementOmitsKernelDockedCaption() {
        // wpass-gv6: TrustCaptionPlacement.Hosted RELOCATES the caption to a host surface
        // — the kernel arm must NOT render its own copy, otherwise the host's relocated
        // caption would duplicate it. This is the only behaviour the param changes; the
        // verbatim caption text and structure remain locked in DocumentTrustCaption.
        val doc = ImageDocument(
            id = ImageDocumentId("img-hosted"),
            displayLabel = "ticket.png",
            byteCount = 2048L,
            widthPx = 1080,
            heightPx = 1920,
            importedAtEpochMs = 0L,
        )
        val rejectingDecoder = object : ImageDecodeBinder {
            override suspend fun decode(
                image: ParcelFileDescriptor,
                maxWidthPx: Int,
                maxHeightPx: Int,
            ): ImageDecodeResult = ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
        }
        val file = File.createTempFile("walt-image-doc", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            composeRule.setContent {
                ThemedHost {
                    DocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = rejectingDecoder,
                        trustCaption = TrustCaptionPlacement.Hosted,
                    )
                }
            }
            composeRule.onAllNodesWithText(
                "User-provided document. Walt has not verified the source.",
            ).fetchSemanticsNodes().let { nodes ->
                assertThat(nodes).isEmpty()
            }
        } finally {
            pfd.close()
            file.delete()
        }
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            DocumentTheme(semantics = semantics, content = content)
        }
    }
}
