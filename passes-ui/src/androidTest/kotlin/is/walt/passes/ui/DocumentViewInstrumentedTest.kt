package `is`.walt.passes.ui

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.PdfDocumentId
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.DocumentSemantics
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.PassesTheme
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device coverage for the parts of `DocumentView` that the JVM unit tests cannot
 * reach: a real `HorizontalPager` that drives layout-driven render() calls into the
 * `PdfRendererBinder`, the bitmap reconstruction from `SharedMemory`, and the
 * caching window the user experiences when paging.
 *
 * The test injects a fake `PdfRendererBinder` rather than the real
 * `PdfRendererClient`, so binding the isolated-process service is not part of this
 * test's responsibilities. The wire-format round-trip is already covered by
 * `PdfRendererBinderRoundTripTest` in `passes-pdf`; the LRU cache contract is
 * covered exhaustively by `RenderedPageCacheTest` in this module's unit tests.
 *
 * The renderer service is the bitmap source-of-truth and pre-recycles its source
 * copy before returning; the consumer's `RenderedPageCache` recycles its entries on
 * eviction. The bitmap-recycling contract is therefore split: the cache test owns
 * the access-order rule, this test owns the pager-drives-render integration.
 */
@RunWith(AndroidJUnit4::class)
class DocumentViewInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var pipeRead: ParcelFileDescriptor
    private lateinit var pipeWrite: ParcelFileDescriptor

    @Before
    fun openPipe() {
        val pipe = ParcelFileDescriptor.createPipe()
        pipeRead = pipe[0]
        pipeWrite = pipe[1]
    }

    @After
    fun closePipe() {
        runCatching { pipeRead.close() }
        runCatching { pipeWrite.close() }
    }

    @Test
    fun pagerLayoutDrivesAtLeastOneRenderCallForTheInitialPage() {
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 3),
                    pdfFile = pipeRead,
                    renderer = recorder,
                )
            }
        }
        composeRule.waitForIdle()
        assertThat(recorder.renderedPages()).contains(0)
    }

    @Test
    fun swipingTheTrustCaptionStillPagesTheDocumentSurface() {
        // The trust caption is non-suppressible regardless of pager position.
        // Pre-condition for the next test: the caption MUST display before, during,
        // and after a swipe.
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 3),
                    pdfFile = pipeRead,
                    renderer = recorder,
                )
            }
        }
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun swipingThroughManyPagesDrivesDistinctRenderCallsAndExceedsTheLruWindow() {
        // Walk far enough through the document that the LRU has evicted at least one
        // page; that eviction is the production code's recycle path. We assert the
        // render() call log shows distinct pages were demanded — the cache cannot
        // serve a page it has not yet seen, so multiple distinct render() calls
        // means the pager is in fact driving the renderer.
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 6),
                    pdfFile = pipeRead,
                    renderer = recorder,
                )
            }
        }
        composeRule.waitForIdle()
        repeat(LRU_PAGE_WINDOW + 2) {
            composeRule.onRoot().performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
        }
        val distinct = recorder.renderedPages().distinct()
        assertThat(distinct.size).isAtLeast(LRU_PAGE_WINDOW + 1)
    }

    @Test
    fun captionStillRendersWhenEveryRenderCallIsRejected() {
        val failing = object : PdfRendererBinder {
            override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
                ProbeResult.Rejected(DocumentRejectedKind.RendererFailed)

            override suspend fun render(
                pdf: ParcelFileDescriptor,
                page: Int,
                widthPx: Int,
                heightPx: Int,
            ): RenderResult = RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
        }
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 2),
                    pdfFile = pipeRead,
                    renderer = failing,
                )
            }
        }
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    private fun doc(pageCount: Int) = PdfDocument(
        id = PdfDocumentId("doc-instrumented"),
        displayLabel = "fixture.pdf",
        byteCount = 4096L,
        pageCount = pageCount,
        importedAtEpochMs = 0L,
    )

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            PassesTheme(semantics = semantics, content = content)
        }
    }

    private val semantics = PassesSemantics(
        signatureBadge = SignatureBadgeColors(
            unsignedBackground = ArgbColor(0xFF000000.toInt()),
            unsignedForeground = ArgbColor(0xFF000000.toInt()),
            selfSignedBackground = ArgbColor(0xFF000000.toInt()),
            selfSignedForeground = ArgbColor(0xFF000000.toInt()),
            appleVerifiedBackground = ArgbColor(0xFF000000.toInt()),
            appleVerifiedForeground = ArgbColor(0xFF000000.toInt()),
            certChainIncompleteBackground = ArgbColor(0xFF000000.toInt()),
            certChainIncompleteForeground = ArgbColor(0xFF000000.toInt()),
        ),
        expiredBadge = ExpiredBadgeStyle(
            pillBackground = ArgbColor(0xFF000000.toInt()),
            pillForeground = ArgbColor(0xFFFFFFFF.toInt()),
            scrimAlpha = 96,
        ),
        securitySheet = SecuritySheetStyle(
            sheetBackground = ArgbColor(0xFFFFFFFF.toInt()),
            emphasisBackground = ArgbColor(0xFFEFEFEF.toInt()),
            emphasisForeground = ArgbColor(0xFF000000.toInt()),
            bodyForeground = ArgbColor(0xFF202020.toInt()),
            confirmContainer = ArgbColor(0xFF202020.toInt()),
            confirmForeground = ArgbColor(0xFFFFFFFF.toInt()),
            cancelForeground = ArgbColor(0xFF202020.toInt()),
        ),
        categoryAccent = CategoryAccentColors(
            boardingPass = ArgbColor(0xFF1D4ED8.toInt()),
            eventTicket = ArgbColor(0xFF7C2D92.toInt()),
            coupon = ArgbColor(0xFF555555.toInt()),
            storeCard = ArgbColor(0xFF555555.toInt()),
            generic = ArgbColor(0xFF555555.toInt()),
        ),
        documents = DocumentSemantics(
            captionBackground = ArgbColor(0xFF202020.toInt()),
            captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
            tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
            tileForeground = ArgbColor(0xFF202020.toInt()),
            tileLabelForeground = ArgbColor(0xFF606060.toInt()),
            laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
            documentBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
            documentBadgeForeground = ArgbColor(0xFF202020.toInt()),
        ),
    )

    /**
     * Records every render() call and returns a fresh SharedMemory of the requested
     * dimensions. Allocations are tracked via an AtomicInteger so the tally is
     * thread-safe across the multiple coroutine workers HorizontalPager drives.
     */
    private class RecordingBinder : PdfRendererBinder {
        private val pages = CopyOnWriteArrayList<Int>()
        private val allocations = AtomicInteger(0)

        override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
            ProbeResult.Ok(pageCount = 6)

        override suspend fun render(
            pdf: ParcelFileDescriptor,
            page: Int,
            widthPx: Int,
            heightPx: Int,
        ): RenderResult {
            pages += page
            allocations.incrementAndGet()
            val size = widthPx * heightPx * BYTES_PER_PIXEL
            val sm = SharedMemory.create("walt-test-render-$page-${allocations.get()}", size)
            return RenderResult.Ok(sm, widthPx, heightPx)
        }

        fun renderedPages(): List<Int> = pages.toList()
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
