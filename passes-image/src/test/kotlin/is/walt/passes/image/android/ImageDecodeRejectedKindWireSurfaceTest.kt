package `is`.walt.passes.image.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the decode-binder wire encoding of [ImageDecodeRejectedKind] to its explicit code
 * table. Same drift-detection discipline as `passes-pdf`'s `RejectedKindWireSurfaceTest` and
 * `passes-barcode`'s `DecodeFailureReasonWireSurfaceTest`: the wire codes are decoupled from
 * the source order of the sealed arms, so a reorder cannot silently mis-decode a rejection in
 * walt-android.
 *
 * Because the taxonomy is a sealed interface (not an enum), the full arm set is enumerated
 * explicitly here; [allKindsAreCovered] fails closed if a new arm is added without extending
 * this list and the wire table.
 */
class ImageDecodeRejectedKindWireSurfaceTest {
    private val allKinds: List<ImageDecodeRejectedKind> =
        listOf(
            ImageDecodeRejectedKind.NotAnImage,
            ImageDecodeRejectedKind.OversizedAtImport,
            ImageDecodeRejectedKind.DimensionsTooLarge,
            ImageDecodeRejectedKind.DecodeFailed,
            ImageDecodeRejectedKind.DecoderUnavailable,
        )

    @Test
    fun encodeMapsEachKindToItsDocumentedCode() {
        val expected =
            mapOf(
                ImageDecodeRejectedKind.NotAnImage to ImageDecodeRejectedKindWire.NOT_AN_IMAGE,
                ImageDecodeRejectedKind.OversizedAtImport to ImageDecodeRejectedKindWire.OVERSIZED_AT_IMPORT,
                ImageDecodeRejectedKind.DimensionsTooLarge to ImageDecodeRejectedKindWire.DIMENSIONS_TOO_LARGE,
                ImageDecodeRejectedKind.DecodeFailed to ImageDecodeRejectedKindWire.DECODE_FAILED,
                ImageDecodeRejectedKind.DecoderUnavailable to ImageDecodeRejectedKindWire.DECODER_UNAVAILABLE,
            )
        for ((kind, code) in expected) {
            assertThat(ImageDecodeRejectedKindWire.encode(kind)).isEqualTo(code)
        }
        assertThat(expected.keys).containsExactlyElementsIn(allKinds)
    }

    @Test
    fun decodeIsInverseOfEncode() {
        for (kind in allKinds) {
            assertThat(ImageDecodeRejectedKindWire.decode(ImageDecodeRejectedKindWire.encode(kind))).isEqualTo(kind)
        }
    }

    @Test
    fun codesAreStableIntegers() {
        assertThat(ImageDecodeRejectedKindWire.NOT_AN_IMAGE).isEqualTo(0)
        assertThat(ImageDecodeRejectedKindWire.OVERSIZED_AT_IMPORT).isEqualTo(1)
        assertThat(ImageDecodeRejectedKindWire.DIMENSIONS_TOO_LARGE).isEqualTo(2)
        assertThat(ImageDecodeRejectedKindWire.DECODE_FAILED).isEqualTo(3)
        assertThat(ImageDecodeRejectedKindWire.DECODER_UNAVAILABLE).isEqualTo(4)
    }

    @Test
    fun codesAreUnique() {
        val codes = allKinds.map(ImageDecodeRejectedKindWire::encode)
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun allKindsAreCovered() {
        // A new sealed arm makes this `when` non-exhaustive at compile time, forcing both this
        // list and the wire table to be extended together.
        for (kind in allKinds) {
            val branch =
                when (kind) {
                    ImageDecodeRejectedKind.NotAnImage -> "not-an-image"
                    ImageDecodeRejectedKind.OversizedAtImport -> "oversized"
                    ImageDecodeRejectedKind.DimensionsTooLarge -> "dimensions"
                    ImageDecodeRejectedKind.DecodeFailed -> "decode-failed"
                    ImageDecodeRejectedKind.DecoderUnavailable -> "unavailable"
                }
            assertThat(branch).isNotEmpty()
        }
    }

    @Test
    fun decodeRejectsUnknownCode() {
        runCatching { ImageDecodeRejectedKindWire.decode(99) }
            .onSuccess { error("Expected decode(99) to throw, got $it") }
    }
}
