package `is`.walt.passes.document.ui

/**
 * Where the trust/provenance signal is carried on the document detail surface
 * (`DocumentView`, both the PDF and image arms). The kernel's answer to "let me fold the
 * provenance signal into my own details section" (wpass-gv6 / Walt wlt-3cer).
 *
 *  - [Docked] — the kernel renders the verbatim non-suppressible [DocumentTrustCaption]
 *    ("User-provided document. Walt has not verified the source.") in its built-in
 *    position above the page / image. This is the default; every existing caller is
 *    unchanged.
 *  - [HostedTypeRow] — the kernel renders **no** trust caption on the detail surface. The
 *    host carries the provenance claim itself, as a single "Pass type" row inside its own
 *    details section (values "PDF" / "Image" / "Image, Scanned" for documents). Under this
 *    mode a neutral type label is an accepted carrier of the claim, and the row MAY sit
 *    inside a collapsed-by-default foldout — it is **not** required to be always-visible.
 *
 * [HostedTypeRow] is a deliberate D5 policy concession, not the earlier "relocate the
 * verbatim caption" contract: it lets the host drop the kernel caption entirely and
 * represent provenance with its own type label. The reasoning, the residual risk, and the
 * bound are recorded in ADR 0005 (D5 "Pass type" row addendum). The kernel cannot enforce
 * that the host actually renders the row, so the obligation is pinned consumer-side by a
 * walt-android test that the details section renders a "Pass type" row enumerating the
 * artifact class — NOT (as before) that the host mounts the kernel caption.
 *
 * Relocation applies to the inline `DocumentView` only. `FullScreenDocumentView` is
 * unchanged: its caption stays docked and non-suppressible (ADR 0005 Z.8). A host's
 * collapsible details section is an inline-surface affordance; the full-screen zoom
 * surface has no host details chrome to fold into.
 */
public sealed interface TrustCaptionPlacement {
    /** Kernel renders the verbatim caption in its built-in position (default). */
    public data object Docked : TrustCaptionPlacement

    /**
     * Kernel renders no caption; the host carries provenance via its own "Pass type" row
     * (a neutral type label is an accepted carrier, and the row may be collapsed by
     * default). D5 concession — see the type KDoc and ADR 0005.
     */
    public data object HostedTypeRow : TrustCaptionPlacement
}
