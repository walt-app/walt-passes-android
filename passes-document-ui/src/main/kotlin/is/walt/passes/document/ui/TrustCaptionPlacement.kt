package `is`.walt.passes.document.ui

/**
 * Where the non-suppressible [DocumentTrustCaption] is rendered on the document detail
 * surface (`DocumentView`, both the PDF and image arms). The kernel's answer to "let me
 * fold the trust signal into my own details section" (wpass-gv6): the caption is
 * **relocatable, not suppressible**.
 *
 * The distinction is load-bearing and is the whole reason this is a named type rather
 * than a `showCaption: Boolean`. Neither value lets the host drop, reword, or restyle the
 * trust claim away — the verbatim caption text ("User-provided document. Walt has not
 * verified the source.") and its structure live only in [DocumentTrustCaption], which
 * stays public and stays locked by `DocumentSurfaceLockTest`. What the host chooses here
 * is *location*, not *content*:
 *
 *  - [Docked] — the kernel renders the caption in its built-in position above the page /
 *    image inside `DocumentView`. This is the default; every existing caller is unchanged.
 *  - [Hosted] — the kernel does **not** render its copy. Choosing this is the host
 *    asserting that it renders the kernel-owned [DocumentTrustCaption] composable itself,
 *    in an always-present surface of its own (e.g. a "Pass type" row inside a host-rendered
 *    details section). The host may place the caption wherever it likes, but it MUST be the
 *    kernel composable — a neutral "Pass type: PDF" label is NOT a substitute, because that
 *    collapses the verbatim provenance claim ADR 0005 D5 protects.
 *
 * [Hosted] shifts the trust caption from the kernel position to a host surface exactly the
 * way `ScannableCardRowTile` shifts the scannable-card caption from list-row to
 * detail-surface: a bounded, documented concession, not a suppression hole. The kernel
 * cannot verify at runtime that the host actually mounted the caption, so the obligation is
 * recorded in ADR 0005 (D5 relocation addendum) and pinned consumer-side by a walt-android
 * test.
 */
public sealed interface TrustCaptionPlacement {
    /** Kernel renders the caption in its built-in position inside `DocumentView` (default). */
    public data object Docked : TrustCaptionPlacement

    /**
     * Kernel omits its copy; the host has taken on rendering the kernel-owned
     * [DocumentTrustCaption] in its own always-present surface. Relocation, not
     * suppression — see the type KDoc and ADR 0005 D5.
     */
    public data object Hosted : TrustCaptionPlacement
}
