package `is`.walt.passes.barcode.android

import `is`.walt.passes.core.ScannableFormat

/**
 * Stable Int <-> [ScannableFormat] mapping for the decode binder wire format.
 *
 * The codes are explicit rather than `ordinal` for the same reason as `passes-pdf`'s
 * `RejectedKindWire`: the wire must not be silently coupled to the source-order of the
 * `passes-core` enum. Reordering or inserting a format there would shift every subsequent
 * code on the wire and mis-decode results without a compile error. The proxy and client
 * live in the same build today, but walt-android consumes this binder downstream; a
 * contributor reordering [ScannableFormat] must not silently break decoding.
 *
 * Add a format: extend [ScannableFormat] in passes-core, append a new code here, and update
 * [encode] / [decode]. [ScannableFormatWireSurfaceTest] fails closed if the table drifts
 * from the enum.
 */
internal object ScannableFormatWire {
    const val CODE_128: Int = 0
    const val EAN_13: Int = 1
    const val UPC_A: Int = 2
    const val CODE_39: Int = 3
    const val QR: Int = 4

    fun encode(format: ScannableFormat): Int =
        when (format) {
            ScannableFormat.Code128 -> CODE_128
            ScannableFormat.Ean13 -> EAN_13
            ScannableFormat.UpcA -> UPC_A
            ScannableFormat.Code39 -> CODE_39
            ScannableFormat.Qr -> QR
        }

    fun decode(code: Int): ScannableFormat =
        when (code) {
            CODE_128 -> ScannableFormat.Code128
            EAN_13 -> ScannableFormat.Ean13
            UPC_A -> ScannableFormat.UpcA
            CODE_39 -> ScannableFormat.Code39
            QR -> ScannableFormat.Qr
            else -> error("Unknown ScannableFormat wire code: $code")
        }
}
