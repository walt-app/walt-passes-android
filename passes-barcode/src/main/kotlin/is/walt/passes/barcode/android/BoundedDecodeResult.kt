package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import `is`.walt.passes.core.DecodeFailureReason

/**
 * The outcome of the bounded codec decode (wpass-zrt.3): either a [Bitmap] that cleared every
 * cap, or a bucketed rejection. Module-internal and never crosses the binder — the bitmap is
 * consumed by the symbol decode and recycled inside the sandbox; only the pure
 * `{payload, format}` of [is.walt.passes.core.BarcodeDecodeResult] crosses back.
 */
internal sealed interface BoundedDecodeResult {
    /** The image decoded within all caps. The caller owns [bitmap] and must recycle it. */
    data class Decoded(val bitmap: Bitmap) : BoundedDecodeResult

    /** The image was rejected before or during decode; [reason] buckets it for telemetry. */
    data class Rejected(val reason: DecodeFailureReason) : BoundedDecodeResult
}
