package `is`.walt.passes.pdf

import `is`.walt.passes.export.ArtifactKind
import `is`.walt.passes.export.ExportableArtifact
import java.time.Instant

/**
 * Opaque identifier for a stored [PdfDocument]. Wrapped in a value class so calling code
 * cannot accidentally substitute a `String` from another domain (a pass id, a filename, a
 * user input) into APIs that expect a document id.
 */
@JvmInline
public value class PdfDocumentId(public val value: String)

/**
 * The pure-Kotlin model for a successfully-imported PDF. Mirrors the role [Pass] plays in
 * `passes-core` for pkpass archives, but is deliberately a *sibling* concept (per ADR 0005
 * D1) — `PdfDocument` and `Pass` share no superclass. Documents are not signature-verified
 * (D5); their trust caption is sourced from [provenance], which has a single arm by design.
 *
 * The displayed [displayLabel] is supplied at import time by the consumer; the model layer
 * never derives it from PDF metadata, because metadata is part of the
 * no-extraction-from-content discipline (D4). Callers should pass a filename if they have
 * one and a date-based fallback ("PDF, added <date>") otherwise.
 */
public data class PdfDocument(
    public val id: PdfDocumentId,
    public val displayLabel: String,
    public val byteCount: Long,
    public val pageCount: Int,
    public val importedAtEpochMs: Long,
    public val provenance: Provenance = Provenance.UserProvided,
) : ExportableArtifact {
    override val exportKind: String get() = ArtifactKind.PDF_DOCUMENT
    override val exportId: String get() = id.value
    override val exportCreatedAt: String get() = Instant.ofEpochMilli(importedAtEpochMs).toString()
}

/**
 * Where a [PdfDocument] came from. Single arm by design: the only legitimate source today
 * is the user importing a file from their device. The arm exists not because there are
 * alternatives but because *not having* this enum would let a future contributor add a
 * silent "downloaded by Walt" provenance without a code-review trail.
 *
 * The presence of this enum also signals the policy in ADR 0005 D5: PDFs are NEVER
 * signature-verified. There is no `SignatureStatus` analogue for documents, by design.
 * Adding a second arm here is a security-policy change requiring re-review.
 */
public enum class Provenance {
    UserProvided,
}

/**
 * The reasons a PDF import can be rejected, flattened to a telemetry-safe enum (no string
 * payloads, ever — see [DocumentTelemetryGuard]). Each arm pins a specific control from
 * ADR 0005:
 *
 *  - [OversizedAtImport] / [TooManyPages] → D7 hard caps.
 *  - [NotAPdf] → header sniff before any decoding work; cuts off MIME-spoofing.
 *  - [Encrypted] → D6 (encrypted PDFs are rejected at import).
 *  - [RendererFailed] → the isolated renderer service (D3) returned an error or timed out
 *    during page-count probing; we never report the underlying decoder error string.
 *  - [UnsupportedAndroidVersion] → ADR 0005 G.1 runtime gate. The host device's
 *    `Build.VERSION.SDK_INT` is below 34, so the Mainline-backed PDFium reachable through
 *    `android.graphics.pdf.PdfRenderer` is not available. Fired by the importer entry
 *    point *before* any source bytes are read or the renderer service is bound; the
 *    isolated process never starts on a device that cannot satisfy the version floor.
 *  - [EncoderFailed] → SharedMemory mapping, `Bitmap.copyPixelsFromBuffer`, or
 *    `Bitmap.compress(PNG)` threw after the renderer service returned `Ok`. This is a
 *    *post-renderer* failure inside the importer's PNG-encoding step. Distinct from
 *    [RendererFailed] so telemetry can tell "PDFium choked on this file" apart from
 *    "the device ran out of RAM during PNG encoding."
 *  - [StorageHandoffFailed] → the consumer-supplied `persist` callback threw after a
 *    successful render. Trust band is the storage layer (downstream of this module);
 *    a spike here points the consumer at SQLCipher / DB infra rather than the renderer.
 *
 * Reviewers should treat any future addition of a string-bearing failure arm (e.g. an
 * "ErrorMessage" data class) as a security-policy change.
 *
 * Downstream wire-format note: at least one downstream module (today, the binder layer
 * in `passes-pdf`) maps each arm to a stable wire code. Adding, removing, or renaming
 * an arm here will fail downstream surface tests until those mapping tables are
 * updated. Reordering arms is safe — the downstream mappings are exhaustive `when`
 * tables over the enum values rather than `ordinal`-positional — but contributors
 * should still expect downstream tests to gate the change.
 */
public enum class DocumentRejectedKind {
    OversizedAtImport,
    NotAPdf,
    Encrypted,
    TooManyPages,
    RendererFailed,
    UnsupportedAndroidVersion,
    EncoderFailed,
    StorageHandoffFailed,
}
