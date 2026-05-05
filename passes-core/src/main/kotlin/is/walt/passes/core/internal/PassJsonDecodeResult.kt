package `is`.walt.passes.core.internal

import `is`.walt.passes.core.Pass

/**
 * Outcome of running [decodePassJson] over the entries map produced by [extractSafely].
 * Internal only: the parser-glue bead lifts a [Failed] into the right
 * [`is`.walt.passes.core.ParseResult] arm. The arm split is finer-grained than the
 * frozen public [`is`.walt.passes.core.MalformedReason] surface so the resource-limit
 * arms (`JsonDepthExceeded`, `JsonStringTooLong`) can route to
 * [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded] with the right
 * [`is`.walt.passes.core.ResourceLimit] without re-deriving it from a stringy reason,
 * and the unsupported arms (`UnknownFormatVersion`, `UnknownPassStyle`) can route to
 * [`is`.walt.passes.core.ParseResult.Unsupported] without collapsing onto malformedness.
 *
 * [Ok.pass] is constructed with `images = emptyMap()` and `locales = emptyMap()` —
 * pass.json carries no image bytes and no per-locale `.strings` files. The parser-glue
 * bead populates those from the entries map at the same layer that owns image bounding
 * and `.strings` parsing, so this slice does not have to know about them.
 */
internal sealed interface PassJsonDecodeResult {
    data class Ok(val pass: Pass) : PassJsonDecodeResult

    data class Failed(val failure: PassJsonFailure) : PassJsonDecodeResult
}

/**
 * Why [decodePassJson] rejected a `pass.json` payload. The arms partition along the
 * same trust / structural / unsupported axis the public
 * [`is`.walt.passes.core.ParseResult] uses, but at finer granularity so the parser-glue
 * bead can route each arm independently:
 *
 *  - [Missing] / [InvalidJson] / [InvalidShape] are structural — pass.json is absent or
 *    its bytes are not parseable as the expected object shape. The parser-glue bead
 *    routes [Missing] to [`is`.walt.passes.core.MalformedReason.MissingPassJson] and the
 *    other two to [`is`.walt.passes.core.MalformedReason.InvalidPassJson].
 *  - [JsonDepthExceeded] / [JsonStringTooLong] are resource-limit hits — pass.json is
 *    syntactically reasonable but exceeds [`is`.walt.passes.core.ParserConfig] guards.
 *    The parser-glue bead routes them to
 *    [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded] with
 *    [`is`.walt.passes.core.ResourceLimit.JsonDepth] /
 *    [`is`.walt.passes.core.ResourceLimit.JsonStringSize].
 *  - [UnknownFormatVersion] / [UnknownPassStyle] are unsupported — pass.json is well
 *    formed but uses a feature this parser does not implement. The parser-glue bead
 *    routes them to [`is`.walt.passes.core.ParseResult.Unsupported] with
 *    [`is`.walt.passes.core.UnsupportedReason.FormatVersion] /
 *    [`is`.walt.passes.core.UnsupportedReason.UnknownPassStyle].
 *
 * Why split the resource-limit arms here rather than collapsing them onto [InvalidShape]
 * and inferring later: the parser-glue bead has no visibility into which limit tripped
 * once the bytes have been discarded. Surfacing the arm here lets it map straight onto
 * the public [`is`.walt.passes.core.ResourceLimit] enum without reparse heuristics.
 */
internal sealed interface PassJsonFailure {
    /** The entries map handed back by [extractSafely] does not contain `pass.json`. */
    data object Missing : PassJsonFailure

    /** `pass.json` is present but its bytes are not parseable as JSON. */
    data object InvalidJson : PassJsonFailure

    /**
     * `pass.json` parses but is structurally not a PKPASS pass: top-level is not an
     * object, a required field is missing, or a typed field carries the wrong JSON
     * kind (e.g. a field-row entry is a string instead of an object).
     */
    data object InvalidShape : PassJsonFailure

    /**
     * The pre-pass JSON tokenizer observed an open-bracket nesting level greater than
     * [`is`.walt.passes.core.ParserConfig.maxJsonDepth]. Surfaced here rather than from
     * the kotlinx.serialization decoder because the kotlinx parser does not enforce a
     * depth limit natively, and post-validating the parsed tree is too late — a
     * deeply-nested adversarial payload has already been allocated by the time the
     * tree is constructed.
     */
    data object JsonDepthExceeded : PassJsonFailure

    /**
     * The pre-pass tokenizer observed a JSON string token whose source-byte length
     * exceeded [`is`.walt.passes.core.ParserConfig.maxJsonStringBytes]. Source-byte
     * length is a slight overcount of the decoded value (escape sequences expand to
     * fewer bytes), but the guard's intent is to bound JSON-bomb expansion, and the
     * overcount is conservative — never letting an over-budget string through. Same
     * pre-pass rationale as [JsonDepthExceeded].
     */
    data object JsonStringTooLong : PassJsonFailure

    /**
     * `pass.json` declares a `formatVersion` that is not `1`. PKPASS has only ever
     * defined version 1; surfaced as a distinct arm so a future spec bump shows up
     * as an unsupported feature, not as malformedness.
     *
     * [version] is `0` if `formatVersion` was missing or non-integer — pass.json is
     * malformed in that case, but a missing version is operationally a "format we
     * cannot understand", which is the same UI surface as a future version we do not
     * implement. The parser-glue bead can choose to coalesce missing-version onto
     * `InvalidPassJson` if that fits the UI better; the failure is preserved here.
     */
    data class UnknownFormatVersion(val version: Int) : PassJsonFailure

    /**
     * `pass.json` does not declare any of the five known top-level pass styles
     * (`boardingPass`, `eventTicket`, `coupon`, `storeCard`, `generic`). [raw] is the
     * first object-valued top-level key that is not in the known-style or
     * known-non-style allowlist, or `""` when no plausible-shape candidate is
     * present.
     */
    data class UnknownPassStyle(val raw: String) : PassJsonFailure
}
