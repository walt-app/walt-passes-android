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
import kotlin.time.TimeSource

/**
 * The pure-JVM ZXing symbol decode (wpass-zrt.4): reads a barcode off a [LuminanceSource] and
 * returns only `{payload, format}`. com.google.zxing:core is 100% JVM, so it adds ZERO native
 * attack surface (the library-choice rationale lives on the `build.gradle.kts` dependency).
 *
 * Lives here, Bitmap-free in `passes-barcode-core`, so ONE decode implementation backs both the
 * isolated still-image path (`passes-barcode`'s `Bitmap → RGBLuminanceSource` adapter) and the
 * future in-process live-camera path (a YUV-frame adapter, wpass-7xo.5). The platform-image and
 * camera glue stay in their Android modules; the symbol decode and its roster allowlist do not
 * fork.
 *
 * Symbology ALLOWLIST, not "decode everything": [DECODE_HINTS] pins the reader to exactly the
 * [ScannableFormat] roster. DataMatrix and the rest are deliberately not enabled — restricting
 * the reader narrows both the work and the parser surface a hostile image can reach. PDF417 and
 * Aztec joined in wpass-pl7.1, for boarding passes.
 *
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
 *
 * One attempt at the source's own resolution is not enough, so [ladder] decides which SCALES of
 * the image are tried and bounds the total spend — see [DecodeLadder] for why the caps make the
 * worst case cheaper rather than dearer. "No locatable symbol" is only reported once every rung
 * has said so.
 */
public fun decodeLuminance(
    source: LuminanceSource,
    ladder: DecodeLadder = DecodeLadder.STILL_IMAGE,
): BarcodeDecodeResult {
    val started = TimeSource.Monotonic.markNow()
    var luminances: ByteArray? = null

    for ((index, rung) in rungSizes(source.width, source.height, ladder).withIndex()) {
        // The first rung always runs; later ones are dropped once the budget is spent so a slow
        // device degrades to "no barcode" rather than to a sandbox its caller's watchdog kills.
        if (index > 0 && started.elapsedNow() >= ladder.budget) break

        val view =
            if (rung.width == source.width && rung.height == source.height) {
                source
            } else {
                val full = luminances ?: source.matrix.also { luminances = it }
                scaledTo(full, source.width, source.height, rung.width, rung.height)
            }

        // Only "no locatable symbol" is worth another rung. A symbol found in a format outside
        // the roster is terminal: the allowlist is a policy statement, and re-reading the same
        // image at another scale until it gives a different answer would blunt it.
        val result = decodeOnce(view)
        if (result != BarcodeDecodeResult.NoBarcodeFound) return result
    }
    return BarcodeDecodeResult.NoBarcodeFound
}

/** One binarize-and-read pass over exactly the pixels [source] presents. */
private fun decodeOnce(source: LuminanceSource): BarcodeDecodeResult {
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

/**
 * The symbology allowlist: ZXing format → the [ScannableFormat] it maps to. Its width is a
 * threat-model input (parser surface and misread ambiguity, Threat 14); changing it means
 * changing `docs/SCANNABLE_CARD_THREAT_MODEL.md` alongside.
 */
private val ROSTER_BY_ZXING_FORMAT: Map<BarcodeFormat, ScannableFormat> =
    mapOf(
        BarcodeFormat.CODE_128 to ScannableFormat.Code128,
        BarcodeFormat.EAN_13 to ScannableFormat.Ean13,
        BarcodeFormat.UPC_A to ScannableFormat.UpcA,
        BarcodeFormat.CODE_39 to ScannableFormat.Code39,
        BarcodeFormat.QR_CODE to ScannableFormat.Qr,
        BarcodeFormat.PDF_417 to ScannableFormat.Pdf417,
        BarcodeFormat.AZTEC to ScannableFormat.Aztec,
    )

/**
 * POSSIBLE_FORMATS pins the reader to the roster allowlist; TRY_HARDER trades a little CPU for a
 * better hit rate on photographed cards. Callers are expected to bound decode time (the
 * still-image path does so via its watchdog).
 */
private val DECODE_HINTS: Map<DecodeHintType, Any> =
    mapOf(
        DecodeHintType.POSSIBLE_FORMATS to ROSTER_BY_ZXING_FORMAT.keys.toList(),
        DecodeHintType.TRY_HARDER to true,
    )
