package `is`.walt.passes.core.internal

import com.google.zxing.EncodeHintType
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
import `is`.walt.passes.core.ScannableFormatConstraints
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
    ): EncodeResult {
        // Proactive PayloadTooDense check for QR: ZXing's WriterException("Data too big") is
        // the only signal the writer gives, and that English string can move under us on any
        // minor version. Doing the byte-length check here keeps the consumer's "shorten the
        // payload" hint working even if ZXing's message wording drifts. UTF-8 because QR's
        // byte-mode capacity is in bytes, not chars (a payload of "é" × 1500 is 3000 bytes).
        if (format == ScannableFormat.Qr &&
            payload.toByteArray(Charsets.UTF_8).size > ScannableFormatConstraints.QR_BYTE_CEILING_ECC_M
        ) {
            return EncodeResult.Failure(EncoderFailureReason.PayloadTooDense)
        }
        // runCatching absorbs anything ZXing throws — including the plain
        // NullPointerException / ArrayIndexOutOfBoundsException its hand-rolled writers do
        // raise on some edge inputs — and funnels it into EncodeResult.Failure to honor the
        // kernel's no-throw contract. Matches the pattern in SignatureVerifier (the other
        // place the kernel wraps third-party code that escapes its declared exception set).
        return runCatching { writeMatrix(payload, format) }
            .fold(
                onSuccess = { EncodeResult.Success(it) },
                onFailure = { EncodeResult.Failure(translateFailure(format, it)) },
            )
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
        // Belt-and-suspenders: the proactive byte-length check above handles the common QR
        // overflow path; this string match catches the same condition when ZXing surfaces it
        // for a payload that slipped under the byte ceiling (e.g. some mixed-mode inputs).
        // The match is intentionally lossy — if ZXing reword the message on a future bump,
        // the encoder still surfaces WriterRejected and the consumer still gets a usable
        // error path, just without the PayloadTooDense-specific UI hint.
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
