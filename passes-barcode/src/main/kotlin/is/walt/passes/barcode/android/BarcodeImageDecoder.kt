package `is`.walt.passes.barcode.android

import android.content.Context
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The single consumer-facing entry point for decoding a barcode/QR from a user-picked static
 * image (wpass-zrt). Analogue of [is.walt.passes.pdf.android.PdfImporter] for the decode
 * boundary: it owns the trust-claim-bearing orchestration the consumer (walt-android) would
 * otherwise have to reassemble — materializing the image across a bind into the isolated
 * decode process, running a bounded codec + pure-JVM ZXing decode inside the sandbox, and
 * returning only `{payload, format}`.
 *
 * Routing every decode through this seam is what keeps the repository's DECISIVE CONSTRAINT
 * honest: the hostile-input boundary lives here, not parallel-implemented in walt-android.
 * The facade returns no `Bitmap` and no source bytes, and does not classify or validate the
 * payload — the consumer routes the returned payload through `passes-core`'s
 * `QrPayloadClassifier` and `ScannableCardInputValidator`.
 *
 * The isolated decode service and its plumbing land in wpass-zrt.2–.4; this interface and its
 * result contract ([BarcodeDecodeResult]) are the surface those beads fill in behind.
 */
public interface BarcodeImageDecoder {
    /**
     * Decode the first barcode found in [source]. Returns
     * [BarcodeDecodeResult.DecodedBarcode] on success, [BarcodeDecodeResult.NoBarcodeFound]
     * when the image decoded but held no recognizable symbol, or
     * [BarcodeDecodeResult.DecodeFailed] folded onto a
     * [is.walt.passes.core.DecodeFailureReason] at the first failing step. The isolated
     * decode process is torn down before this method returns, regardless of outcome.
     *
     * The caller retains ownership of [source] and is responsible for closing any
     * [ParcelFileDescriptor][android.os.ParcelFileDescriptor] it holds.
     */
    public suspend fun decode(source: BarcodeImageSource): BarcodeDecodeResult

    public companion object {
        public fun create(context: Context): BarcodeImageDecoder =
            DefaultBarcodeImageDecoder(context.applicationContext)
    }
}
