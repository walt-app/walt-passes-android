package `is`.walt.passes.core.internal

import `is`.walt.passes.core.Pass

/**
 * Outcome of running [decodePassJson] over the entries map produced by [extractSafely].
 * Internal only: the parser-glue bead lifts a [Failed] into the right
 * [`is`.walt.passes.core.ParseResult] arm. The arm split on [PassJsonFailure] is finer
 * than the public surface so each failure routes independently — see [PassJsonFailure]
 * for the routing rationale.
 *
 * [Ok.pass] is constructed with `images = emptyMap()` and `locales = emptyMap()` —
 * pass.json carries no image bytes and no per-locale `.strings` files. The parser-glue
 * bead populates those at the same layer that owns image bounding and `.strings`
 * parsing.
 */
internal sealed interface PassJsonDecodeResult {
    data class Ok(val pass: Pass) : PassJsonDecodeResult

    data class Failed(val failure: PassJsonFailure) : PassJsonDecodeResult
}

/**
 * Why [decodePassJson] rejected a `pass.json` payload. Arms are deliberately finer-
 * grained than the public [`is`.walt.passes.core.MalformedReason] /
 * [`is`.walt.passes.core.UnsupportedReason] surface so the parser-glue bead can route
 * each failure independently without re-deriving it from a stringy reason once the
 * bytes have been discarded — in particular, the resource-limit arms surface which
 * [`is`.walt.passes.core.ResourceLimit] tripped, and the unsupported arms stay out of
 * the malformedness bucket.
 */
internal sealed interface PassJsonFailure {
    /** No `pass.json` entry in the entries map. */
    data object Missing : PassJsonFailure

    /** `pass.json` is present but its bytes are not parseable as JSON. */
    data object InvalidJson : PassJsonFailure

    /**
     * `pass.json` parses but is structurally not a PKPASS pass: top-level is not an
     * object, a required field is missing, two pass-style keys are present, or a
     * typed field carries the wrong JSON kind.
     */
    data object InvalidShape : PassJsonFailure

    /**
     * Pre-pass tokenizer observed nesting deeper than
     * [`is`.walt.passes.core.ParserConfig.maxJsonDepth].
     */
    data object JsonDepthExceeded : PassJsonFailure

    /**
     * Pre-pass tokenizer observed a string token longer than
     * [`is`.walt.passes.core.ParserConfig.maxJsonStringBytes].
     */
    data object JsonStringTooLong : PassJsonFailure

    /**
     * `formatVersion` is not `1`. [version] is `0` if `formatVersion` was missing or
     * non-integer; the parser-glue bead can choose to coalesce that onto
     * `InvalidPassJson` if the UI calls for it.
     */
    data class UnknownFormatVersion(val version: Int) : PassJsonFailure

    /**
     * No known top-level pass style (`boardingPass`, `eventTicket`, `coupon`,
     * `storeCard`, `generic`) is declared. [raw] is the first object-valued top-level
     * key not on the known-style or known-non-style allowlist, or `""` when no
     * plausible candidate is present.
     */
    data class UnknownPassStyle(val raw: String) : PassJsonFailure
}
