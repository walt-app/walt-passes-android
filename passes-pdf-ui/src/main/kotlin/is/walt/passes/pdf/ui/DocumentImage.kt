// MatchingDeclarationName: this file groups the single-image decode facade — the
// DocumentImageState sealed result and its rememberDocumentImage producer — beside its PDF
// sibling PdfThumbnail.kt; the state type is the named anchor, the producer is its partner.
@file:Suppress("MatchingDeclarationName")

package `is`.walt.passes.pdf.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.ImageDocument
import `is`.walt.passes.pdf.ui.internal.decodeImage
import `is`.walt.passes.pdf.ui.internal.discardImageResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Outcome of a [rememberDocumentImage] call — the image-arm sibling of [PdfThumbnailState].
 * Drives the consumer's placeholder / bitmap / error chrome from a single sealed state. The
 * shape is narrow by design (no field through which a consumer could surface image metadata or
 * EXIF): the image-document trust posture mirrors the PDF one (ADR 0005 D4).
 */
public sealed interface DocumentImageState {
    public data object Loading : DocumentImageState

    public data class Rendered(
        public val image: ImageBitmap,
        public val sourceAspect: Float,
    ) : DocumentImageState

    public data class Failed(public val kind: ImageDecodeRejectedKind) : DocumentImageState
}

/**
 * Compose facade over the isolated-process image decoder for a single image document. The
 * image-arm counterpart to [rememberPdfThumbnail]: it consumes a caller-supplied
 * [ImageDecodeBinder] (it never binds its own service — preserving the D3 isolation contract),
 * decodes the original image once inside the sandbox, and reconstructs the bounded ARGB_8888
 * raster into an [ImageBitmap].
 *
 * Lifecycle guarantees this facade owns so consumers do not reimplement them:
 *
 *  - `decoder.decode(...)` runs inside [NonCancellable], so a surface disposed mid-decode
 *    still receives the [ImageDecodeResult] and releases its `SharedMemory` region (a stale
 *    result is closed via [discardImageResult]).
 *  - the reconstructed bitmap is recycled on dispose of the composable.
 *
 * [imageFile] is the ORIGINAL image bytes opened by the caller; it is owned by the caller and
 * must remain open while the composable is composed. Close after composition ends.
 */
@Composable
public fun rememberDocumentImage(
    document: ImageDocument,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    targetSizePx: IntSize,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
): DocumentImageState {
    val widthPx = targetSizePx.width.coerceAtLeast(1)
    val heightPx = targetSizePx.height.coerceAtLeast(1)
    val state: State<DocumentImageState> = produceState<DocumentImageState>(
        initialValue = DocumentImageState.Loading,
        document.id,
        widthPx,
        heightPx,
        decoder,
        imageFile,
    ) {
        val producerScope = this
        var ownedHandle: Bitmap? = null

        val result = withContext(NonCancellable) { decoder.decode(imageFile, widthPx, heightPx) }
        if (!producerScope.isActive) {
            // Superseded by a key change while the blocking transact was in flight; release the
            // ashmem region rather than reconstructing a bitmap nobody will display.
            discardImageResult(result)
        } else {
            when (result) {
                is ImageDecodeResult.Ok -> {
                    val decoded = decodeImage(result, telemetry)
                    if (decoded != null) {
                        ownedHandle = decoded.bitmap
                        value = DocumentImageState.Rendered(decoded.image, decoded.sourceAspect)
                    } else {
                        // decodeImage routed the cause to telemetry; surface a user-actionable
                        // shape so the surface renders a placeholder rather than spinning.
                        value = DocumentImageState.Failed(ImageDecodeRejectedKind.DecodeFailed)
                    }
                }
                is ImageDecodeResult.Rejected -> {
                    value = DocumentImageState.Failed(result.kind)
                }
            }
        }
        awaitDispose {
            ownedHandle?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    return state.value
}
