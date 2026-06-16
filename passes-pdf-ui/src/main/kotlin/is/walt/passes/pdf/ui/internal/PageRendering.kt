@file:Suppress("MatchingDeclarationName")

package `is`.walt.passes.pdf.ui.internal

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import `is`.walt.passes.pdf.ConsumerRenderFailure
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import java.nio.BufferUnderflowException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared bitmap-reconstruction + cleanup helpers for `DocumentView` and
 * `FullScreenDocumentView`. Both surfaces rasterise pages through the same binder and
 * map ARGB_8888 pixels out of [SharedMemory]; extracting the primitives here keeps a
 * fix to one site from missing the other (`wpass-6ag` review A1, C2).
 */
internal data class DecodedPage(
    val bitmap: Bitmap,
    val image: ImageBitmap,
    val pageAspect: Float,
)

/**
 * Reconstructs the rendered bitmap from a [RenderResult.Ok] and closes the underlying
 * [SharedMemory] in all paths. Returns `null` when the bitmap cannot be reconstructed;
 * telemetry is notified with the failure mode.
 */
internal fun decodePage(
    ok: RenderResult.Ok,
    telemetry: DocumentTelemetryGuard,
): DecodedPage? =
    runCatching {
        val mapped = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
            // Recycle the bitmap only if the pixel copy fails; on success its ownership
            // transfers to the caller (cache eviction / dispose). Without this the bitmap
            // leaks on the failure path until GC.
            runCatching {
                bitmap.copyPixelsFromBuffer(mapped)
                DecodedPage(bitmap, bitmap.asImageBitmap(), ok.pageAspect)
            }.getOrElse { t ->
                bitmap.recycle()
                throw t
            }
        } finally {
            SharedMemory.unmap(mapped)
            ok.sharedMemory.close()
        }
    }.onFailure { t ->
        telemetry.onConsumerRenderFailed(consumerRenderFailureFor(t))
    }.getOrNull()

/**
 * Closes the [SharedMemory] of a [RenderResult] that the caller is throwing away
 * without decoding (`wpass-6ag` C2 — every settled-zoom render that loses to a
 * superseding gesture must release its ashmem region).
 */
internal fun discardRenderResult(result: RenderResult) {
    if (result is RenderResult.Ok) {
        runCatching { result.sharedMemory.close() }
    }
}

/**
 * Runs [PdfRendererBinder.render] inside [NonCancellable] so the constructed
 * [RenderResult] is always returned to this function, even when the caller's coroutine
 * is cancelled mid-render (`wpass-6ag` review N1). `binder.transact` is blocking and
 * uninterruptible; wrapping it in [NonCancellable] lets the IO worker complete and
 * yield the `Ok` to us, where we either return it for the caller to consume or close
 * its SharedMemory via [discardRenderResult] when [isStillWanted] reports the result
 * is stale (superseded by a newer request, or the calling page disposed). Returns
 * `null` when the result was discarded.
 */
@Suppress("LongParameterList")
internal suspend fun renderOrDiscard(
    renderer: PdfRendererBinder,
    pdf: ParcelFileDescriptor,
    page: Int,
    widthPx: Int,
    heightPx: Int,
    sourceRect: RenderSourceRect,
    isStillWanted: () -> Boolean,
): RenderResult? {
    val result = withContext(NonCancellable) {
        renderer.render(pdf, page, widthPx, heightPx, sourceRect)
    }
    return if (isStillWanted()) {
        result
    } else {
        discardRenderResult(result)
        null
    }
}

internal fun consumerRenderFailureFor(t: Throwable): ConsumerRenderFailure = when (t) {
    is OutOfMemoryError -> ConsumerRenderFailure.OutOfMemory
    is BufferUnderflowException -> ConsumerRenderFailure.DimensionMismatch
    is IllegalStateException -> ConsumerRenderFailure.SharedMemoryUnavailable
    else -> ConsumerRenderFailure.Other
}

