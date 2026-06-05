package `is`.walt.passes.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat

/**
 * The pure-JVM ZXing symbol decode (wpass-zrt.4): reads a barcode off a [LuminanceSource] and
 * returns only `{payload, format}`. com.google.zxing:core is Apache-2.0 and 100% JVM, so it adds
 * ZERO native attack surface — the deciding reason it is chosen over a native decoder (which
 * would sit outside the JVM security model with full address-space access) and over ML Kit
 * (telemetry + Play-Services dep).
 *
 * Lives here, Bitmap-free in `passes-barcode-core`, so ONE decode implementation backs both the
 * isolated still-image path (`passes-barcode`'s `Bitmap → RGBLuminanceSource` adapter) and the
 * future in-process live-camera path (a YUV-frame adapter, wpass-7xo.5). The platform-image and
 * camera glue stay in their Android modules; the symbol decode and its roster allowlist do not
 * fork.
 *
 * Symbology ALLOWLIST, not "decode everything": [DECODE_HINTS] pins the reader to exactly the
 * [ScannableFormat] roster Walt renders. PDF417/Aztec and the rest are deliberately not enabled —
 * restricting the reader narrows both the work and the parser surface a hostile image can reach.
 * Because the reader can only return a format already in the allowlist, the out-of-roster
 * [DecodeFailureReason.UnsupportedBarcodeFormat] arm is a defensive guard the pinned hints make
 * unreachable in practice; it exists so a later hint change can't silently force an unsupported
 * symbol into an ill-fitting result.
 *
 * The payload is returned FAITHFULLY and is never interpreted here — classification and
 * validation stay downstream in the consumer (`QrPayloadClassifier` / `ScannableCardInputValidator`).
 *
 * A fresh [MultiFormatReader] per call keeps the decode one-shot and stateless (the sandbox
 * handles one image per bind). [NotFoundException] — no locatable symbol — is the benign
 * [BarcodeDecodeResult.NoBarcodeFound]; a [ReaderException] (checksum/format) means a
 * symbol-like region was found but could not be decoded cleanly, reported honestly as
 * no usable barcode rather than a fabricated payload.
 */
public fun decodeLuminance(source: LuminanceSource): BarcodeDecodeResult {
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
 * (bounded by the caller — e.g. `passes-barcode`'s `DecodeWatchdog`) for a better hit rate on
 * photographed cards.
 */
private val DECODE_HINTS: Map<DecodeHintType, Any> =
    mapOf(
        DecodeHintType.POSSIBLE_FORMATS to ROSTER_BY_ZXING_FORMAT.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
    )
