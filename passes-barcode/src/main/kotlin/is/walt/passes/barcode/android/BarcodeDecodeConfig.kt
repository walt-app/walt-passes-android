package `is`.walt.passes.barcode.android

/**
 * Defensive caps for the bounded image decode that runs inside the `:barcodeDecoder`
 * sandbox (wpass-zrt.3). The image-codec step is the dominant RCE surface (CVE-2023-4863
 * libwebp / CVE-2020-16010 class) and the decompression-bomb DoS surface, so every limit
 * here is enforced *before* the platform decoder allocates a full-size bitmap.
 *
 * Layered the way `passes-pdf`'s `PdfImportConfig` is: [maxBytes] bounds the compressed
 * bytes read off the descriptor before any decode; [maxDimensionPx] / [maxAreaPx] are
 * checked from the decoded *header* (via `ImageDecoder.OnHeaderDecodedListener`) before the
 * backing bitmap is allocated; [allowedMimeTypes] rejects containers outside the still-image
 * roster at the same header step; [decodeTimeoutMs] is the watchdog budget that bounds a
 * slow-loris descriptor and terminates the sandbox on expiry ([DecodeWatchdog]).
 *
 * Exposed as constants so tests and the service refer to the same numbers and changing a
 * default is a deliberate, test-breaking edit.
 */
internal data class BarcodeDecodeConfig(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    val maxAreaPx: Long = DEFAULT_MAX_AREA_PX,
    val decodeTimeoutMs: Long = DEFAULT_DECODE_TIMEOUT_MS,
    val allowedMimeTypes: Set<String> = DEFAULT_ALLOWED_MIME_TYPES,
) {
    companion object {
        /**
         * File-size cap on the compressed bytes read off the descriptor, mirroring
         * `passes-storage`'s `DocumentBounds` / `PdfImportConfig` 25 MB. Bounds the only
         * input a decompression bomb with *small* dimensions could weaponise (the
         * dimension/area caps catch the small-file-huge-canvas shape; this catches the
         * large-file shape).
         */
        const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024

        /**
         * Per-side pixel cap, checked from the header before allocation. A true
         * decompression bomb advertises absurd dimensions (tens of thousands of px per
         * side) in a tiny file; this fires on it instantly. Set well above any real
         * card photo's longest edge.
         */
        const val DEFAULT_MAX_DIMENSION_PX: Int = 12_000

        /**
         * Total-pixel (megapixel) cap, checked from the header before allocation. At
         * ARGB_8888 the backing bitmap is 4 bytes/px, so this bounds the largest allocation
         * the decoder can be asked for to ~200 MB. Generous enough for high-resolution phone
         * photos (~50 MP) yet a hard ceiling against a bomb that stays under [maxDimensionPx]
         * on each axis but multiplies to a huge canvas. An image past this returns
         * [is.walt.passes.core.DecodeFailureReason.ImageTooLarge].
         */
        const val DEFAULT_MAX_AREA_PX: Long = 50_000_000L

        /**
         * Wall-clock budget for one decode, mirroring `PdfImportConfig`'s 5 s render
         * timeout. On expiry [DecodeWatchdog] terminates the isolated process rather than
         * let a slow/malicious descriptor block a sandbox binder thread indefinitely
         * (the slow-loris follow-up flagged on wpass-zrt.3).
         */
        const val DEFAULT_DECODE_TIMEOUT_MS: Long = 5_000L

        /**
         * Container allowlist, checked against the header's reported MIME type. Limited to
         * the still-image formats a user-picked card photo realistically arrives in; an
         * animated or exotic container outside this set is rejected before decode rather
         * than handed to a wider swath of codec surface.
         */
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
