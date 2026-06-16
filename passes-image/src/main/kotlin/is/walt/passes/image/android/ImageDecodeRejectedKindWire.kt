package `is`.walt.passes.image.android

/**
 * Stable Int <-> [ImageDecodeRejectedKind] mapping for the decode binder wire format. Same
 * explicit-code discipline as `passes-pdf`'s `RejectedKindWire` and `passes-barcode`'s
 * `DecodeFailureReasonWire`: the wire codes are decoupled from the source order of the sealed
 * arms so reordering them cannot silently mis-decode a rejection downstream in walt-android.
 *
 * [ImageDecodeRejectedKindWireSurfaceTest] fails closed if the table drifts from the type.
 */
internal object ImageDecodeRejectedKindWire {
    const val NOT_AN_IMAGE: Int = 0
    const val OVERSIZED_AT_IMPORT: Int = 1
    const val DIMENSIONS_TOO_LARGE: Int = 2
    const val DECODE_FAILED: Int = 3
    const val DECODER_UNAVAILABLE: Int = 4

    fun encode(kind: ImageDecodeRejectedKind): Int =
        when (kind) {
            ImageDecodeRejectedKind.NotAnImage -> NOT_AN_IMAGE
            ImageDecodeRejectedKind.OversizedAtImport -> OVERSIZED_AT_IMPORT
            ImageDecodeRejectedKind.DimensionsTooLarge -> DIMENSIONS_TOO_LARGE
            ImageDecodeRejectedKind.DecodeFailed -> DECODE_FAILED
            ImageDecodeRejectedKind.DecoderUnavailable -> DECODER_UNAVAILABLE
        }

    fun decode(code: Int): ImageDecodeRejectedKind =
        when (code) {
            NOT_AN_IMAGE -> ImageDecodeRejectedKind.NotAnImage
            OVERSIZED_AT_IMPORT -> ImageDecodeRejectedKind.OversizedAtImport
            DIMENSIONS_TOO_LARGE -> ImageDecodeRejectedKind.DimensionsTooLarge
            DECODE_FAILED -> ImageDecodeRejectedKind.DecodeFailed
            DECODER_UNAVAILABLE -> ImageDecodeRejectedKind.DecoderUnavailable
            else -> error("Unknown ImageDecodeRejectedKind wire code: $code")
        }
}
