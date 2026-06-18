package `is`.walt.passes.ui

/**
 * Where the non-suppressible [ScannableCardTrustCaption] is rendered on a detail surface
 * (`ScannableCardScreen`). This is the kernel's answer to "let me fold the trust signal
 * into my own details section" (wpass-gv6): the caption is **relocatable, not
 * suppressible**.
 *
 * The distinction is load-bearing and is the whole reason this is a named type rather
 * than a `showCaption: Boolean`. Neither value lets the host drop, reword, or restyle the
 * trust claim away — the verbatim caption text and its structure live only in
 * [ScannableCardTrustCaption], which stays public and stays locked by
 * `ComposableSurfaceLockTest`. What the host chooses here is *location*, not *content*:
 *
 *  - [Docked] — the kernel renders the caption in its built-in docked position at the
 *    bottom of `ScannableCardScreen`. This is the default; every existing caller is
 *    unchanged.
 *  - [Hosted] — the kernel does **not** render its docked copy. Choosing this is the host
 *    asserting that it renders the kernel-owned [ScannableCardTrustCaption] composable
 *    itself, in an always-present surface of its own (e.g. a "Pass type" row inside a
 *    host-rendered details section). The host may place the caption wherever it likes,
 *    but it MUST be the kernel composable — a neutral "Pass type: Scanned" label is NOT a
 *    substitute, because that collapses the verbatim provenance claim C2 protects.
 *
 * [Hosted] shifts the trust caption from the kernel-docked position to a host surface
 * exactly the way `ScannableCardRowTile` shifts it from list-row to detail-surface: it is
 * a bounded, documented concession, not a suppression hole. The kernel cannot verify at
 * runtime that the host actually mounted the caption, so the obligation is recorded in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md` (C2) and pinned consumer-side by a walt-android
 * test, mirroring the existing row-tile concession (condition 3 there).
 */
public sealed interface TrustCaptionPlacement {
    /** Kernel renders the caption docked at the bottom of the surface (default). */
    public data object Docked : TrustCaptionPlacement

    /**
     * Kernel omits its docked copy; the host has taken on rendering the kernel-owned
     * [ScannableCardTrustCaption] in its own always-present surface. Relocation, not
     * suppression — see the type KDoc and `SCANNABLE_CARD_THREAT_MODEL.md` C2.
     */
    public data object Hosted : TrustCaptionPlacement
}
