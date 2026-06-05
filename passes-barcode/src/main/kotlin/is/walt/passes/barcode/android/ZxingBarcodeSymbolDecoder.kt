package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import com.google.zxing.RGBLuminanceSource
import `is`.walt.passes.barcode.decodeLuminance
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The Android `Bitmap → RGBLuminanceSource` adapter for the still-image path. The
 * trust-claim-bearing symbol decode and its roster allowlist live in `passes-barcode-core`
 * ([decodeLuminance]); this only turns the bounded bitmap [BoundedBitmapDecoder] produced into
 * the luminance source the core consumes, so the same decode backs both this path and the
 * in-process live-camera path (wpass-7xo.5). The [Bitmap] and its pixels never leave this process.
 */
internal class ZxingBarcodeSymbolDecoder : BarcodeSymbolDecoder {
    override fun decode(bitmap: Bitmap): BarcodeDecodeResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return BarcodeDecodeResult.NoBarcodeFound
        // Requires a software-backed bitmap; BoundedBitmapDecoder pins ALLOCATOR_SOFTWARE so
        // getPixels never hits the hardware-bitmap path. A stray IllegalStateException here is
        // still contained as a failed decode by the service's doDecode catch.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodeLuminance(RGBLuminanceSource(width, height, pixels))
    }
}
