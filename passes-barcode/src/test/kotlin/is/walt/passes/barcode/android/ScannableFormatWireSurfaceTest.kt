package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Pins the decode-binder wire encoding of [ScannableFormat] to its explicit code table.
 * Mirrors `passes-pdf`'s `RejectedKindWireSurfaceTest`: the exhaustive `when` in
 * [ScannableFormatWire.encode] guarantees every format has a code, and this test guarantees
 * the codes are the documented stable integers. Adding a format requires touching the enum,
 * the mapping table, and this test in lockstep, so a reorder in `passes-core` cannot
 * silently shift the wire for downstream consumers (walt-android).
 */
class ScannableFormatWireSurfaceTest {
    @Test
    fun encodeMapsEachFormatToItsDocumentedCode() {
        val expected =
            mapOf(
                ScannableFormat.Code128 to ScannableFormatWire.CODE_128,
                ScannableFormat.Ean13 to ScannableFormatWire.EAN_13,
                ScannableFormat.UpcA to ScannableFormatWire.UPC_A,
                ScannableFormat.Code39 to ScannableFormatWire.CODE_39,
                ScannableFormat.Qr to ScannableFormatWire.QR,
            )
        for ((format, code) in expected) {
            assertThat(ScannableFormatWire.encode(format)).isEqualTo(code)
        }
        assertThat(expected.keys).containsExactlyElementsIn(ScannableFormat.entries)
    }

    @Test
    fun decodeIsInverseOfEncode() {
        for (format in ScannableFormat.entries) {
            assertThat(ScannableFormatWire.decode(ScannableFormatWire.encode(format))).isEqualTo(format)
        }
    }

    @Test
    fun codesAreStableIntegers() {
        assertThat(ScannableFormatWire.CODE_128).isEqualTo(0)
        assertThat(ScannableFormatWire.EAN_13).isEqualTo(1)
        assertThat(ScannableFormatWire.UPC_A).isEqualTo(2)
        assertThat(ScannableFormatWire.CODE_39).isEqualTo(3)
        assertThat(ScannableFormatWire.QR).isEqualTo(4)
    }

    @Test
    fun codesAreUnique() {
        val codes = ScannableFormat.entries.map(ScannableFormatWire::encode)
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun decodeRejectsUnknownCode() {
        runCatching { ScannableFormatWire.decode(99) }
            .onSuccess { error("Expected decode(99) to throw, got $it") }
    }
}
