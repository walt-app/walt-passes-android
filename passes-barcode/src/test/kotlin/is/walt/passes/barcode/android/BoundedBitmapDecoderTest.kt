package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.DecodeFailureReason
import org.junit.Test

/**
 * Pins the pure half of the bounded decode: the header-cap decision table ([headerRejection]).
 * The file-size cap is the shared `readBoundedBytes` in passes-isolation. The platform
 * `ImageDecoder` integration these feed — that the header listener fires before allocation and
 * that OOM is contained — is the instrumented half (wpass-zrt.5); the policy that decides
 * accept-vs-reject is exercised here on the JVM where it can be enumerated exhaustively.
 */
class BoundedBitmapDecoderTest {
    // ------------------------------------------------------------- header cap table

    @Test
    fun headerRejectionAcceptsAllowedFormatWithinCaps() {
        assertThat(headerRejection("image/png", width = 1024, height = 768, config = config)).isNull()
        assertThat(headerRejection("image/jpeg", width = 4000, height = 3000, config = config)).isNull()
        assertThat(headerRejection("image/webp", width = 8000, height = 6000, config = config)).isNull()
    }

    @Test
    fun headerRejectionRejectsDisallowedFormatAsImageDecodeFailed() {
        // A container outside the still-image roster is one this decoder won't turn into
        // pixels — bucketed as a decode failure, not a size failure.
        assertThat(headerRejection("image/gif", width = 10, height = 10, config = config))
            .isEqualTo(DecodeFailureReason.ImageDecodeFailed)
        assertThat(headerRejection("image/svg+xml", width = 10, height = 10, config = config))
            .isEqualTo(DecodeFailureReason.ImageDecodeFailed)
        assertThat(headerRejection(null, width = 10, height = 10, config = config))
            .isEqualTo(DecodeFailureReason.ImageDecodeFailed)
    }

    @Test
    fun headerRejectionRejectsOverWideOrTallAsImageTooLarge() {
        val overSide = config.maxDimensionPx + 1
        assertThat(headerRejection("image/png", width = overSide, height = 10, config = config))
            .isEqualTo(DecodeFailureReason.ImageTooLarge)
        assertThat(headerRejection("image/png", width = 10, height = overSide, config = config))
            .isEqualTo(DecodeFailureReason.ImageTooLarge)
    }

    @Test
    fun headerRejectionRejectsOverAreaAsImageTooLarge() {
        // The decompression-bomb shape that stays under the per-side cap but multiplies to a
        // huge canvas: both sides legal, product over the megapixel cap.
        val side = config.maxDimensionPx
        assertThat(side.toLong() * side.toLong()).isGreaterThan(config.maxAreaPx)
        assertThat(headerRejection("image/png", width = side, height = side, config = config))
            .isEqualTo(DecodeFailureReason.ImageTooLarge)
    }

    @Test
    fun headerRejectionFormatCheckPrecedesSizeCheck() {
        // A disallowed format that is also over-cap reports the format reason; the allowlist
        // is the first gate.
        assertThat(
            headerRejection("image/gif", width = config.maxDimensionPx + 1, height = 10, config = config),
        ).isEqualTo(DecodeFailureReason.ImageDecodeFailed)
    }

    private val config = BarcodeDecodeConfig()
}
