package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.document.DocumentRejectedKind
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.ImageDocumentId
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import `is`.walt.passes.document.ui.theme.DocumentSemantics
import `is`.walt.passes.document.ui.theme.DocumentTheme
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.ui.core.ArgbColor
import `is`.walt.passes.ui.core.faceIsTinted
import java.io.File
import java.util.Collections
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the wpass-80y.2 constraint on `DocumentView.faceTint`: the tint is the **frame**,
 * never the content. The rasterised PDF page and the decoded photo are real content — they
 * render identically tinted or not, and (per the section 04 note in Walt's 26.08.08 design)
 * identically in light and dark, which is exactly the rule real content already follows.
 *
 * The primary pin is on-device: `DocumentViewInstrumentedTest.faceTintDoesNotChangeTheLaidOut
 * PageSize` compares the rendered page's geometry tinted vs untinted, which is where a tint
 * that inset, clipped, or reshaped the content would show. It lives in `androidTest` because
 * neither the SharedMemory round-trip nor pixel layout is reachable under this Robolectric
 * setup — a page never leaves the Loading arm here.
 *
 * What the unit tests add is the layer below that: [faceTintLeavesThePageRenderRequestUnchanged]
 * guards what reaches the isolated renderer, including that the request stays derived from
 * [TARGET_PAGE_WIDTH_DP] / [TARGET_PAGE_HEIGHT_DP] rather than from the slot. Note the
 * asymmetry that makes the second half of that test the load-bearing half: while the request
 * is constant-derived, its size *cannot* vary with a tint by construction, so tint-invariance
 * alone would only catch a tint reaching the page index or source rect. The constant-derived
 * assertion is what fails if a future change makes the request slot-derived — at which point
 * the tint could start moving it and the invariance assertion starts meaning something.
 *
 * The rest assert the tint is not a surface suppressor: everything the untinted surface
 * renders still renders under a tint, including the non-suppressible trust caption.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentFaceTintTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Denim / Violet are the palette's PDF and image defaults; any of the 7 tints is
    // reachable once the user reassigns, so nothing here may depend on either value.
    private val denim = Color(0xFFCEE6FF)
    private val violet = Color(0xFFE8DCFE)

    // Closed in @After, not inside the test: the documented pdfFile / imageFile contract
    // is that the fd outlives the composition.
    private val openFds = mutableListOf<ParcelFileDescriptor>()
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        openFds.forEach { it.close() }
        tempFiles.forEach { it.delete() }
    }

    private val semantics = DocumentSemantics(
        captionBackground = ArgbColor(0xFF202020.toInt()),
        captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
        tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
        tileForeground = ArgbColor(0xFF202020.toInt()),
        tileLabelForeground = ArgbColor(0xFF606060.toInt()),
        laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
        documentBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
        documentBadgeForeground = ArgbColor(0xFF202020.toInt()),
    )

    @Test
    fun faceTintLeavesThePageRenderRequestUnchanged() {
        // Every argument the pager hands the isolated renderer — page index, requested
        // pixel size, source rect — has to be identical to the untinted composition's.
        // Both surfaces are composed once, in DELIBERATELY DIFFERENT slot sizes: the
        // request is supposed to be slot-independent, so differing slots turn the equality
        // below into a check on that too, and the one-setContent-per-rule limit means
        // this is the only shot at composing both.
        val untinted = RecordingRenderer()
        val tinted = RecordingRenderer()
        var density: Density? = null
        composeRule.setContent {
            density = LocalDensity.current
            ThemedHost {
                Column {
                    Slot(width = SLOT_W, height = SLOT_H) {
                        DocumentView(
                            doc = pdfFixture("untinted", PAGE_COUNT),
                            pdfFile = openPdfFd(),
                            renderer = untinted,
                        )
                    }
                    Slot(width = SLOT_W / 2, height = SLOT_H / 2) {
                        DocumentView(
                            doc = pdfFixture("tinted", PAGE_COUNT),
                            pdfFile = openPdfFd(),
                            renderer = tinted,
                            faceTint = denim,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertWithMessage("the untinted composition must request pages at all")
            .that(untinted.requests())
            .isNotEmpty()
        assertThat(tinted.requests()).isEqualTo(untinted.requests())

        // The load-bearing half. While the request is derived from these fixed constants
        // its SIZE cannot vary with a tint by construction, so the equality above only
        // catches a tint that reached the page index or source rect. This is what fails if
        // a future change derives the request from the slot instead (as
        // FullScreenDocumentView already does via BoxWithConstraints) — the point at which
        // a tint could start moving it and the equality above starts carrying weight.
        val d = requireNotNull(density) { "composition never ran" }
        val expectedWidth = with(d) { TARGET_PAGE_WIDTH_DP.dp.toPx().toInt() }
        val expectedHeight = with(d) { TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt() }
        untinted.requests().forEach { request ->
            assertWithMessage("render request %s must be constant-derived, not slot-derived", request)
                .that(request.widthPx to request.heightPx)
                .isEqualTo(expectedWidth to expectedHeight)
        }
    }

    @Test
    fun faceTintDoesNotSuppressTheTrustCaptionOnEitherArm() {
        // The tint is presentation only, not a surface suppressor: the non-suppressible
        // D5 caption survives it in both arms. The ONE way the caption is omitted stays
        // the audited HostedTypeRow concession.
        composeRule.setContent {
            ThemedHost {
                Column {
                    Slot {
                        DocumentView(
                            doc = pdfFixture("pdf", PAGE_COUNT),
                            pdfFile = openPdfFd(),
                            renderer = RecordingRenderer(),
                            faceTint = denim,
                        )
                    }
                    Slot {
                        DocumentView(
                            doc = imageFixture(),
                            imageFile = openImageFd(),
                            imageDecoder = RejectingDecoder,
                            faceTint = violet,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(TRUST_CAPTION_TEXT).fetchSemanticsNodes().let { nodes ->
            assertWithMessage("both tinted arms must carry the caption").that(nodes).hasSize(2)
        }
    }

    @Test
    fun faceTintStillComposesUnderAnOpaqueDarkTint() {
        // The palette ships light tints, but the parameter accepts any colour and — unlike
        // the scannable card face — this frame derives no ink from it, because no kernel
        // text sits on it. Pinning a dark tint keeps it that way: if a future change starts
        // reading the tint's luminance, it acquires a branch this test already covers.
        composeRule.setContent {
            ThemedHost {
                Slot {
                    DocumentView(
                        doc = pdfFixture("dark", PAGE_COUNT),
                        pdfFile = openPdfFd(),
                        renderer = RecordingRenderer(),
                        faceTint = Color(0xFF2A75BA),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TRUST_CAPTION_TEXT).assertIsDisplayed()
    }

    @Test
    fun fullyTransparentTintFallsBackToTheDefaultFrame() {
        // A cleared or not-yet-loaded color must get the documented default frame, not an
        // unpainted one; the gate is pinned by passes-ui-core's FaceTintTest. That the frame
        // routes through it is unpinned on this arm — Robolectric cannot capture pixels here,
        // and there is no on-device counterpart to the scannable arm's face-tint pin
        // (wpass-3qf).
        assertThat(faceIsTinted(Color.Transparent)).isFalse()

        composeRule.setContent {
            ThemedHost {
                Slot {
                    DocumentView(
                        doc = pdfFixture("transparent", PAGE_COUNT),
                        pdfFile = openPdfFd(),
                        renderer = RecordingRenderer(),
                        faceTint = Color.Transparent,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TRUST_CAPTION_TEXT).assertIsDisplayed()
    }

    // -- helpers -------------------------------------------------------------------

    /**
     * A fixed slot. `DocumentView` fills the bounds it is given, so without this the
     * surfaces under comparison would size themselves off the root rather than off
     * something the test controls.
     */
    @Composable
    private fun Slot(
        width: Dp = SLOT_W,
        height: Dp = SLOT_H,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = Modifier.size(width, height)) { content() }
    }

    /** One call into the isolated renderer: everything the tint must not be able to move. */
    private data class Request(
        val page: Int,
        val widthPx: Int,
        val heightPx: Int,
        val sourceRect: RenderSourceRect,
    )

    private class RecordingRenderer : PdfRendererBinder {
        private val seen = Collections.synchronizedSet(mutableSetOf<Request>())

        override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
            ProbeResult.Ok(pageCount = PAGE_COUNT)

        override suspend fun render(
            pdf: ParcelFileDescriptor,
            page: Int,
            widthPx: Int,
            heightPx: Int,
            sourceRect: RenderSourceRect,
        ): RenderResult {
            seen += Request(page, widthPx, heightPx, sourceRect)
            // Rejected keeps the test free of SharedMemory plumbing, which does not
            // round-trip under Robolectric; the request itself is what is under test.
            return RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
        }

        fun requests(): Set<Request> = seen.toSet()
    }

    private object RejectingDecoder : ImageDecodeBinder {
        override suspend fun decode(
            image: ParcelFileDescriptor,
            maxWidthPx: Int,
            maxHeightPx: Int,
        ): ImageDecodeResult = ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
    }

    private fun pdfFixture(id: String, pageCount: Int) = PdfDocument(
        id = PdfDocumentId(id),
        displayLabel = "doc.pdf",
        byteCount = 1024L,
        pageCount = pageCount,
        importedAtEpochMs = 0L,
    )

    private fun imageFixture() = ImageDocument(
        id = ImageDocumentId("img-tint"),
        displayLabel = "receipt.png",
        byteCount = 2048L,
        widthPx = 1080,
        heightPx = 1830,
        importedAtEpochMs = 0L,
    )

    private fun openPdfFd(): ParcelFileDescriptor = openFd("doc", ".pdf", byteArrayOf(0x25))

    private fun openImageFd(): ParcelFileDescriptor = openFd("img", ".png", byteArrayOf(1, 2, 3))

    private fun openFd(prefix: String, suffix: String, bytes: ByteArray): ParcelFileDescriptor {
        val backing = File.createTempFile(prefix, suffix).apply { writeBytes(bytes) }
        tempFiles += backing
        return ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
            .also { openFds += it }
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            DocumentTheme(semantics = semantics, content = content)
        }
    }

    private companion object {
        const val PAGE_COUNT = 3
        val SLOT_W = 320.dp
        val SLOT_H = 400.dp
    }
}
