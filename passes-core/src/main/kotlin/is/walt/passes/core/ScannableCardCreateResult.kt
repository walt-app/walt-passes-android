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
 * Why a user-typed payload was rejected before encoding. Empty here on purpose — concrete
 * arms (length caps, charset rules, bidi/control-character rejection) land in Child 4
 * (wpass-lzi.4).
 */
public sealed interface PayloadRejection

/** Why a user-typed label was rejected. Arms land with [PayloadRejection] in Child 4. */
public sealed interface LabelRejection

/** Why the encoder rejected a structurally valid payload. Arms land in Child 3 (wpass-lzi.3). */
public sealed interface EncoderFailureReason
