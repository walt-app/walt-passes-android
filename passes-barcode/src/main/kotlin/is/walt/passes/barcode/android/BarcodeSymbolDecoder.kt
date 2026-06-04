package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The symbol-decode step that runs on the bounded bitmap [BoundedBitmapDecoder] produced. Held
 * as a seam so the bounded codec decode (wpass-zrt.3) and the symbol decode (wpass-zrt.4)
 * compose without one re-touching the other, and so [doDecode]'s orchestration is testable
 * with a stubbed decoder. Fulfilled by [ZxingBarcodeSymbolDecoder] (pure-JVM ZXing) since .4.
 *
 * Returns only a pure [BarcodeDecodeResult] — `{payload, format}`, a no-symbol marker, or a
 * bucketed failure. The [Bitmap] never leaves the sandbox; nothing here may return it or its
 * pixels.
 */
internal fun interface BarcodeSymbolDecoder {
    fun decode(bitmap: Bitmap): BarcodeDecodeResult
}
