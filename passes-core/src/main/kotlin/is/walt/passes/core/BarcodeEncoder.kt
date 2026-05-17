package `is`.walt.passes.core

import `is`.walt.passes.core.internal.ZxingBarcodeEncoder

/**
 * Encodes a [ScannableCard]'s `(payload, format)` into a [BarcodeMatrix] the renderer can
 * draw. Pure synchronous function — ZXing's writers complete in well under 50ms for any
 * realistic input. Callers that want to keep the main thread free wrap the call in their
 * own dispatcher; the kernel does not pick a thread for the caller.
 *
 * **Validation boundary.** The encoder assumes its input has already cleared
 * [ScannableCardInputValidator]. It does NOT re-run charset, length, or check-digit checks;
 * those belong upstream so the consumer's error UI maps validation rejections to the right
 * field (payload vs. label) before the encoder is ever called. The arms of [EncodeResult]
 * are therefore narrow — anything not caused by ZXing's encodability rules is the validator's
 * problem.
 *
 * **No-throw contract.** Any [Throwable] from the underlying ZXing writer is captured and
 * translated into an [EncodeResult.Failure] arm; the caller observes the outcome via
 * exhaustive `when` and never via `try/catch`. This matches the kernel-wide pattern set by
 * [ParseResult] and [ScannableCardCreateResult].
 *
 * **API stability.** ZXing's `BitMatrix` is deliberately not visible on this surface — the
 * matrix is wrapped in the kernel's own [BarcodeMatrix] so the encoder is replaceable
 * without breaking consumers.
 */
public object BarcodeEncoder {
    public fun encode(
        payload: String,
        format: ScannableFormat,
    ): EncodeResult = ZxingBarcodeEncoder.encode(payload, format)
}

/**
 * Outcome of [BarcodeEncoder.encode]. Sibling family to [ScannableCardCreateResult] —
 * encoder failures bubble up into [ScannableCardCreateResult.EncoderFailure] when an
 * orchestrator wraps validation + encoding into a single create flow (storage layer,
 * Child 6). The two surface types are kept separate so the encoder can be called on its
 * own (e.g., re-encoding for a render-time size change) without dragging the create-flow
 * arms into a render-time call site.
 */
public sealed interface EncodeResult {
    public data class Success(public val matrix: BarcodeMatrix) : EncodeResult

    public data class Failure(public val reason: EncoderFailureReason) : EncodeResult
}
