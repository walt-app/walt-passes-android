package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavior lock for [ScannableFormatConstraints], the per-symbology charset / length / structural
 * table. [ScannableCardInputValidatorTest] covers the same rules end-to-end for the formats a card
 * can actually be minted in; this suite is where the rules for the DECODE-ONLY formats live,
 * because the validator refuses those before any of them is consulted (wpass-pl7.1).
 *
 * When wpass-pl7.6 wires the writers and empties [ScannableFormatConstraints.decodeOnly], the
 * Pdf417 / Aztec cases below should gain validator-level twins rather than move.
 */
class ScannableFormatConstraintsTest {
    @Test
    fun decodeOnlyHoldsExactlyTheFormatsWithNoWriter() {
        assertThat(ScannableFormatConstraints.decodeOnly)
            .containsExactly(ScannableFormat.Pdf417, ScannableFormat.Aztec)
    }

    @Test
    fun everyFormatCarriesItsFrozenLengthCap() {
        val expected =
            mapOf(
                ScannableFormat.Code128 to 80,
                ScannableFormat.Code39 to 80,
                ScannableFormat.Ean13 to 13,
                ScannableFormat.UpcA to 12,
                ScannableFormat.Qr to 2_000,
                ScannableFormat.Pdf417 to 800,
                ScannableFormat.Aztec to 1_500,
            )
        for ((format, cap) in expected) {
            assertThat(ScannableFormatConstraints.maxPayloadLength(format)).isEqualTo(cap)
        }
        // Fails closed when a format is added without a cap being chosen for it.
        assertThat(expected.keys).containsExactlyElementsIn(ScannableFormat.entries)
    }

    @Test
    fun theTwoDimensionalFormatsAreVariableLengthAndUnstructured() {
        for (format in setOf(ScannableFormat.Qr, ScannableFormat.Pdf417, ScannableFormat.Aztec)) {
            assertThat(ScannableFormatConstraints.requiredLength(format)).isNull()
            assertThat(ScannableFormatConstraints.validateStructural(format, "anything")).isNull()
        }
    }

    @Test
    fun byteCapableFormatsAdmitAnyVisibleCharacter() {
        // Pdf417 and Aztec share Qr's "any character" rule; the bidi / control screen upstream in
        // the validator is what excludes the hazardous ones, not this per-format charset.
        for (format in setOf(ScannableFormat.Qr, ScannableFormat.Pdf417, ScannableFormat.Aztec)) {
            for (char in "bag-drop/é/👍М1") {
                assertThat(ScannableFormatConstraints.isAllowedChar(format, char)).isTrue()
            }
        }
    }

    @Test
    fun boardingPassLengthPayloadFitsBothTwoDimensionalCaps() {
        // The wpass-pl7 repro decodes to a ~126-character IATA BCBP string, and a multi-leg pass
        // is a small multiple of that. Synthetic length only: real BCBP payloads carry passenger
        // name and PNR and must not enter this repository.
        val boardingPassLength = 126
        assertThat(ScannableFormatConstraints.maxPayloadLength(ScannableFormat.Aztec))
            .isGreaterThan(boardingPassLength * 4)
        assertThat(ScannableFormatConstraints.maxPayloadLength(ScannableFormat.Pdf417))
            .isGreaterThan(boardingPassLength * 4)
    }
}
