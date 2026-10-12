package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings

/**
 * Outcome of running [parseStrings] over a single `<locale>.lproj/pass.strings` payload.
 * Internal only: [DefaultPassParser] decides what a [Failed] means for the pass.
 *
 * Every arm except [StringsFailure.ValueTooLong] drops that one locale and the pass
 * still parses (#252); the integrity checks over the file's bytes ran before this
 * parse and are unaffected. [StringsFailure.ValueTooLong] is a resource-limit trip
 * and fails the pass as [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded].
 */
internal sealed interface StringsResult {
    data class Ok(val strings: LocalizedStrings) : StringsResult

    data class Failed(val failure: StringsFailure) : StringsResult
}

/**
 * Why [parseStrings] rejected a `pass.strings` payload. Arms are finer-grained than
 * the caller needs so each can be routed independently: the structural arms drop the
 * locale, while [ValueTooLong] lifts to
 * [`is`.walt.passes.core.MalformedReason.ResourceLimitExceeded] with
 * [`is`.walt.passes.core.ResourceLimit.JsonStringSize].
 */
internal sealed interface StringsFailure {
    /** The bytes did not decode under the sniffed charset's strict policy (REPORT on malformed input). */
    data object InvalidEncoding : StringsFailure

    /** A quoted string ran to EOF without a matching close. */
    data object UnterminatedString : StringsFailure

    /** A block comment was opened (slash-star) but ran to EOF without a closing star-slash. */
    data object UnterminatedComment : StringsFailure

    /**
     * Expected a token, `=`, `;`, or `}` and got something else. Distinct from
     * [BadEscape] because the wrong-shape signal is at the entry layer rather than
     * inside a quoted string.
     */
    data object BadStructure : StringsFailure

    /**
     * Unrecognized escape (`\x`), octal above the ASCII range, short or non-hex
     * `\Uxxxx`, or a surrogate code unit that is not part of a valid pair. Unpaired
     * surrogates are rejected outright rather than silently embedded — a malformed
     * UTF-16 string smuggled past the parser would surface unpredictably at any
     * downstream UTF-8 boundary (re-encoding, JNI, logging) and undermines the
     * strict-decode posture the charset layer enforces on raw bytes.
     */
    data object BadEscape : StringsFailure

    /**
     * A single value's decoded UTF-8 byte length exceeded
     * [`is`.walt.passes.core.ParserConfig.maxJsonStringBytes].
     */
    data object ValueTooLong : StringsFailure
}
