package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings

/**
 * Outcome of running [parseStrings] over a single `<locale>.lproj/pass.strings` payload.
 * Internal only: the parser-glue bead lifts a [Failed] into the right
 * [`is`.walt.passes.core.ParseResult] arm.
 *
 * Mirrors [PassJsonDecodeResult]'s split: the public failure surface is coarse
 * ([`is`.walt.passes.core.MalformedReason.InvalidStrings] /
 * [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded]) but each internal
 * arm carries the granularity ops dashboards need to tell a charset failure apart
 * from a truncation attack and from a per-value cap trip without re-deriving the
 * cause from a stringy reason.
 */
internal sealed interface StringsResult {
    data class Ok(val strings: LocalizedStrings) : StringsResult

    data class Failed(val failure: StringsFailure) : StringsResult
}

/**
 * Why [parseStrings] rejected a `pass.strings` payload. Arms are deliberately finer-
 * grained than the public [`is`.walt.passes.core.MalformedReason] surface so the
 * parser-glue bead can route each failure independently and so telemetry can
 * distinguish a structural attack pattern (e.g. dangling block comments showing up
 * in bursts) from an encoding misconfiguration.
 *
 * All arms except [ValueTooLong] lift to [`is`.walt.passes.core.MalformedReason.InvalidStrings];
 * [ValueTooLong] lifts to [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded]
 * with [`is`.walt.passes.core.ResourceLimit.JsonStringSize].
 */
internal sealed interface StringsFailure {
    /**
     * BOM-sniff matched no charset, or the bytes did not decode under the chosen
     * charset's strict policy (REPORT on malformed input).
     */
    data object InvalidEncoding : StringsFailure

    /** A `"`-opened string ran to EOF without a matching close. */
    data object UnterminatedString : StringsFailure

    /** A block comment was opened (slash-star) but ran to EOF without a closing star-slash. */
    data object UnterminatedComment : StringsFailure

    /**
     * Expected an `=`, `;`, or opening `"` and got something else. Distinct from
     * [BadEscape] because the wrong-shape signal is at the entry layer rather than
     * inside a quoted string.
     */
    data object BadStructure : StringsFailure

    /**
     * Unrecognized escape (`\x`), short or non-hex `\Uxxxx`, or a surrogate code unit
     * that is not part of a valid pair. Unpaired surrogates are rejected outright
     * rather than silently embedded — a malformed UTF-16 string smuggled past the
     * parser would surface unpredictably at any downstream UTF-8 boundary
     * (re-encoding, JNI, logging) and undermines the strict-decode posture the
     * charset layer enforces on raw bytes.
     */
    data object BadEscape : StringsFailure

    /**
     * A single value's decoded UTF-8 byte length exceeded
     * [`is`.walt.passes.core.ParserConfig.maxJsonStringBytes].
     */
    data object ValueTooLong : StringsFailure
}
