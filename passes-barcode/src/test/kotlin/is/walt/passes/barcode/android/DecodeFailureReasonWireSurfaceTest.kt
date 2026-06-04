package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.DecodeFailureReason
import org.junit.Test

/**
 * Pins the decode-binder wire encoding of [DecodeFailureReason] to its explicit code table.
 * Companion to [ScannableFormatWireSurfaceTest]; same drift-detection discipline as
 * `passes-pdf`'s `RejectedKindWireSurfaceTest`.
 */
class DecodeFailureReasonWireSurfaceTest {
    @Test
    fun encodeMapsEachReasonToItsDocumentedCode() {
        val expected =
            mapOf(
                DecodeFailureReason.SourceUnreadable to DecodeFailureReasonWire.SOURCE_UNREADABLE,
                DecodeFailureReason.ImageDecodeFailed to DecodeFailureReasonWire.IMAGE_DECODE_FAILED,
                DecodeFailureReason.ImageTooLarge to DecodeFailureReasonWire.IMAGE_TOO_LARGE,
                DecodeFailureReason.UnsupportedBarcodeFormat to DecodeFailureReasonWire.UNSUPPORTED_BARCODE_FORMAT,
                DecodeFailureReason.DecoderUnavailable to DecodeFailureReasonWire.DECODER_UNAVAILABLE,
            )
        for ((reason, code) in expected) {
            assertThat(DecodeFailureReasonWire.encode(reason)).isEqualTo(code)
        }
        assertThat(expected.keys).containsExactlyElementsIn(DecodeFailureReason.entries)
    }

    @Test
    fun decodeIsInverseOfEncode() {
        for (reason in DecodeFailureReason.entries) {
            assertThat(DecodeFailureReasonWire.decode(DecodeFailureReasonWire.encode(reason))).isEqualTo(reason)
        }
    }

    @Test
    fun codesAreStableIntegers() {
        assertThat(DecodeFailureReasonWire.SOURCE_UNREADABLE).isEqualTo(0)
        assertThat(DecodeFailureReasonWire.IMAGE_DECODE_FAILED).isEqualTo(1)
        assertThat(DecodeFailureReasonWire.IMAGE_TOO_LARGE).isEqualTo(2)
        assertThat(DecodeFailureReasonWire.UNSUPPORTED_BARCODE_FORMAT).isEqualTo(3)
        assertThat(DecodeFailureReasonWire.DECODER_UNAVAILABLE).isEqualTo(4)
    }

    @Test
    fun codesAreUnique() {
        val codes = DecodeFailureReason.entries.map(DecodeFailureReasonWire::encode)
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun decodeRejectsUnknownCode() {
        runCatching { DecodeFailureReasonWire.decode(99) }
            .onSuccess { error("Expected decode(99) to throw, got $it") }
    }
}
