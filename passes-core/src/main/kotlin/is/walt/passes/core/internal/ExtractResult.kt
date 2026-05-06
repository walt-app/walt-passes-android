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
     *
     * [archiveBytes] is the count of compressed-archive bytes that the extractor pulled
     * from the underlying input. The parser-glue layer uses it for telemetry on stream
     * sources whose [`is`.walt.passes.core.PassSource.Stream.sizeHintBytes] was not
     * supplied — without this plumbing, telemetry would record `0` for every
     * hint-less stream parse, which is the failure mode the review called out as
     * "documented but misleading."
     *
     * The count tracks bytes consumed *by the extractor's pipeline*, not the archive's
     * total file length. For a zip with a central directory + EOCD record, the
     * underlying [java.util.zip.ZipInputStream] stops after detecting the central-
     * directory signature; bytes past that point are not pulled and not counted. This
     * is honest for "what we ate" telemetry; the parser-glue layer prefers a
     * source-derived value (`bytes.size` / `sizeHintBytes`) when one is available so
     * the existing telemetry assertions (which use exact archive size) keep their
     * meaning.
     */
    data class Success(val entries: Map<String, ByteArray>, val archiveBytes: Long) : ExtractResult

    data class Failure(val reason: MalformedReason) : ExtractResult
}
