package `is`.walt.passes.barcode.android

import `is`.walt.passes.core.DecodeFailureReason

/**
 * Stable Int <-> [DecodeFailureReason] mapping for the decode binder wire format. Same
 * explicit-code discipline as [ScannableFormatWire] and `passes-pdf`'s `RejectedKindWire`:
 * the wire stays decoupled from the source-order of the `passes-core` enum so a reorder
 * there cannot silently mis-decode failures downstream in walt-android.
 *
 * [DecodeFailureReasonWireSurfaceTest] fails closed if the table drifts from the enum.
 */
internal object DecodeFailureReasonWire {
    const val SOURCE_UNREADABLE: Int = 0
    const val IMAGE_DECODE_FAILED: Int = 1
    const val IMAGE_TOO_LARGE: Int = 2
    const val UNSUPPORTED_BARCODE_FORMAT: Int = 3
    const val DECODER_UNAVAILABLE: Int = 4

    fun encode(reason: DecodeFailureReason): Int =
        when (reason) {
            DecodeFailureReason.SourceUnreadable -> SOURCE_UNREADABLE
            DecodeFailureReason.ImageDecodeFailed -> IMAGE_DECODE_FAILED
            DecodeFailureReason.ImageTooLarge -> IMAGE_TOO_LARGE
            DecodeFailureReason.UnsupportedBarcodeFormat -> UNSUPPORTED_BARCODE_FORMAT
            DecodeFailureReason.DecoderUnavailable -> DECODER_UNAVAILABLE
        }

    fun decode(code: Int): DecodeFailureReason =
        when (code) {
            SOURCE_UNREADABLE -> DecodeFailureReason.SourceUnreadable
            IMAGE_DECODE_FAILED -> DecodeFailureReason.ImageDecodeFailed
            IMAGE_TOO_LARGE -> DecodeFailureReason.ImageTooLarge
            UNSUPPORTED_BARCODE_FORMAT -> DecodeFailureReason.UnsupportedBarcodeFormat
            DECODER_UNAVAILABLE -> DecodeFailureReason.DecoderUnavailable
            else -> error("Unknown DecodeFailureReason wire code: $code")
        }
}
