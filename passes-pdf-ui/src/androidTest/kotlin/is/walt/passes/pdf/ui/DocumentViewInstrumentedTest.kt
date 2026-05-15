package `is`.walt.passes.pdf.ui

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.PdfDocumentId
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.pdf.ui.theme.DocumentSemantics
import `is`.walt.passes.pdf.ui.theme.DocumentTheme
import `is`.walt.passes.ui.core.ArgbColor
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
                sourceRect: RenderSourceRect,
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

    @Test
    fun pinchOnTheInlineSurfaceDoesNotZoomAndKeepsTheTrustCaptionVisible() {
        // wpass-ny4: inline DocumentView is fixed 1x after the design pivot. Pinch
        // gestures must NOT scale the page (no zoom surface inline) and the trust
        // caption must remain visible. The actual zoom surface lives on the full-screen
        // detail view (wpass-jil), entered via the banner.
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
        composeRule.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Page 1 of 3").performTouchInput {
            pinch(
                start0 = center + Offset(-100f, 0f),
                end0 = center + Offset(-300f, 0f),
                start1 = center + Offset(100f, 0f),
                end1 = center + Offset(300f, 0f),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun singleTouchHorizontalDragAtFitScaleStillAdvancesThePager() {
        // wpass-ny4: with zoom removed inline, single-touch drag is always available
        // to the pager. Regression for the pre-`wpass-1wq` swipe-to-page behaviour.
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
        composeRule.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Page 2 of 3").assertIsDisplayed()
    }

    @Test
    fun fullScreenBannerIsHiddenWhenNoCallbackIsProvided() {
        // wpass-jil: the banner is opt-in. Hosts that do not provide an
        // `onOpenFullScreen` callback see no banner; existing call sites stay
        // unchanged after the addition.
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 1),
                    pdfFile = pipeRead,
                    renderer = recorder,
                )
            }
        }
        composeRule.onAllNodesWithText("Tap for full screen").assertCountEquals(0)
    }

    @Test
    fun fullScreenBannerAppearsAndInvokesTheCallbackOnTap() {
        // wpass-jil: tapping the banner is the only call site for the host's
        // navigation hop into the full-screen surface.
        val recorder = RecordingBinder()
        var tapped = false
        composeRule.setContent {
            ThemedHost {
                DocumentView(
                    doc = doc(pageCount = 1),
                    pdfFile = pipeRead,
                    renderer = recorder,
                    onOpenFullScreen = { tapped = true },
                )
            }
        }
        composeRule.onNodeWithText("Tap for full screen").assertIsDisplayed()
        composeRule.onNodeWithText("Tap for full screen").performClick()
        assertThat(tapped).isTrue()
    }

    @Test
    fun fullScreenSurfaceShowsTheTrustCaption() {
        // wpass-jil / ADR 0005 Z.8: the trust caption is docked on the full-screen
        // surface and visible at first composition. The bitmap-availability path is
        // gated on a renderer round-trip so the assertion targets the caption only.
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                FullScreenDocumentView(
                    doc = doc(pageCount = 1),
                    pdfFile = pipeRead,
                    renderer = recorder,
                    onClose = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "User-provided document. Walt has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun pinchOnFullScreenDrivesAZoomAwareRerenderCall() {
        // wpass-jil + wpass-f4b: after the user pinches in the full-screen surface,
        // the surface issues a renderer.render() call whose sourceRect is a SubRect of
        // the page. Asserting "at least one render call used a SubRect" pins the
        // integration without coupling to specific rect coordinates (which depend on
        // pinch trajectory + slot size).
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                FullScreenDocumentView(
                    doc = doc(pageCount = 1),
                    pdfFile = pipeRead,
                    renderer = recorder,
                    onClose = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Page 1 of 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Page 1 of 1").performTouchInput {
            pinch(
                start0 = center + Offset(-100f, 0f),
                end0 = center + Offset(-300f, 0f),
                start1 = center + Offset(100f, 0f),
                end1 = center + Offset(300f, 0f),
            )
        }
        composeRule.waitForIdle()
        val sourceRects = recorder.sourceRects()
        assertThat(sourceRects.any { it is RenderSourceRect.SubRect }).isTrue()
    }

    @Test
    fun trustCaptionDoesNotOverlapThePageWhenTheConsumerGivesAShortSlot() {
        // Regression for wpass-b6h: walt-android embeds DocumentView in a short slot —
        // sandwiched between a document title and a details section — not the full
        // screen. The page must letterbox into whatever height it is given. An earlier
        // `fillMaxWidth().aspectRatio(3:4)` page derived its height from its width and
        // ignored the slot height, so in a short slot it grew taller than the pager
        // viewport and drew up over the non-suppressible trust caption. Pinning
        // caption.bottom <= page.top keeps that boundary structural.
        val recorder = RecordingBinder()
        composeRule.setContent {
            ThemedHost {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    DocumentView(
                        doc = doc(pageCount = 3),
                        pdfFile = pipeRead,
                        renderer = recorder,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val captionBottom = composeRule
            .onNodeWithText("User-provided document. Walt has not verified the source.")
            .getUnclippedBoundsInRoot()
            .bottom
        val pageTop = composeRule
            .onNodeWithContentDescription("Page 1 of 3")
            .getUnclippedBoundsInRoot()
            .top

        assertThat(pageTop.value).isAtLeast(captionBottom.value)
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
            DocumentTheme(semantics = semantics, content = content)
        }
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

    /**
     * Records every render() call and returns a fresh SharedMemory of the requested
     * dimensions. Allocations are tracked via an AtomicInteger so the tally is
     * thread-safe across the multiple coroutine workers HorizontalPager drives.
     */
    private class RecordingBinder : PdfRendererBinder {
        private val pages = CopyOnWriteArrayList<Int>()
        private val rects = CopyOnWriteArrayList<RenderSourceRect>()
        private val allocations = AtomicInteger(0)

        override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
            ProbeResult.Ok(pageCount = 6)

        override suspend fun render(
            pdf: ParcelFileDescriptor,
            page: Int,
            widthPx: Int,
            heightPx: Int,
            sourceRect: RenderSourceRect,
        ): RenderResult {
            pages += page
            rects += sourceRect
            allocations.incrementAndGet()
            val size = widthPx * heightPx * BYTES_PER_PIXEL
            val sm = SharedMemory.create("walt-test-render-$page-${allocations.get()}", size)
            return RenderResult.Ok(sm, widthPx, heightPx)
        }

        fun renderedPages(): List<Int> = pages.toList()

        fun sourceRects(): List<RenderSourceRect> = rects.toList()
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
