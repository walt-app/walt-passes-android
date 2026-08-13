package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavior lock for [ScannableFormatConstraints], the per-symbology charset / length / structural
 * table. [ScannableCardInputValidatorTest] covers the same rules end-to-end for the formats a card
 * can actually be minted in — which, since wpass-pl7.6 wired the Pdf417 / Aztec writers, is
 * every one of them.
 */
class ScannableFormatConstraintsTest {
    @Test
    fun noFormatIsDecodeOnly() {
        // Empty since wpass-pl7.6: every roster member both decodes and encodes. The set stays
        // on the surface as the create-boundary refusal a future decode-first addition needs.
        assertThat(ScannableFormatConstraints.decodeOnly).isEmpty()
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
        // isAllowedChar takes a Char, i.e. a UTF-16 code unit, so an astral codepoint reaches it
        // as its two surrogates — included deliberately, since that is how an emoji payload
        // actually arrives.
        val surrogates = "👍" // U+1F44D, one high + one low surrogate
        check(surrogates.length == 2)
        for (format in setOf(ScannableFormat.Qr, ScannableFormat.Pdf417, ScannableFormat.Aztec)) {
            for (char in "bag-drop/é/М1" + surrogates) {
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
