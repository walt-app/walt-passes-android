package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavior lock for [BarcodeEncoder]. Pins per-format round-trip success, per-format
 * encoder-rejection paths, and the QR-specific [EncoderFailureReason.PayloadTooDense] lift.
 *
 * The encoder assumes its input has cleared [ScannableCardInputValidator]; tests here use
 * the validator's known-good fixtures (or check-digit-correct strings) so encoder failures
 * are attributable to ZXing's encodability ceiling, not to pre-validator hygiene.
 */
class BarcodeEncoderTest {
    // ---- per-format success ----

    @Test
    fun code128EncodesProducingNonTrivialMatrix() {
        // Fixture stays inside the printable-ASCII range the validator enforces for Code128
        // (ScannableFormatConstraints.PRINTABLE_ASCII_MIN..MAX, i.e. 0x20..0x7E).
        val result = BarcodeEncoder.encode("ABC123 xyz", ScannableFormat.Code128)
        val matrix = (result as EncodeResult.Success).matrix
        assertThat(matrix.width).isGreaterThan(0)
        assertThat(matrix.height).isGreaterThan(0)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    @Test
    fun code39EncodesProducingNonTrivialMatrix() {
        val result = BarcodeEncoder.encode("HELLO-123", ScannableFormat.Code39)
        val matrix = (result as EncodeResult.Success).matrix
        assertThat(matrix.width).isGreaterThan(0)
        assertThat(matrix.height).isGreaterThan(0)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    @Test
    fun ean13EncodesProducingNonTrivialMatrix() {
        // 1234567890128 — last digit is the EAN-13 checksum for "123456789012".
        val result = BarcodeEncoder.encode("1234567890128", ScannableFormat.Ean13)
        val matrix = (result as EncodeResult.Success).matrix
        assertThat(matrix.width).isGreaterThan(0)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    @Test
    fun upcAEncodesProducingNonTrivialMatrix() {
        // 036000291452 — published example UPC-A with valid checksum.
        val result = BarcodeEncoder.encode("036000291452", ScannableFormat.UpcA)
        val matrix = (result as EncodeResult.Success).matrix
        assertThat(matrix.width).isGreaterThan(0)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    @Test
    fun qrEncodesProducingSquareMatrix() {
        val result = BarcodeEncoder.encode("https://example.org/loyalty/123", ScannableFormat.Qr)
        val matrix = (result as EncodeResult.Success).matrix
        // QR codes are square. Locks the encoder dispatching to QRCodeWriter (vs a 1D writer
        // that would yield a tall thin strip).
        assertThat(matrix.width).isEqualTo(matrix.height)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    // ---- per-format encoder rejection ----

    @Test
    fun ean13RejectsWrongLengthAtWriter() {
        // Eleven digits — passes the digit charset check but EAN-13 writer wants 12+1 or 13.
        val result = BarcodeEncoder.encode("12345678901", ScannableFormat.Ean13)
        val failure = (result as EncodeResult.Failure).reason
        assertThat(failure).isInstanceOf(EncoderFailureReason.WriterRejected::class.java)
        assertThat((failure as EncoderFailureReason.WriterRejected).format).isEqualTo(ScannableFormat.Ean13)
    }

    @Test
    fun upcARejectsNonNumericAtWriter() {
        // Charset gate is upstream's job; calling the encoder directly with bad chars should
        // surface as a writer-side rejection (UPCAWriter throws IAE), not crash.
        val result = BarcodeEncoder.encode("12345678901A", ScannableFormat.UpcA)
        val failure = (result as EncodeResult.Failure).reason
        assertThat(failure).isInstanceOf(EncoderFailureReason.WriterRejected::class.java)
        assertThat((failure as EncoderFailureReason.WriterRejected).format).isEqualTo(ScannableFormat.UpcA)
    }

    @Test
    fun qrPayloadTooDenseLiftsToDedicatedArm() {
        // Largest QR version maxes out around ~2,953 bytes at error correction L; at level M
        // (the kernel's pin) the byte ceiling is ~2,331. A 4,000-char payload exceeds every
        // version regardless of mode, forcing the writer's "Data too big" path.
        val result = BarcodeEncoder.encode("A".repeat(4_000), ScannableFormat.Qr)
        val failure = (result as EncodeResult.Failure).reason
        assertThat(failure).isEqualTo(EncoderFailureReason.PayloadTooDense)
    }

    // ---- no-throw contract ----

    @Test
    fun emptyPayloadFailsWithFormatAttribution() {
        // Validator rejects empty upstream, but the encoder must still translate to Failure
        // and the failure must carry the format that was attempted, so a consumer triaging
        // an "empty input" bug across multiple format-picker positions can attribute it.
        // Locks no-throw AND per-format dispatch correctness in one test. Asserts on
        // WriterRejected.format specifically; PayloadTooDense is not a plausible arm for an
        // empty input (zero bytes can never exceed any ceiling).
        for (format in ScannableFormat.entries) {
            val result = BarcodeEncoder.encode("", format)
            val reason = (result as EncodeResult.Failure).reason
            assertThat(reason).isInstanceOf(EncoderFailureReason.WriterRejected::class.java)
            assertThat((reason as EncoderFailureReason.WriterRejected).format).isEqualTo(format)
        }
    }

    @Test
    fun proactiveQrByteCeilingHandlesNonAsciiByteCountCorrectly() {
        // Char count of 1,200 sits below QR_BYTE_CEILING_ECC_M (2,331) — but each "é" is
        // two UTF-8 bytes, so the byte count is 2,400 and the proactive guard must fire.
        // Pins the load-bearing detail that the byte-length check is on UTF-8 bytes, not
        // chars; a regression to String.length would let this slip through to ZXing.
        val payload = "é".repeat(1_200)
        val result = BarcodeEncoder.encode(payload, ScannableFormat.Qr)
        assertThat((result as EncodeResult.Failure).reason).isEqualTo(EncoderFailureReason.PayloadTooDense)
    }

    // ---- BarcodeMatrix sanity ----

    @Test
    fun matrixBoundsCheckRejectsOutOfRangeCoordinates() {
        val matrix = (BarcodeEncoder.encode("ABC", ScannableFormat.Code128) as EncodeResult.Success).matrix
        // Inside the grid: must not throw.
        matrix.isSet(0, 0)
        matrix.isSet(matrix.width - 1, matrix.height - 1)
        // Outside: bounds check fires.
        runCatching { matrix.isSet(-1, 0) }.exceptionOrNull().let {
            assertThat(it).isInstanceOf(IllegalArgumentException::class.java)
        }
        runCatching { matrix.isSet(0, matrix.height) }.exceptionOrNull().let {
            assertThat(it).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun matrixEqualityIsStructural() {
        val a = (BarcodeEncoder.encode("ABC", ScannableFormat.Code128) as EncodeResult.Success).matrix
        val b = (BarcodeEncoder.encode("ABC", ScannableFormat.Code128) as EncodeResult.Success).matrix
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())

        val different = (BarcodeEncoder.encode("XYZ", ScannableFormat.Code128) as EncodeResult.Success).matrix
        assertThat(a).isNotEqualTo(different)
    }

    private fun anyModuleSet(matrix: BarcodeMatrix): Boolean {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.isSet(x, y)) return true
            }
        }
        return false
    }
}
