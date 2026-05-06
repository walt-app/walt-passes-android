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
import androidx.compose.material3.Text
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
        if (doc.pageCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // No-pages is a renderer-side rejection in production; surface
                    // an inert placeholder rather than render an empty pager.
                    text = "",
                    color = semantics.tileForeground.toComposeColor(),
                )
            }
            return@Column
        }

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

    val widthPx = with(density) { TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1) }
    val heightPx = with(density) { TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1) }

    LaunchedEffect(document.id, pageIndex, widthPx, heightPx) {
        val cached = cache.get(document.id, pageIndex)
        if (cached != null && !cached.isRecycled) {
            rendered = cached.asImageBitmap()
            return@LaunchedEffect
        }
        val result = renderer.render(pdfFile, pageIndex, widthPx, heightPx)
        if (result is RenderResult.Ok) {
            val bitmap = bitmapFromSharedMemory(result, widthPx, heightPx)
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
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun bitmapFromSharedMemory(
    ok: RenderResult.Ok,
    widthPx: Int,
    heightPx: Int,
): Bitmap? {
    // The renderer service writes ARGB_8888 packed row-major with no padding. Reconstruct
    // a Bitmap of the same dimensions and copy the SharedMemory bytes in. The SharedMemory
    // region is read-only by service contract.
    return runCatching {
        val mapped: ByteBuffer = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped)
            bitmap
        } finally {
            android.os.SharedMemory.unmap(mapped)
            ok.sharedMemory.close()
        }
    }.getOrNull()
}

internal const val LRU_PAGE_WINDOW: Int = 3

// Render budget defaults. The renderer service caps the bitmap area independently
// (ADR 0005 D7); these are the UI-side request size, picked to look reasonable on
// phone-class displays without committing to a particular host density.
private const val TARGET_PAGE_WIDTH_DP: Int = 360
private const val TARGET_PAGE_HEIGHT_DP: Int = 480
private const val TARGET_PAGE_ASPECT_RATIO: Float = 3f / 4f
