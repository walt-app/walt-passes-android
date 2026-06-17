@file:Suppress("MatchingDeclarationName")

package `is`.walt.passes.document.ui.internal

import android.graphics.Bitmap
import android.os.SharedMemory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.document.DocumentTelemetryGuard

/**
 * Image-arm sibling of [decodePage]. The image-document display surface reaches the
 * `passes-image` decode sandbox over the `ImageDecodeBinder` and maps ARGB_8888 pixels out of
 * [SharedMemory] exactly as the PDF surface does for `RenderResult.Ok` — the pixel layout is
 * identical by `ImageDecodeResult.Ok`'s contract, so the reconstruction is the same shape.
 * Kept here beside [decodePage] so a fix to the SharedMemory lifetime touches both arms.
 */
internal data class DecodedImage(
    val bitmap: Bitmap,
    val image: ImageBitmap,
    val sourceAspect: Float,
)

/**
 * Reconstructs the decoded bitmap from an [ImageDecodeResult.Ok] and closes the underlying
 * [SharedMemory] on every path. Returns `null` when the bitmap cannot be reconstructed;
 * telemetry is notified with the failure mode (reusing [DocumentTelemetryGuard]'s
 * consumer-render hook — the failure shapes are identical to the PDF path).
 */
internal fun decodeImage(
    ok: ImageDecodeResult.Ok,
    telemetry: DocumentTelemetryGuard,
): DecodedImage? =
    runCatching {
        val mapped = ok.sharedMemory.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(ok.widthPx, ok.heightPx, Bitmap.Config.ARGB_8888)
            // Recycle the bitmap only if the pixel copy fails; on success its ownership
            // transfers to the caller (recycled on dispose). Without this the bitmap leaks
            // on the failure path until GC.
            runCatching {
                bitmap.copyPixelsFromBuffer(mapped)
                DecodedImage(bitmap, bitmap.asImageBitmap(), ok.sourceAspect)
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
 * Closes the [SharedMemory] of an [ImageDecodeResult] the caller is discarding without
 * decoding (e.g. a stale result superseded by a recomposition). Mirrors [discardRenderResult].
 */
internal fun discardImageResult(result: ImageDecodeResult) {
    if (result is ImageDecodeResult.Ok) {
        runCatching { result.sharedMemory.close() }
    }
}
