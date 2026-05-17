package `is`.walt.passes.core

/**
 * The single choke point that turns a raw [ScannableCardCreateInput] into a trusted
 * [ScannableCard]. Existence of a [ScannableCard] value asserts that this validator approved
 * it (the artifact's constructor is `internal` so no other path can mint one).
 *
 * The `id` and `createdAt` parameters are caller-provided because passes-core does not mint
 * IDs (see [ScannableCardId]'s KDoc — storage assigns them) and the clock is injected so
 * tests are deterministic. The validator only judges field content; it does not allocate
 * identity or time.
 *
 * Fail-fast: returns the first violation found. Label trimmed first (a whitespace-only
 * label is empty for users), then payload (trim, empty, bidi/control, length, charset,
 * structural). Both trimmed values land on the resulting [ScannableCard].
 */
public object ScannableCardInputValidator {
    /** Display-friendly cap. Long enough for any realistic card name, short enough to render. */
    public const val MAX_LABEL_LENGTH: Int = 64

    // ReturnCount: fail-fast pipeline. Each early return surfaces a distinct rejection
    // family so the caller's `when` branches read 1:1 with validator stages.
    @Suppress("ReturnCount")
    public fun validate(
        input: ScannableCardCreateInput,
        id: ScannableCardId,
        createdAt: PassInstant,
    ): ScannableCardCreateResult {
        val trimmedLabel = input.label.trim()
        validateLabel(trimmedLabel)?.let { return ScannableCardCreateResult.InvalidLabel(it) }

        val trimmedPayload = input.payload.trim()
        validatePayload(trimmedPayload, input.format)?.let {
            return ScannableCardCreateResult.InvalidPayload(it)
        }

        return ScannableCardCreateResult.Success(
            ScannableCard(
                id = id,
                payload = trimmedPayload,
                format = input.format,
                label = trimmedLabel,
                color = input.color,
                createdAt = createdAt,
            ),
        )
    }

    @Suppress("ReturnCount")
    private fun validateLabel(label: String): LabelRejection? {
        if (label.isEmpty()) return LabelRejection.Empty
        // Bidi/control check before length so a 200-char string of bidi marks reports the
        // hazardous content, not just its size.
        for (c in label) {
            if (c.category == CharCategory.FORMAT) return LabelRejection.ContainsBidiChar
            if (c.isISOControl()) return LabelRejection.ContainsControlChar
        }
        if (label.length > MAX_LABEL_LENGTH) {
            return LabelRejection.TooLong(label.length, MAX_LABEL_LENGTH)
        }
        return null
    }

    @Suppress("ReturnCount")
    private fun validatePayload(
        payload: String,
        format: ScannableFormat,
    ): PayloadRejection? {
        if (payload.isEmpty()) return PayloadRejection.Empty
        // Bidi/control check before length/charset so the error tells the user "your input
        // contains a hidden character," not "U+0000 is not in the EAN-13 charset."
        for (c in payload) {
            if (c.category == CharCategory.FORMAT) return PayloadRejection.ContainsBidiChar
            if (c.isISOControl()) return PayloadRejection.ContainsControlChar
        }
        // Fixed-length symbologies (EAN-13, UPC-A): exact-length check surfaces WrongLength
        // for both too-short AND too-long inputs, so the consumer never has to choose between
        // TooLong and WrongLength for the same logical mistake. Variable-length symbologies
        // use the soft cap and emit TooLong.
        val required = ScannableFormatConstraints.requiredLength(format)
        if (required != null) {
            if (payload.length != required) {
                return PayloadRejection.WrongLength(payload.length, required, format)
            }
        } else {
            val max = ScannableFormatConstraints.maxPayloadLength(format)
            if (payload.length > max) return PayloadRejection.TooLong(payload.length, max)
        }
        for (c in payload) {
            if (!ScannableFormatConstraints.isAllowedChar(format, c)) {
                return PayloadRejection.WrongCharset(format, c)
            }
        }
        return ScannableFormatConstraints.validateStructural(format, payload)
    }
}
