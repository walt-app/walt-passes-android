package `is`.walt.passes.core

/**
 * Outcome of attempting to construct a [ScannableCard] from a [ScannableCardCreateInput].
 * The arms partition along trust / recoverability lines so the consumer UI can render
 * distinct states without inspecting exception messages. No-throw contract: callers observe
 * outcomes via exhaustive `when`, never via try/catch.
 */
public sealed interface ScannableCardCreateResult {
    public data class Success(public val card: ScannableCard) : ScannableCardCreateResult

    public data class InvalidPayload(public val reason: PayloadRejection) : ScannableCardCreateResult

    public data class InvalidLabel(public val reason: LabelRejection) : ScannableCardCreateResult

    /** Build-time capability gap — the encoder for [format] is not wired in this kernel build. */
    public data class UnsupportedFormat(public val format: ScannableFormat) : ScannableCardCreateResult

    /**
     * The encoder rejected an otherwise-valid payload at encode time (e.g. a Code39 input
     * that passes charset checks but exceeds the symbology's encodable density). Distinct
     * from validation failures so telemetry can distinguish "user typed something bad"
     * from "the encoder said no."
     */
    public data class EncoderFailure(public val reason: EncoderFailureReason) : ScannableCardCreateResult
}

/**
 * Why a user-typed payload was rejected before encoding. Distinct arms so the consumer UI
 * can surface a specific error string without inspecting raw input.
 */
public sealed interface PayloadRejection {
    /** Payload exceeds the per-format length cap (see [ScannableFormatConstraints]). */
    public data class TooLong(public val actual: Int, public val max: Int) : PayloadRejection

    /** A character is not in the symbology's allowed charset (e.g. a letter in EAN-13). */
    public data class WrongCharset(
        public val format: ScannableFormat,
        public val offendingChar: Char,
    ) : PayloadRejection

    /** Length mismatch for fixed-length symbologies (EAN-13 must be 13, UPC-A must be 12). */
    public data class WrongLength(
        public val actual: Int,
        public val required: Int,
        public val format: ScannableFormat,
    ) : PayloadRejection

    /** Mod-10 check digit did not match for a fixed-length symbology (EAN-13, UPC-A). */
    public data class InvalidCheckDigit(public val format: ScannableFormat) : PayloadRejection

    /** Payload contained a Unicode Cc (Control) codepoint — rejected for all formats. */
    public object ContainsControlChar : PayloadRejection

    /** Payload contained a Unicode Cf (Format) codepoint — bidi controls etc., all formats. */
    public object ContainsBidiChar : PayloadRejection

    /** Payload was empty (after whitespace trimming). */
    public object Empty : PayloadRejection
}

/**
 * Why a user-typed label was rejected. Mirrors the bidi/control hygiene of [PayloadRejection]
 * because the label is rendered alongside untrusted user content.
 */
public sealed interface LabelRejection {
    /** Label exceeded the display-friendly cap (see [ScannableCardInputValidator]). */
    public data class TooLong(public val actual: Int, public val max: Int) : LabelRejection

    /** Label contained a Unicode Cf (Format) codepoint — bidi controls etc. */
    public object ContainsBidiChar : LabelRejection

    /** Label contained a Unicode Cc (Control) codepoint. */
    public object ContainsControlChar : LabelRejection

    /** Label was empty. */
    public object Empty : LabelRejection
}

/**
 * Why the encoder rejected a structurally valid payload — i.e. one that already cleared
 * [ScannableCardInputValidator]. These arms exist because per-symbology validation cannot
 * fully predict ZXing's encodability ceiling: a payload of the correct length and charset
 * can still fail to fit a chosen symbology's density rules (most often Code39 with a
 * pathological pattern, or QR pushed past its largest version). The arms partition by
 * recoverability so the consumer UI can suggest a different format vs. surfacing an opaque
 * "try again" message.
 */
public sealed interface EncoderFailureReason {
    /**
     * The underlying ZXing writer rejected the payload at encode time. [format] identifies
     * which writer failed (the consumer may suggest switching to a denser symbology). The
     * raw ZXing exception message is preserved on [detail] for the consumer's diagnostic
     * surface; it is not user-facing copy and may be empty when ZXing did not provide one.
     */
    public data class WriterRejected(
        public val format: ScannableFormat,
        public val detail: String,
    ) : EncoderFailureReason

    /**
     * The payload is too dense to encode at the symbology's maximum version (only QR can
     * surface this — the 1D writers reject density mismatches under [WriterRejected]). Distinct
     * arm so the consumer UI can specifically suggest shortening the payload rather than
     * switching format.
     */
    public object PayloadTooDense : EncoderFailureReason
}
