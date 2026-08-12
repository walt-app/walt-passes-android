package `is`.walt.passes.barcode.android

import `is`.walt.passes.barcode.DecodeLadder
import kotlin.time.Duration.Companion.milliseconds

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
 * slow-loris descriptor and terminates the sandbox on expiry ([DecodeWatchdog]);
 * [symbolDecodeBudgetMs] is the slice of that budget the scale ladder may spend.
 *
 * Every field here is enforced INSIDE the sandbox. [decodeTimeoutMs] is additionally read by the
 * host, which compares elapsed time against it to attribute a dropped binder — see
 * [BarcodeDecodeClient]. The host's own bind bound is not a sandbox cap and lives with the host
 * code, on [DefaultBarcodeImageDecoder].
 *
 * Exposed as constants so tests and the service refer to the same numbers and changing a
 * default is a deliberate, test-breaking edit.
 */
internal data class BarcodeDecodeConfig(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    val maxAreaPx: Long = DEFAULT_MAX_AREA_PX,
    val decodeTimeoutMs: Long = DEFAULT_DECODE_TIMEOUT_MS,
    val symbolDecodeBudgetMs: Long = DEFAULT_SYMBOL_DECODE_BUDGET_MS,
    val allowedMimeTypes: Set<String> = DEFAULT_ALLOWED_MIME_TYPES,
) {
    init {
        require(symbolDecodeBudgetMs < decodeTimeoutMs) {
            "The symbol-decode budget ($symbolDecodeBudgetMs ms) must leave the read and codec " +
                "decode room inside the watchdog timeout ($decodeTimeoutMs ms)."
        }
    }

    /** The scale ladder the symbol decode runs, bounded by this config's slice of the watchdog. */
    val ladder: DecodeLadder = DecodeLadder.STILL_IMAGE.withBudget(symbolDecodeBudgetMs.milliseconds)

    companion object {
        /** Catches the large-file bomb shape; mirrors `PdfImportConfig` / storage's 25 MB. */
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
         * How much of [DEFAULT_DECODE_TIMEOUT_MS] the multi-scale symbol decode may spend
         * (wpass-pl7.2), leaving the descriptor read and the codec decode the rest.
         *
         * The ladder holds this by declining to start a rung it predicts would not finish inside
         * the budget, so exceeding it costs a "no barcode found" where exceeding the watchdog
         * would cost the whole sandbox. It is a self-imposed bound, not a hard one: the ladder's
         * first rung is unconditional (bounded instead by being the cheapest and fixed-capped),
         * and a rung's cost is predicted, not known. The gap between the two numbers is what
         * absorbs both.
         */
        const val DEFAULT_SYMBOL_DECODE_BUDGET_MS: Long = 3_000L

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
