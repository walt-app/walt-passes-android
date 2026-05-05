package `is`.walt.passes.core.internal

import `is`.walt.passes.core.MalformedReason

/**
 * Outcome of running [extractSafely] over a [`is`.walt.passes.core.PassSource]. Internal
 * only: the parser-glue bead lifts a [Failure] into
 * [`is`.walt.passes.core.ParseResult.Malformed]. Surfacing this type to consumers would
 * expand the public API beyond what ADR 0001 fixes.
 */
internal sealed interface ExtractResult {
    /**
     * Insertion-ordered map of entry name to fully-decoded entry bytes. Order mirrors the
     * order the entries appeared in the archive's local-file-header stream so the
     * downstream pass.json / manifest hash steps can iterate deterministically.
     */
    data class Success(val entries: Map<String, ByteArray>) : ExtractResult

    data class Failure(val reason: MalformedReason) : ExtractResult
}
