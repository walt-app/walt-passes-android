package `is`.walt.passes.barcode.android

import `is`.walt.passes.core.DecodeFailureReason

/**
 * Stable Int <-> [DecodeFailureReason] mapping for the decode binder wire format. Same
 * explicit-code discipline as [ScannableFormatWire] and `passes-pdf`'s `RejectedKindWire`:
 * the wire stays decoupled from the source-order of the `passes-core` enum so a reorder
 * there cannot silently mis-decode failures downstream in walt-android.
 *
 * [DecodeFailureReasonWireSurfaceTest] fails closed if the table drifts from the enum.
 *
 * [DECODE_TIMED_OUT] never crosses the wire today — a watchdog-killed sandbox cannot report
 * its own death, so [BarcodeDecodeClient] attributes that arm host-side. The code is reserved
 * here anyway so the table stays total over the enum and a future sandbox-side report needs no
 * renumber.
 */
internal object DecodeFailureReasonWire {
    const val SOURCE_UNREADABLE: Int = 0
    const val IMAGE_DECODE_FAILED: Int = 1
    const val IMAGE_TOO_LARGE: Int = 2
    const val UNSUPPORTED_BARCODE_FORMAT: Int = 3
    const val DECODER_UNAVAILABLE: Int = 4
    const val DECODE_TIMED_OUT: Int = 5

    fun encode(reason: DecodeFailureReason): Int =
        when (reason) {
            DecodeFailureReason.SourceUnreadable -> SOURCE_UNREADABLE
            DecodeFailureReason.ImageDecodeFailed -> IMAGE_DECODE_FAILED
            DecodeFailureReason.ImageTooLarge -> IMAGE_TOO_LARGE
            DecodeFailureReason.UnsupportedBarcodeFormat -> UNSUPPORTED_BARCODE_FORMAT
            DecodeFailureReason.DecoderUnavailable -> DECODER_UNAVAILABLE
            DecodeFailureReason.DecodeTimedOut -> DECODE_TIMED_OUT
        }

    fun decode(code: Int): DecodeFailureReason =
        when (code) {
            SOURCE_UNREADABLE -> DecodeFailureReason.SourceUnreadable
            IMAGE_DECODE_FAILED -> DecodeFailureReason.ImageDecodeFailed
            IMAGE_TOO_LARGE -> DecodeFailureReason.ImageTooLarge
            UNSUPPORTED_BARCODE_FORMAT -> DecodeFailureReason.UnsupportedBarcodeFormat
            DECODER_UNAVAILABLE -> DecodeFailureReason.DecoderUnavailable
            DECODE_TIMED_OUT -> DecodeFailureReason.DecodeTimedOut
            else -> error("Unknown DecodeFailureReason wire code: $code")
        }
}
