package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavior lock for [ScannableCardInputValidator]. Pins per-format charset, length, bidi /
 * control rejection, and EAN-13 / UPC-A check-digit rules. Companion to the surface lock in
 * [ScannableCardSurfaceTest], which covers the shape of the result type.
 */
class ScannableCardInputValidatorTest {
    private val id = ScannableCardId("test")
    private val now = PassInstant(epochMillis = 1_800_000_000_000L)

    // ---- per-format success ----

    @Test
    fun code128HappyPath() {
        val result = validate("ABC123 xyz", ScannableFormat.Code128)
        assertSuccessWithPayload(result, "ABC123 xyz")
    }

    @Test
    fun code39HappyPath() {
        val result = validate("ABC-123 +%/.$", ScannableFormat.Code39)
        assertSuccessWithPayload(result, "ABC-123 +%/.$")
    }

    @Test
    fun ean13HappyPathWithValidCheckDigit() {
        val result = validate("1234567890120", ScannableFormat.Ean13)
        assertSuccessWithPayload(result, "1234567890120")
    }

    @Test
    fun upcAHappyPathWithValidCheckDigit() {
        val result = validate("123456789012", ScannableFormat.UpcA)
        assertSuccessWithPayload(result, "123456789012")
    }

    @Test
    fun qrHappyPathAcceptsUtf8() {
        val result = validate("https://example.org/é/👍", ScannableFormat.Qr)
        assertSuccessWithPayload(result, "https://example.org/é/👍")
    }

    // ---- length caps ----

    @Test
    fun code128TooLong() {
        val payload = "A".repeat(81)
        val rejection = expectPayloadRejection(payload, ScannableFormat.Code128)
        assertThat(rejection).isInstanceOf(PayloadRejection.TooLong::class.java)
        rejection as PayloadRejection.TooLong
        assertThat(rejection.actual).isEqualTo(81)
        assertThat(rejection.max).isEqualTo(80)
    }

    @Test
    fun code39TooLong() {
        val rejection = expectPayloadRejection("A".repeat(81), ScannableFormat.Code39)
        assertThat(rejection).isInstanceOf(PayloadRejection.TooLong::class.java)
    }

    @Test
    fun qrTooLong() {
        val rejection = expectPayloadRejection("x".repeat(2001), ScannableFormat.Qr)
        assertThat(rejection).isInstanceOf(PayloadRejection.TooLong::class.java)
    }

    // ---- charset violations ----

    @Test
    fun code128RejectsNonAsciiBecauseOfCharset() {
        val rejection = expectPayloadRejection("ABCé", ScannableFormat.Code128)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongCharset::class.java)
        rejection as PayloadRejection.WrongCharset
        assertThat(rejection.format).isEqualTo(ScannableFormat.Code128)
        assertThat(rejection.offendingChar).isEqualTo('é')
    }

    @Test
    fun code39RejectsLowercaseLetters() {
        val rejection = expectPayloadRejection("abc", ScannableFormat.Code39)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongCharset::class.java)
    }

    @Test
    fun ean13RejectsLetters() {
        val rejection = expectPayloadRejection("12345678A0120", ScannableFormat.Ean13)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongCharset::class.java)
    }

    @Test
    fun upcARejectsLetters() {
        val rejection = expectPayloadRejection("12345A789012", ScannableFormat.UpcA)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongCharset::class.java)
    }

    // ---- bidi / control rejection across all formats ----

    @Test
    fun code128BidiCharRejected() {
        val rejection = expectPayloadRejection("AB‮C", ScannableFormat.Code128)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsBidiChar)
    }

    @Test
    fun code39BidiCharRejected() {
        val rejection = expectPayloadRejection("AB‮C", ScannableFormat.Code39)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsBidiChar)
    }

    @Test
    fun ean13BidiCharRejected() {
        val rejection = expectPayloadRejection("1234567‮890120", ScannableFormat.Ean13)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsBidiChar)
    }

    @Test
    fun upcABidiCharRejected() {
        val rejection = expectPayloadRejection("12345‮789012", ScannableFormat.UpcA)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsBidiChar)
    }

    @Test
    fun qrBidiCharRejected() {
        val rejection = expectPayloadRejection("hello‮world", ScannableFormat.Qr)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsBidiChar)
    }

    @Test
    fun nullByteRejectedAsControlChar() {
        val rejection = expectPayloadRejection("AB\u0000C", ScannableFormat.Code128)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsControlChar)
    }

    @Test
    fun qrNullByteRejectedAsControlChar() {
        val rejection = expectPayloadRejection("hi\u0000there", ScannableFormat.Qr)
        assertThat(rejection).isEqualTo(PayloadRejection.ContainsControlChar)
    }

    // ---- trim / empty ----

    @Test
    fun payloadIsTrimmedBeforeValidation() {
        val result = validate("  ABC  ", ScannableFormat.Code128)
        assertSuccessWithPayload(result, "ABC")
    }

    @Test
    fun whitespaceOnlyPayloadIsEmpty() {
        val rejection = expectPayloadRejection("   ", ScannableFormat.Code128)
        assertThat(rejection).isEqualTo(PayloadRejection.Empty)
    }

    @Test
    fun emptyPayloadRejected() {
        val rejection = expectPayloadRejection("", ScannableFormat.Code128)
        assertThat(rejection).isEqualTo(PayloadRejection.Empty)
    }

    // ---- EAN-13 structural ----

    @Test
    fun ean13WrongLengthRejected() {
        val rejection = expectPayloadRejection("123456789012", ScannableFormat.Ean13)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongLength::class.java)
        rejection as PayloadRejection.WrongLength
        assertThat(rejection.actual).isEqualTo(12)
        assertThat(rejection.required).isEqualTo(13)
        assertThat(rejection.format).isEqualTo(ScannableFormat.Ean13)
    }

    @Test
    fun ean13InvalidCheckDigitRejected() {
        val rejection = expectPayloadRejection("1234567890121", ScannableFormat.Ean13)
        assertThat(rejection).isInstanceOf(PayloadRejection.InvalidCheckDigit::class.java)
        rejection as PayloadRejection.InvalidCheckDigit
        assertThat(rejection.format).isEqualTo(ScannableFormat.Ean13)
    }

    // ---- UPC-A structural ----

    @Test
    fun upcAShortLengthRejected() {
        // 11 digits — below the UPC-A length cap so it passes the TooLong gate and
        // surfaces the structural WrongLength rejection instead.
        val rejection = expectPayloadRejection("12345678901", ScannableFormat.UpcA)
        assertThat(rejection).isInstanceOf(PayloadRejection.WrongLength::class.java)
        rejection as PayloadRejection.WrongLength
        assertThat(rejection.actual).isEqualTo(11)
        assertThat(rejection.required).isEqualTo(12)
    }

    @Test
    fun upcAInvalidCheckDigitRejected() {
        val rejection = expectPayloadRejection("123456789013", ScannableFormat.UpcA)
        assertThat(rejection).isInstanceOf(PayloadRejection.InvalidCheckDigit::class.java)
    }

    // ---- label ----

    @Test
    fun labelEmptyRejected() {
        val result = validateInput(payload = "ABC", format = ScannableFormat.Code128, label = "")
        val invalid = result as ScannableCardCreateResult.InvalidLabel
        assertThat(invalid.reason).isEqualTo(LabelRejection.Empty)
    }

    @Test
    fun labelBidiCharRejected() {
        val result = validateInput(payload = "ABC", format = ScannableFormat.Code128, label = "Card‮")
        val invalid = result as ScannableCardCreateResult.InvalidLabel
        assertThat(invalid.reason).isEqualTo(LabelRejection.ContainsBidiChar)
    }

    @Test
    fun labelControlCharRejected() {
        val result = validateInput(payload = "ABC", format = ScannableFormat.Code128, label = "Card\u0007")
        val invalid = result as ScannableCardCreateResult.InvalidLabel
        assertThat(invalid.reason).isEqualTo(LabelRejection.ContainsControlChar)
    }

    @Test
    fun labelTooLongRejected() {
        val long = "L".repeat(101)
        val result = validateInput(payload = "ABC", format = ScannableFormat.Code128, label = long)
        val invalid = result as ScannableCardCreateResult.InvalidLabel
        assertThat(invalid.reason).isInstanceOf(LabelRejection.TooLong::class.java)
        val reason = invalid.reason as LabelRejection.TooLong
        assertThat(reason.actual).isEqualTo(101)
        assertThat(reason.max).isEqualTo(100)
    }

    @Test
    fun labelExactlyAtCapAccepted() {
        val result = validateInput(payload = "ABC", format = ScannableFormat.Code128, label = "L".repeat(100))
        assertThat(result).isInstanceOf(ScannableCardCreateResult.Success::class.java)
    }

    // ---- trim semantics on success path ----

    @Test
    fun successCardCarriesTrimmedPayloadNotRaw() {
        val result = validate("  hello  ", ScannableFormat.Code128)
        val success = result as ScannableCardCreateResult.Success
        assertThat(success.card.payload).isEqualTo("hello")
        assertThat(success.card.payload).doesNotContain(" ")
    }

    @Test
    fun successCardCarriesCallerIdAndTimestamp() {
        val result = validate("ABC", ScannableFormat.Code128)
        val success = result as ScannableCardCreateResult.Success
        assertThat(success.card.id).isEqualTo(id)
        assertThat(success.card.createdAt).isEqualTo(now)
    }

    // ---- helpers ----

    private fun validate(
        payload: String,
        format: ScannableFormat,
    ): ScannableCardCreateResult = validateInput(payload = payload, format = format, label = "Card")

    private fun validateInput(
        payload: String,
        format: ScannableFormat,
        label: String,
    ): ScannableCardCreateResult =
        ScannableCardInputValidator.validate(
            input =
                ScannableCardCreateInput(
                    payload = payload,
                    format = format,
                    label = label,
                    color = null,
                ),
            id = id,
            createdAt = now,
        )

    private fun expectPayloadRejection(
        payload: String,
        format: ScannableFormat,
    ): PayloadRejection {
        val result = validate(payload, format)
        assertThat(result).isInstanceOf(ScannableCardCreateResult.InvalidPayload::class.java)
        return (result as ScannableCardCreateResult.InvalidPayload).reason
    }

    private fun assertSuccessWithPayload(
        result: ScannableCardCreateResult,
        expectedPayload: String,
    ) {
        assertThat(result).isInstanceOf(ScannableCardCreateResult.Success::class.java)
        val success = result as ScannableCardCreateResult.Success
        assertThat(success.card.payload).isEqualTo(expectedPayload)
    }
}
