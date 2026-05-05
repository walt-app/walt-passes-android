package `is`.walt.passes.core.internal

import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.MalformedReason

/**
 * Outcome of running [parseStrings] over a single `<locale>.lproj/pass.strings` payload.
 * Internal only: the parser-glue bead lifts a [Failed] into the right
 * [`is`.walt.passes.core.ParseResult] arm.
 *
 * The arm split is deliberately narrow — [Failed] surfaces a public [MalformedReason]
 * directly because the .strings layer has no finer-grained internal failure vocabulary
 * to defend yet (no version field, no shape constraints richer than `"key" = "value";`).
 * Two reasons are surfaceable today:
 *
 *  - [MalformedReason.InvalidPassJson] for any structural decode failure (charset error,
 *    unterminated token, missing `=` / `;`, unrecognized escape). A follow-up bead
 *    (wpass-n6g) introduces a dedicated `MalformedReason.InvalidStrings` arm; until
 *    then `InvalidPassJson` is the closest existing arm and routing can move in one
 *    place.
 *  - [MalformedReason.ResourceLimitExceeded] with
 *    [`is`.walt.passes.core.ResourceLimit.JsonStringSize] on a per-value byte cap trip.
 */
internal sealed interface StringsResult {
    data class Ok(val strings: LocalizedStrings) : StringsResult

    data class Failed(val reason: MalformedReason) : StringsResult
}
