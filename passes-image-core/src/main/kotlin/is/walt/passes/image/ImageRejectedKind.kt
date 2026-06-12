package `is`.walt.passes.image

/**
 * The reasons an image import can be rejected, flattened to a telemetry-safe enum (no
 * string payloads, ever — see [ImageTelemetryGuard]). Each arm pins a specific control
 * in the import pipeline:
 *
 *  - [OversizedAtImport] → byte-count cap enforced before any decoding work.
 *  - [NotAnImage] → header sniff returned null before the decoder is invoked; cuts off
 *    MIME-spoofing (a PDF or ZIP disguised as a JPEG is rejected here).
 *  - [TooLargeToImport] → decoded width or height exceeds [ImageImportConfig.maxDimensionPx];
 *    checked via a bounds-only decode (`inJustDecodeBounds`) before the full decode.
 *  - [DecodeFailed] → `BitmapFactory.decodeByteArray` returned null, or the PNG
 *    thumbnail-compression step threw after a successful bounds-decode.
 *  - [StorageHandoffFailed] → the consumer-supplied `persist` callback threw after a
 *    successful decode. A spike here points at the consumer's storage layer rather than
 *    the image decoder.
 *
 * Reviewers should treat any future addition of a string-bearing failure arm as a
 * security-policy change.
 */
public enum class ImageRejectedKind {
    OversizedAtImport,
    NotAnImage,
    TooLargeToImport,
    DecodeFailed,
    StorageHandoffFailed,
}
