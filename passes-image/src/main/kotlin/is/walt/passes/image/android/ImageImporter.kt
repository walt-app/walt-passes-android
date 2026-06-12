package `is`.walt.passes.image.android

import `is`.walt.passes.image.ImageFormat
import `is`.walt.passes.image.ImageImportConfig
import `is`.walt.passes.image.ImageImportResult

/**
 * The single import entry point for user-supplied images. Owns the trust-claim-bearing
 * orchestration that the consumer (walt-android) would otherwise have to assemble itself:
 *
 *  1. Materialize the source to a bounded, in-memory byte buffer with a fail-fast cap.
 *  2. Magic-byte header sniff before any decode work — rejects MIME-spoofed files.
 *  3. Bounds-only decode (`inJustDecodeBounds`) to read width/height without allocating
 *     the full uncompressed bitmap; rejects images exceeding [ImageImportConfig.maxDimensionPx].
 *  4. Full decode at a downsampled resolution to produce a thumbnail.
 *  5. Storage handoff via a caller-supplied `persist` lambda.
 *  6. Telemetry start / success / failure with enums-and-durations only.
 *
 * Unlike the PDF importer, there is no isolated process: `BitmapFactory` does not carry
 * the attack surface of a PDF renderer (no embedded JavaScript, no action processing, no
 * complex object-graph formats). The bounded-bytes + header-sniff + dimension-cap
 * sequence contains the risk to what the platform decoder is hardened against.
 *
 * Storage is wired through a callback rather than a direct repository dependency so
 * `passes-image` and `passes-storage` remain independent peers. The consumer's Hilt
 * graph supplies a lambda that calls the repository; the importer stays storage-agnostic.
 *
 * [displayLabel] is supplied by the consumer; the importer does not derive it from image
 * metadata (EXIF, XMP, etc.), because metadata extraction is part of the
 * no-extraction-from-content discipline.
 */
public interface ImageImporter {
    /**
     * Run the import sequence end-to-end. Returns [ImageImportResult.Imported] on
     * success, or [ImageImportResult.Rejected] at the first failing step.
     *
     * [persist] is invoked exactly once on the success path, after the decode succeeds
     * and before [ImageImportResult.Imported] is constructed. It is never invoked on a
     * rejection. If [persist] throws (other than `CancellationException`, which is
     * rethrown to preserve structured concurrency), the import returns
     * [is.walt.passes.image.ImageRejectedKind.StorageHandoffFailed].
     */
    public suspend fun import(
        source: ImageImportSource,
        displayLabel: String,
        persist: suspend (
            label: String,
            imageBytes: ByteArray,
            format: ImageFormat,
            widthPx: Int,
            heightPx: Int,
            thumbnailBytes: ByteArray,
        ) -> Unit,
    ): ImageImportResult

    public companion object {
        public fun create(config: ImageImportConfig = ImageImportConfig()): ImageImporter =
            DefaultImageImporter(config)
    }
}
