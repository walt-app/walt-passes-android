package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.DecodeFailureReason
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pins the two pure halves of the bounded decode (wpass-zrt.3): the file-size cap
 * ([readBoundedBytes]) and the header-cap decision table ([headerRejection]). The platform
 * `ImageDecoder` integration these feed — that the header listener fires before allocation and
 * that OOM is contained — is the instrumented half (wpass-zrt.5); the policy that decides
 * accept-vs-reject is exercised here on the JVM where it can be enumerated exhaustively.
 */
class BoundedBitmapDecoderTest {
    // ------------------------------------------------------------------ size cap

    @Test
    fun readBoundedBytesReturnsAllBytesWhenUnderCap() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }

        val read = readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 4096)

        assertThat(read).isNotNull()
        assertThat(read!!.toList()).isEqualTo(bytes.toList())
    }

    @Test
    fun readBoundedBytesReturnsExactlyAtCapBoundary() {
        val bytes = ByteArray(4096) { 1 }

        val read = readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 4096)

        assertThat(read).isNotNull()
        assertThat(read!!.size).isEqualTo(4096)
    }

    @Test
    fun readBoundedBytesRejectsOneByteOverCap() {
        // A large-file decompression bomb: one byte past the cap fails the whole read.
        val bytes = ByteArray(4097) { 1 }

        val read = readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 4096)

        assertThat(read).isNull()
    }

    @Test
    fun readBoundedBytesHandlesEmptyStream() {
        val read = readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 4096)

        assertThat(read).isNotNull()
        assertThat(read!!.size).isEqualTo(0)
    }

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
