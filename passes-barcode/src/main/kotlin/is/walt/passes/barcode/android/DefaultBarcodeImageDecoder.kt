package `is`.walt.passes.barcode.android

import android.content.Context
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason

/**
 * Default [BarcodeImageDecoder]. Holds the application [Context] the isolated-process bind
 * will need and owns the decode orchestration seam.
 *
 * The isolated decode service it binds to lands in wpass-zrt.2 (service + memfd/bind
 * plumbing), with bounded codec decode in wpass-zrt.3 and ZXing decode in wpass-zrt.4. Until
 * that service exists there is genuinely no decoder to bind, so [decode] reports
 * [DecodeFailureReason.DecoderUnavailable] rather than fabricating a result. Wiring the bind
 * here replaces this single return; the public surface above does not change.
 */
internal class DefaultBarcodeImageDecoder(
    // held for the wpass-zrt.2 isolated-process bind
    @Suppress("unused") private val appContext: Context,
) : BarcodeImageDecoder {
    override suspend fun decode(source: BarcodeImageSource): BarcodeDecodeResult =
        BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable)
}
