package `is`.walt.passes.core.internal

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.Code39Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.UPCAWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.EncoderFailureReason
import `is`.walt.passes.core.ScannableFormat
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * ZXing-backed implementation of the kernel's barcode encoder. Per-format writers
 * (`Code128Writer`, `EAN13Writer`, `UPCAWriter`, `Code39Writer`, `QRCodeWriter`) are used
 * instead of `MultiFormatWriter` so the v1 roster of [ScannableFormat] is enforced at the
 * dispatch site: adding a format here is the only path that lets a new symbology reach the
 * encoder. The intermediate `MultiFormatWriter` would silently accept anything ZXing
 * supports, including formats the validator has not been taught to gate.
 *
 * Hidden behind [BarcodeEncoder]; this object is package-internal so consumers cannot
 * reach for ZXing types directly.
 *
 * **Quiet zone.** Most ZXing writers emit their own quiet zone (margin) at default
 * settings. The kernel does not strip or extend it here — the render layer (passes-ui,
 * Child 7) controls visual padding.
 *
 * **QR error correction.** Fixed at [ErrorCorrectionLevel.M] (~15% redundancy). High
 * enough to survive moderate scratch/damage on a phone screen but low enough that long
 * payloads still fit. v1 does not expose a tuning knob; if scanner reliability demands it
 * later, surface a parameter on [BarcodeEncoder.encode] without changing the default.
 */
internal object ZxingBarcodeEncoder {
    // The QR writer's hint for error correction. Other writers ignore the hint map.
    private val qrHints: Map<EncodeHintType, Any> =
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)

    fun encode(
        payload: String,
        format: ScannableFormat,
    ): EncodeResult =
        try {
            EncodeResult.Success(writeMatrix(payload, format))
        } catch (e: WriterException) {
            EncodeResult.Failure(translateFailure(format, e))
        } catch (e: IllegalArgumentException) {
            // ZXing's writers occasionally throw IAE rather than WriterException for
            // structurally bad input (e.g. UPC-A writer on a non-numeric string). Treat
            // the same as a writer-side rejection.
            EncodeResult.Failure(translateFailure(format, e))
        }

    private fun writeMatrix(
        payload: String,
        format: ScannableFormat,
    ): BarcodeMatrix {
        // Width/height of 0 tells each writer to use its symbology's natural minimum.
        // The renderer scales the resulting matrix at draw time; encoding at the natural
        // size avoids ZXing's resampling step and keeps every module at integer width.
        val bitMatrix =
            when (format) {
                ScannableFormat.Code128 -> Code128Writer().encode(payload, ZxingFormat.CODE_128, 0, 0)
                ScannableFormat.Code39 -> Code39Writer().encode(payload, ZxingFormat.CODE_39, 0, 0)
                ScannableFormat.Ean13 -> EAN13Writer().encode(payload, ZxingFormat.EAN_13, 0, 0)
                ScannableFormat.UpcA -> UPCAWriter().encode(payload, ZxingFormat.UPC_A, 0, 0)
                ScannableFormat.Qr -> QRCodeWriter().encode(payload, ZxingFormat.QR_CODE, 0, 0, qrHints)
            }
        return bitMatrix.toBarcodeMatrix()
    }

    private fun translateFailure(
        format: ScannableFormat,
        cause: Throwable,
    ): EncoderFailureReason {
        // QR-only: ZXing signals "no version fits this payload" via a WriterException whose
        // message contains "Data too big". Lift that into the dedicated PayloadTooDense arm
        // so the consumer UI can suggest shortening instead of switching format.
        val message = cause.message.orEmpty()
        if (format == ScannableFormat.Qr && DATA_TOO_BIG_MESSAGE in message) {
            return EncoderFailureReason.PayloadTooDense
        }
        return EncoderFailureReason.WriterRejected(format, message)
    }

    private fun BitMatrix.toBarcodeMatrix(): BarcodeMatrix {
        val flat = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flat[y * width + x] = this[x, y]
            }
        }
        return BarcodeMatrix(width, height, flat)
    }

    // ZXing's QRCodeWriter throws WriterException("Data too big") when no QR version fits.
    // The exact substring is load-bearing — see PayloadTooDense lift above. Pinned by
    // BarcodeEncoderTest.qrPayloadTooDenseLiftsToDedicatedArm.
    private const val DATA_TOO_BIG_MESSAGE = "Data too big"
}
