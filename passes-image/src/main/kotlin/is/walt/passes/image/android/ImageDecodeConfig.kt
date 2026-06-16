package `is`.walt.passes.image.android

/**
 * Defensive caps for the bounded image decode that runs inside the `:imageDecoder` sandbox
 * (wpass-6yp). The image-codec step is the dominant RCE surface (CVE-2023-4863 libwebp /
 * CVE-2020-16010 class) and the decompression-bomb DoS surface, so every limit here is
 * enforced *before* the platform decoder allocates a full-size bitmap.
 *
 * Layered the way `passes-barcode`'s `BarcodeDecodeConfig` and `passes-pdf`'s
 * `PdfImportConfig` are: [maxBytes] bounds the compressed bytes read off the descriptor
 * before any decode; [maxDimensionPx] / [maxAreaPx] are checked from the decoded *header*
 * (via `ImageDecoder.OnHeaderDecodedListener`) before the backing bitmap is allocated;
 * [allowedMimeTypes] rejects containers outside the still-image roster at the same header
 * step; [decodeTimeoutMs] is the watchdog budget that bounds a slow-loris descriptor and
 * terminates the sandbox on expiry ([DecodeWatchdog]); [maxOutputPixels] caps the
 * Walt-produced raster the service hands back, defending against a caller asking for an
 * arbitrarily large output bitmap (mirrors `PdfRendererService.MAX_PIXELS`).
 *
 * Exposed as constants so tests and the service refer to the same numbers and changing a
 * default is a deliberate, test-breaking edit. The numbers intentionally match
 * `BarcodeDecodeConfig`: both modules decode the same still-image roster from the same import
 * sources, so a file that the barcode sandbox accepts is one the image sandbox accepts too.
 */
internal data class ImageDecodeConfig(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    val maxAreaPx: Long = DEFAULT_MAX_AREA_PX,
    val decodeTimeoutMs: Long = DEFAULT_DECODE_TIMEOUT_MS,
    val maxOutputPixels: Long = DEFAULT_MAX_OUTPUT_PIXELS,
    val allowedMimeTypes: Set<String> = DEFAULT_ALLOWED_MIME_TYPES,
) {
    companion object {
        /** Catches the large-file bomb shape; mirrors `BarcodeDecodeConfig` / storage's 25 MB. */
        const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024

        /** Per-side header cap; a bomb advertising absurd dimensions trips it before allocation. */
        const val DEFAULT_MAX_DIMENSION_PX: Int = 12_000

        /**
         * Megapixel header cap catching the small-file-huge-canvas bomb that stays under
         * [maxDimensionPx] per axis. ~50 MP bounds the ARGB_8888 allocation to ~200 MB.
         */
        const val DEFAULT_MAX_AREA_PX: Long = 50_000_000L

        /** Decode wall-clock budget; on expiry [DecodeWatchdog] kills the sandbox (slow-loris guard). */
        const val DEFAULT_DECODE_TIMEOUT_MS: Long = 5_000L

        /**
         * Bound on the returned raster. 4 MP at ARGB_8888 is 16 MB of pixel data, comfortably
         * below what the host can map. The design expectation is that the consumer requests a
         * view-port-sized raster and never approaches this; the cap defends against a caller
         * (or a future bug) asking for an arbitrarily large output. Mirrors
         * `PdfRendererService.MAX_PIXELS`.
         */
        const val DEFAULT_MAX_OUTPUT_PIXELS: Long = 4L * 1024 * 1024

        /** Still-image containers a card photo realistically arrives in; others are refused before decode. */
        val DEFAULT_ALLOWED_MIME_TYPES: Set<String> =
            setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/heif",
                "image/heic",
            )
    }
}
