package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.ui.internal.RenderedPageCache
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor
import java.nio.ByteBuffer

/**
 * Full-screen presentation of a [PdfDocument]. Pages are rasterised on demand by the
 * isolated-process renderer reached through [renderer] (the `PdfRendererBinder`
 * interface, never the concrete `PdfRendererClient`, so test fakes substitute cleanly)
 * and held in a small per-document LRU cache to amortise re-render on quick swipes.
 *
 * Trust contract:
 *
 *  - The non-suppressible [DocumentTrustCaption] is rendered inside this view and is
 *    not gated by any parameter. There is no `DocumentView` overload that omits it.
 *    `ComposableSurfaceLockTest` pins the parameter shape; `TrustClaimSurfaceTest`
 *    pins the visible-text contract.
 *  - The view displays only the rasterised page bitmaps and the caption. ADR 0005 D4:
 *    no PDF metadata, no extracted text, no annotation list, no attachment list.
 *  - The view exposes no share, export, print, or open-with affordance. ADR 0005 D8.
 *    `PublicApiSurfaceTest.passesUiCompiledClassesContainNoForbiddenStrings` enforces
 *    by scanning compiled bytecode for `android.intent.action.SEND` and the
 *    `application/pdf` MIME literal so a future contributor cannot quietly add either.
 *
 * Bitmap ownership: the LRU cache stores native [Bitmap]s and recycles them on
 * eviction; the renderer service has already pre-recycled its source-side copy
 * before returning. On dispose the cache is cleared so every retained pixel buffer
 * is freed at the same moment the composable leaves composition.
 *
 * Cancellation: [renderer]'s suspend functions are cancellation-cooperative — the
 * `LaunchedEffect` keyed on the current page is cancelled when the user swipes,
 * freeing the rendering coroutine even if the underlying binder transact is still
 * in flight on its IO worker.
 *
 * pdfFile lifetime: [pdfFile] is owned by the caller. It MUST remain open for as
 * long as `DocumentView` is composed; closing it earlier produces undefined
 * behaviour for any in-flight `render()` call (the binder duplicates the fd
 * on each transact, but a closed source fd surfaces as a renderer-side
 * `RendererFailed`, not as a UI-visible error). Close after `DocumentView` leaves
 * composition.
 */
@Composable
public fun DocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    modifier: Modifier = Modifier,
) {
    val semantics = LocalPassesSemantics.current.documents
    val cache = remember(doc.id) {
        RenderedPageCache<Bitmap>(
            maxSize = LRU_PAGE_WINDOW,
            onEvict = { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            },
        )
    }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(pageCount = { doc.pageCount })

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(semantics.laneBackground.toComposeColor()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DocumentTrustCaption()

        // pageCount = 0 is rejected at import (DocumentRejectedKind.RendererFailed),
        // so the pager renders nothing. HorizontalPager handles a 0-count state
        // gracefully without a placeholder; a custom branch here would only mask a
        // future regression in the import path with a silent empty surface.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        ) { page ->
            DocumentPage(
                document = doc,
                pageIndex = page,
                pdfFile = pdfFile,
                renderer = renderer,
                cache = cache,
            )
        }
    }
}

@Composable
private fun DocumentPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: RenderedPageCache<Bitmap>,
) {
    val density = LocalDensity.current
    var rendered by remember(document.id, pageIndex) {
        mutableStateOf<ImageBitmap?>(cache.get(document.id, pageIndex)?.asImageBitmap())
    }

    val requestWidthPx = with(density) {
        TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val requestHeightPx = with(density) {
        TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
    }

    LaunchedEffect(document.id, pageIndex, requestWidthPx, requestHeightPx) {
        val cached = cache.get(document.id, pageIndex)
        if (cached != null && !cached.isRecycled) {
            rendered = cached.asImageBitmap()
            return@LaunchedEffect
        }
        val result = renderer.render(pdfFile, pageIndex, requestWidthPx, requestHeightPx)
        if (result is RenderResult.Ok) {
            // ADR 0005 D7: the renderer service caps bitmap area independently and may
            // return a downsized buffer. Use the renderer's reported dimensions, NOT the
            // request, when reconstructing the Bitmap — using the request would make
            // copyPixelsFromBuffer either throw against an undersized SharedMemory
            // (silently swallowed by runCatching below) or render a misshapen image.
            val bitmap = bitmapFromSharedMemory(result)
            if (bitmap != null) {
                cache.put(document.id, pageIndex, bitmap)
                rendered = bitmap.asImageBitmap()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(TARGET_PAGE_ASPECT_RATIO),
        contentAlignment = Alignment.Center,
    ) {
        rendered?.let { bitmap ->
            Image(
                bitmap = bitmap,
                // ADR 0005 D4 forbids extracting text from the PDF; positional caption
                // is the only safe TalkBack fallback. "Page 3 of 7" is non-content but
                // navigationally useful.
                contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun bitmapFromSharedMemory(ok: RenderResult.Ok): Bitmap? {
    // The renderer service writes ARGB_8888 packed row-major with no padding using the
    // dimensions it reports back in `ok.widthPx` / `ok.heightPx` (which may differ from
    // the request — see the call-site comment for D7).
    //
    // Failure modes that runCatching here intentionally swallows: OOM on
    // Bitmap.createBitmap, BufferUnderflowException from copyPixelsFromBuffer if the
    // SharedMemory size mismatches the bitmap, IllegalStateException if the
    // SharedMemory was already closed by a parallel render. Each of these surfaces as
    // "page does not paint" and the next swipe re-attempts; the renderer-service-side
    // failure mode is already telemetered via DocumentTelemetryGuard. Consumer-side
    // telemetry for the silent path is tracked in a follow-up issue (see PR review).
    return runCatching {
        val mapped: ByteBuffer = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped)
            bitmap
        } finally {
            android.os.SharedMemory.unmap(mapped)
            ok.sharedMemory.close()
        }
    }.getOrNull()
}

// HorizontalPager retains composed Image references for adjacent pages during a swipe
// transition. A window equal to "current ± 2" gives the access-ordered LRU enough room
// that an evicted bitmap is never the one the pager is still painting; recycling a
// bitmap whose Image is on screen crashes the draw pass. See PR review C2.
internal const val LRU_PAGE_WINDOW: Int = 5

// Render budget defaults. The renderer service caps the bitmap area independently
// (ADR 0005 D7); these are the UI-side request size, picked to look reasonable on
// phone-class displays without committing to a particular host density.
private const val TARGET_PAGE_WIDTH_DP: Int = 360
private const val TARGET_PAGE_HEIGHT_DP: Int = 480
private const val TARGET_PAGE_ASPECT_RATIO: Float = 3f / 4f
