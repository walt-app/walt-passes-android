package `is`.walt.passes.core

/**
 * Outcome of attempting to construct a [ScannableCard] from a [ScannableCardCreateInput].
 * The arms partition along the same trust / recoverability lines [ParseResult] uses, so the
 * consumer UI can render distinct states without inspecting exception messages:
 *
 *  - [Success]: a usable [ScannableCard], ready to persist and render.
 *  - [InvalidPayload]: the user-typed payload violated a length cap, charset rule, or
 *    contained bidi/format/control characters that would compromise display safety.
 *  - [InvalidLabel]: the user-typed label violated the same hygiene rules.
 *  - [UnsupportedFormat]: a [ScannableFormat] this build does not yet implement encoders
 *    for. Surfaced separately from validation failures because it is a build-time
 *    capability gap, not a user-input problem.
 *  - [EncoderFailure]: the ZXing encoder rejected an otherwise-valid payload at encode time
 *    (e.g. a Code39 input that passes the input charset check but exceeds the symbology's
 *    encodable density). Distinct arm so telemetry can distinguish "user typed something
 *    bad" from "our encoder said no."
 *
 * The result family is a no-throw contract. A caller observes outcomes via exhaustive
 * `when`, never via try/catch.
 */
public sealed interface ScannableCardCreateResult {
    public data class Success(public val card: ScannableCard) : ScannableCardCreateResult

    public data class InvalidPayload(public val reason: PayloadRejection) : ScannableCardCreateResult

    public data class InvalidLabel(public val reason: LabelRejection) : ScannableCardCreateResult

    public data class UnsupportedFormat(public val format: ScannableFormat) : ScannableCardCreateResult

    public data class EncoderFailure(public val reason: String) : ScannableCardCreateResult
}

/**
 * Why a user-typed payload was rejected before encoding. Empty here on purpose — the
 * concrete arms (length caps, charset rules, bidi/control-character rejection) land with
 * the validator implementation in Child 4 (wpass-lzi.4). Defining the sealed-interface
 * shape now lets [ScannableCardCreateResult.InvalidPayload] compile and be exhaustively
 * matched in tests before the validator exists.
 */
public sealed interface PayloadRejection

/**
 * Why a user-typed label was rejected. Same staging as [PayloadRejection]: arms land in
 * Child 4 (wpass-lzi.4); the shape is defined here so the result family is complete.
 */
public sealed interface LabelRejection
