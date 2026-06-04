package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat

/**
 * The pure-JVM ZXing symbol decode (wpass-zrt.4): reads a barcode off the bounded bitmap
 * [BoundedBitmapDecoder] produced and returns only `{payload, format}`. com.google.zxing:core
 * is Apache-2.0 and 100% JVM, so it adds ZERO native attack surface — the deciding reason it
 * is chosen over a native decoder (which would sit outside the JVM security model with full
 * address-space access) and over ML Kit (telemetry + Play-Services dep). It still runs only
 * inside the `:barcodeDecoder` sandbox; the [Bitmap] and its pixels never leave this process.
 *
 * Symbology ALLOWLIST, not "decode everything": [POSSIBLE_FORMATS] pins the reader to exactly
 * the [ScannableFormat] roster Walt renders. PDF417/Aztec and the rest are deliberately not
 * enabled — restricting the reader narrows both the work and the parser surface a hostile
 * image can reach. Because the reader can only return a format already in the allowlist, the
 * out-of-roster [DecodeFailureReason.UnsupportedBarcodeFormat] arm is a defensive guard the
 * pinned hints make unreachable in practice; it exists so a later hint change can't silently
 * force an unsupported symbol into an ill-fitting result.
 *
 * The payload is returned FAITHFULLY and is never interpreted here — classification and
 * validation stay downstream in the consumer (`QrPayloadClassifier` / `ScannableCardInputValidator`).
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

/**
 * Decode a single barcode from [source], constrained to the roster allowlist. Kept top-level
 * and Bitmap-free so the decode contract — roster mapping, the no-symbol arm, and the
 * exception buckets — is unit-testable on the JVM by round-tripping through ZXing's writer,
 * without the platform image codec or a live device.
 *
 * A fresh [MultiFormatReader] per call keeps the decode one-shot and stateless (the sandbox
 * handles one image per bind). [NotFoundException] — no locatable symbol — is the benign
 * [BarcodeDecodeResult.NoBarcodeFound]; a [ReaderException] (checksum/format) means a
 * symbol-like region was found but could not be decoded cleanly, reported honestly as
 * no usable barcode rather than a fabricated payload.
 */
internal fun decodeLuminance(source: LuminanceSource): BarcodeDecodeResult {
    val binary = BinaryBitmap(HybridBinarizer(source))
    return try {
        val result = MultiFormatReader().decode(binary, DECODE_HINTS)
        val format =
            // Defensive guard: the pinned allowlist makes a non-roster format unreachable here.
            ROSTER_BY_ZXING_FORMAT[result.barcodeFormat]
                ?: return BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.UnsupportedBarcodeFormat)
        BarcodeDecodeResult.DecodedBarcode(result.text, format)
    } catch (_: NotFoundException) {
        BarcodeDecodeResult.NoBarcodeFound
    } catch (_: ReaderException) {
        BarcodeDecodeResult.NoBarcodeFound
    }
}

/** The symbology allowlist: ZXing format → the [ScannableFormat] Walt renders. */
private val ROSTER_BY_ZXING_FORMAT: Map<BarcodeFormat, ScannableFormat> =
    mapOf(
        BarcodeFormat.CODE_128 to ScannableFormat.Code128,
        BarcodeFormat.EAN_13 to ScannableFormat.Ean13,
        BarcodeFormat.UPC_A to ScannableFormat.UpcA,
        BarcodeFormat.CODE_39 to ScannableFormat.Code39,
        BarcodeFormat.QR_CODE to ScannableFormat.Qr,
    )

/**
 * POSSIBLE_FORMATS pins the reader to the roster allowlist; TRY_HARDER trades a little CPU
 * (already bounded by the [DecodeWatchdog]) for a better hit rate on photographed cards.
 */
private val DECODE_HINTS: Map<DecodeHintType, Any> =
    mapOf(
        DecodeHintType.POSSIBLE_FORMATS to ROSTER_BY_ZXING_FORMAT.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
    )
