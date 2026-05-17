package `is`.walt.passes.core

/**
 * Single source of truth for per-symbology charset, length cap, required length, and
 * structural-checksum rules. Hook point for the wpass-lzi threat model — if a constraint
 * here changes, the threat-model doc must change alongside it.
 *
 * Kept `internal` so the validator is the only callable boundary; the consumer never picks
 * "is this character allowed" off of this object directly. Bidi / control-character checks
 * live in [ScannableCardInputValidator] because they apply uniformly across all formats.
 */
internal object ScannableFormatConstraints {
    /** Soft cap on payload length per symbology. Numeric symbologies use their exact length. */
    fun maxPayloadLength(format: ScannableFormat): Int =
        when (format) {
            ScannableFormat.Code128 -> CODE128_MAX
            ScannableFormat.Code39 -> CODE39_MAX
            ScannableFormat.Ean13 -> EAN13_LENGTH
            ScannableFormat.UpcA -> UPCA_LENGTH
            ScannableFormat.Qr -> QR_MAX
        }

    /** Non-null only for fixed-length numeric symbologies (EAN-13, UPC-A). */
    fun requiredLength(format: ScannableFormat): Int? =
        when (format) {
            ScannableFormat.Ean13 -> EAN13_LENGTH
            ScannableFormat.UpcA -> UPCA_LENGTH
            else -> null
        }

    /**
     * True if [char] is in the symbology's allowed charset. Bidi / control characters are
     * rejected by the validator before this is consulted, so the per-format set need only
     * describe the visible alphabet.
     */
    fun isAllowedChar(
        format: ScannableFormat,
        char: Char,
    ): Boolean =
        when (format) {
            // Code128 subsets A/B/C between them cover printable ASCII; bytes outside that
            // range are rejected here (the upstream control-char check catches NUL etc first,
            // so this guard only fires on extended-Unicode input like "é").
            ScannableFormat.Code128 -> char.code in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX
            ScannableFormat.Code39 -> char in CODE39_ALLOWED
            ScannableFormat.Ean13, ScannableFormat.UpcA -> char in '0'..'9'
            ScannableFormat.Qr -> true
        }

    /**
     * Structural validation for fixed-length symbologies. Returns the rejection arm to surface
     * (length mismatch wins over check-digit mismatch), or null when the payload structurally
     * conforms.
     */
    fun validateStructural(
        format: ScannableFormat,
        payload: String,
    ): PayloadRejection? =
        when (format) {
            ScannableFormat.Ean13 -> validateEan13(payload)
            ScannableFormat.UpcA -> validateUpcA(payload)
            ScannableFormat.Code128, ScannableFormat.Code39, ScannableFormat.Qr -> null
        }

    // Length already enforced by the validator via [requiredLength]; structural check assumes
    // a correctly-sized payload and only verifies the check digit.
    private fun validateEan13(payload: String): PayloadRejection? {
        // EAN-13: rightmost digit is the check digit. Weights from right (excluding check
        // digit) alternate 1, 3, 1, 3 ...; sum mod 10, then (10 - sum mod 10) mod 10.
        val digits = payload.map { it.digitToInt() }
        val expected = ean13CheckDigit(digits.dropLast(1))
        return if (expected == digits.last()) null else PayloadRejection.InvalidCheckDigit(ScannableFormat.Ean13)
    }

    private fun validateUpcA(payload: String): PayloadRejection? {
        // UPC-A: weights from left (excluding check digit) alternate 3, 1, 3, 1 ...; equivalent
        // to EAN-13 with a leading implicit zero, but expressed directly here for clarity.
        val digits = payload.map { it.digitToInt() }
        val expected = upcACheckDigit(digits.dropLast(1))
        return if (expected == digits.last()) null else PayloadRejection.InvalidCheckDigit(ScannableFormat.UpcA)
    }

    private fun ean13CheckDigit(twelveDigits: List<Int>): Int {
        var sum = 0
        // Index from the right: position 0 = weight 1, position 1 = weight 3, alternating.
        for ((indexFromRight, digit) in twelveDigits.asReversed().withIndex()) {
            sum += digit * if (indexFromRight % 2 == 0) 1 else 3
        }
        return (10 - sum % 10) % 10
    }

    private fun upcACheckDigit(elevenDigits: List<Int>): Int {
        var sum = 0
        for ((indexFromLeft, digit) in elevenDigits.withIndex()) {
            sum += digit * if (indexFromLeft % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10
    }

    private const val CODE128_MAX = 80
    private const val CODE39_MAX = 80
    private const val EAN13_LENGTH = 13
    private const val UPCA_LENGTH = 12
    private const val QR_MAX = 2000
    private const val PRINTABLE_ASCII_MIN = 0x20
    private const val PRINTABLE_ASCII_MAX = 0x7E

    private val CODE39_ALLOWED: Set<Char> =
        buildSet {
            addAll('A'..'Z')
            addAll('0'..'9')
            addAll(listOf(' ', '-', '.', '$', '/', '+', '%'))
        }
}
