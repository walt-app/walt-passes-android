package `is`.walt.passes.core

/**
 * The outcome of decoding a barcode/QR from a user-supplied static image (wpass-zrt). A pure
 * model type so it lives in `passes-core` next to [ScannableFormat] and is reachable by both
 * the Android decode facade (`:passes-barcode`) and any consumer branching on the result,
 * without either depending on the other.
 *
 * Sealed for compile-time exhaustiveness, mirroring [ParseResult]. Two trust-claim
 * invariants are encoded in the shape itself:
 *
 *  1. The decoder returns the payload FAITHFULLY and never auto-acts on it. Classification
 *     and validation stay downstream in the consumer's call to [QrPayloadClassifier] and
 *     `ScannableCardInputValidator`; nothing here interprets the bytes.
 *  2. No arm carries a `Bitmap` or the source image bytes. The isolated decode process
 *     (wpass-zrt.2) returns only `{payload, format}` over the binder — the hostile image
 *     never crosses back into the caller's address space.
 */
public sealed interface BarcodeDecodeResult {
    /**
     * A single barcode was located and decoded. [payload] is the raw decoded string exactly
     * as the symbol carried it; [format] is the symbology, constrained to the
     * [ScannableFormat] roster Walt renders. A symbol decoded in a format outside that roster
     * is reported as [DecodeFailed] with [DecodeFailureReason.UnsupportedBarcodeFormat], not
     * forced into an ill-fitting arm.
     */
    public data class DecodedBarcode(
        public val payload: String,
        public val format: ScannableFormat,
    ) : BarcodeDecodeResult

    /** The image decoded cleanly but carried no barcode the decoder could locate. */
    public data object NoBarcodeFound : BarcodeDecodeResult

    /** Decoding could not complete; [reason] buckets the failure for telemetry. */
    public data class DecodeFailed(public val reason: DecodeFailureReason) : BarcodeDecodeResult
}

/**
 * Why a decode attempt failed, bucketed to the threat-model steps on wpass-zrt so telemetry
 * can tell "we never read the file" from "the codec rejected it" from "the sandbox went
 * away." Enum (not free text) keeps the surface enumerable and the telemetry cardinality
 * bounded.
 */
public enum class DecodeFailureReason {
    /** The image source could not be opened or read across the bind. */
    SourceUnreadable,

    /** The platform image codec could not decode the container into pixels. */
    ImageDecodeFailed,

    /** The image exceeded the bounded-decode dimension/megapixel/size caps (decompression-bomb guard). */
    ImageTooLarge,

    /** A symbol was found but its symbology is outside the [ScannableFormat] roster. */
    UnsupportedBarcodeFormat,

    /** The isolated decode process could not be bound or terminated before returning a result. */
    DecoderUnavailable,
}
