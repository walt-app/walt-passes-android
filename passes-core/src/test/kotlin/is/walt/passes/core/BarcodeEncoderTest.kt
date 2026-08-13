package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.ZxingBarcodeEncoder
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

    @Test
    fun aztecEncodesProducingSquareMatrix() {
        val result = BarcodeEncoder.encode(BCBP_SHAPED_PAYLOAD, ScannableFormat.Aztec)
        val matrix = (result as EncodeResult.Success).matrix
        // Aztec is square. Locks the dispatch to AztecWriter rather than the stacked PDF417
        // writer, which the same boarding-pass payload would also encode successfully.
        assertThat(matrix.width).isEqualTo(matrix.height)
        assertThat(anyModuleSet(matrix)).isTrue()
    }

    @Test
    fun pdf417EncodesProducingWideStackedMatrix() {
        val result = BarcodeEncoder.encode(BCBP_SHAPED_PAYLOAD, ScannableFormat.Pdf417)
        val matrix = (result as EncodeResult.Success).matrix
        assertThat(anyModuleSet(matrix)).isTrue()
        // Stacked, so taller than the one-module strip a 1D writer emits but far wider than
        // tall. The band also pins the 2-module MARGIN: at ZXing's default 30-module quiet
        // zone this same payload lands at 2.55:1 and fails the lower bound, which is the
        // regression that would silently letterbox the render slot.
        assertThat(matrix.height).isGreaterThan(1)
        val aspect = matrix.width.toDouble() / matrix.height
        assertThat(aspect).isGreaterThan(3.0)
        assertThat(aspect).isLessThan(5.0)
    }

    @Test
    fun theTwoDimensionalFormatsEncodeAtTheirValidatorCap() {
        // Single-byte only, which is as far as the caps reach: they count characters while
        // capacity is spent in bytes, so a multibyte payload can sit under the cap and still
        // overflow (see multibytePayloadUnderTheCharacterCapStillLiftsToPayloadTooDense).
        // Re-derive both caps if either error-correction pin rises.
        for ((format, cap) in listOf(ScannableFormat.Pdf417 to 800, ScannableFormat.Aztec to 1_500)) {
            val result = BarcodeEncoder.encode("A".repeat(cap), format)
            assertThat(result).isInstanceOf(EncodeResult.Success::class.java)
        }
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
    fun multibytePayloadUnderTheCharacterCapStillLiftsToPayloadTooDense() {
        // The caps are in CHARACTERS, capacity is in bytes: 700 CJK characters is under both
        // 2D caps and over both byte capacities (Aztec fits 623, PDF417 528). The user has to
        // be told to shorten it — WriterRejected would send them to change format instead, and
        // no arm at all leaves a card that saves and then renders blank.
        for (format in listOf(ScannableFormat.Pdf417, ScannableFormat.Aztec)) {
            val result = BarcodeEncoder.encode("東".repeat(700), format)

            assertThat((result as EncodeResult.Failure).reason)
                .isEqualTo(EncoderFailureReason.PayloadTooDense)
        }
    }

    @Test
    fun pdf417RefusesSupplementaryCharactersWithoutEchoingThePayload() {
        // Refused before the writer runs, which is what keeps the payload out of detail:
        // PDF417Writer's own "Failed to encode" message interpolates the WHOLE input, and
        // detail is the one third-party string crossing the kernel boundary. Named for the
        // refusal rather than the scrub, because the refusal is what this input exercises.
        val payload = "https://example.org/secret-token-abc123/👍"

        val reason = (BarcodeEncoder.encode(payload, ScannableFormat.Pdf417) as EncodeResult.Failure).reason

        assertThat((reason as EncoderFailureReason.WriterRejected).format).isEqualTo(ScannableFormat.Pdf417)
        assertThat(reason.detail).doesNotContain("secret-token-abc123")
    }

    @Test
    fun payloadScrubberRemovesThePayloadFromAThirdPartyMessage() {
        // Exercised directly because no input reaches it end-to-end: the surrogate refusal
        // above blocks the one ZXing message that echoes a whole payload. The scrub is the
        // backstop if that guard is ever relaxed or a reworded message starts echoing input,
        // so it needs coverage of its own or it rots unnoticed.
        val payload = "MEMBER-9988776655-SECRET"

        with(ZxingBarcodeEncoder) {
            assertThat("""Failed to encode "$payload"""".withoutPayload(payload))
                .doesNotContain(payload)
            // Messages that never carried the payload are passed through untouched, so
            // ordinary diagnostics keep their detail.
            assertThat("Bad character in input: ASCII value=233".withoutPayload(payload))
                .isEqualTo("Bad character in input: ASCII value=233")
            // An empty payload must not turn every message into redactions.
            assertThat("Empty message not allowed".withoutPayload(""))
                .isEqualTo("Empty message not allowed")
        }
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
    fun everyRosterFormatEncodes() {
        // Runs a writer per format rather than asking isCreatable(), which with an empty
        // decodeOnly set answers true without encoding anything. Fails closed on a roster
        // addition: the map has to gain a payload, and that payload has to actually render.
        val payloads =
            mapOf(
                ScannableFormat.Code128 to "ABC123 xyz",
                ScannableFormat.Code39 to "HELLO-123",
                ScannableFormat.Ean13 to "1234567890128",
                ScannableFormat.UpcA to "036000291452",
                ScannableFormat.Qr to "https://example.org/loyalty/123",
                ScannableFormat.Pdf417 to "WALT-CHECK-1",
                ScannableFormat.Aztec to "WALT-CHECK-1",
            )
        assertThat(payloads.keys).containsExactlyElementsIn(ScannableFormat.entries)

        for ((format, payload) in payloads) {
            val matrix = (BarcodeEncoder.encode(payload, format) as EncodeResult.Success).matrix
            assertThat(anyModuleSet(matrix)).isTrue()
            assertThat(format.isCreatable()).isTrue()
        }
    }

    @Test
    fun proactiveQrByteCeilingHandlesNonAsciiByteCountCorrectly() {
        // Char count of 1,200 sits below QR_BYTE_CEILING_ECC_M_BYTE_MODE (2,331) — but each
        // "é" is two UTF-8 bytes, so the byte count is 2,400 and the proactive guard must
        // fire. Pins the load-bearing detail that the byte-length check is on UTF-8 bytes,
        // not chars; a regression to String.length would let this slip through to ZXing.
        // "é" is outside the QR alphanumeric mode set, so the proactive byte-mode check is
        // reachable (pure-alphanumeric payloads of the same byte count fall through to
        // ZXing instead — see [denseNumericQrPayloadEncodesViaNumericMode]).
        val payload = "é".repeat(1_200)
        val result = BarcodeEncoder.encode(payload, ScannableFormat.Qr)
        assertThat((result as EncodeResult.Failure).reason).isEqualTo(EncoderFailureReason.PayloadTooDense)
    }

    @Test
    fun denseNumericQrPayloadEncodesViaNumericMode() {
        // Pure-digit payload of 3,000 chars: above QR_BYTE_CEILING_ECC_M_BYTE_MODE (2,331)
        // and above QR v40-M alphanumeric capacity (~3,391 chars — close but under), but
        // well under v40-M numeric capacity (~5,596 digits). ZXing's QRCodeWriter
        // auto-selects numeric mode and encodes successfully. Pins that the proactive
        // byte-mode check is gated by the alphanumeric-set membership test — a regression
        // that re-introduces an unconditional byte-count pre-check would over-reject this
        // input as PayloadTooDense even though ZXing can encode it.
        val payload = "1".repeat(3_000)
        val result = BarcodeEncoder.encode(payload, ScannableFormat.Qr)
        assertThat(result).isInstanceOf(EncodeResult.Success::class.java)
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

    private companion object {
        /**
         * A synthetic payload of IATA BCBP shape and length — the sizing case the Pdf417 /
         * Aztec writers exist to serve. Invented, never a decoded boarding pass: a real BCBP
         * string carries passenger name and PNR, which wpass-pl7 bars from any committed
         * fixture, log line or bead note.
         */
        const val BCBP_SHAPED_PAYLOAD =
            "M1TEST/PASSENGER      EABC123 CPHLHRSK 0501 227M014A0058 147>5180      " +
                "B1A              2A05512345678901 0                          "
    }
}
