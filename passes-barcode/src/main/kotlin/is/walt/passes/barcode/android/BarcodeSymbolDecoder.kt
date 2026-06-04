package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The symbol-decode step that runs on the bounded bitmap [BoundedBitmapDecoder] produced. Held
 * as a seam so wpass-zrt.3 (this bead, the bounded codec decode) and wpass-zrt.4 (the pure-JVM
 * ZXing symbol decode) compose without one re-touching the other: the service wires the
 * bounded decode now and swaps [NotYetImplemented] for the ZXing implementation when .4 lands.
 *
 * Returns only a pure [BarcodeDecodeResult] — `{payload, format}`, a no-symbol marker, or a
 * bucketed failure. The [Bitmap] never leaves the sandbox; nothing here may return it or its
 * pixels.
 */
internal fun interface BarcodeSymbolDecoder {
    fun decode(bitmap: Bitmap): BarcodeDecodeResult

    companion object {
        /**
         * Stand-in until wpass-zrt.4 lands the real ZXing decode. A bitmap that decoded
         * within the caps but has no symbology decoder behind it yet is reported as
         * [BarcodeDecodeResult.NoBarcodeFound] — the image decoded cleanly, no barcode was
         * located — which is exactly what a benign image with no barcode would return, so the
         * arm is honest. The instrumented `benignQrDecodesToPayloadAndFormat` stays `@Ignore`d
         * until .4 fills this in.
         */
        val NotYetImplemented: BarcodeSymbolDecoder =
            BarcodeSymbolDecoder { BarcodeDecodeResult.NoBarcodeFound }
    }
}
