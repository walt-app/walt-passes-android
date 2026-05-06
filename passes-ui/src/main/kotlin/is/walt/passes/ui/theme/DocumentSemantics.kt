package `is`.walt.passes.ui.theme

/**
 * Theming slots for the PDF-document surfaces in `passes-ui` (DocumentView, DocumentTile,
 * DocumentsLane, DocumentTrustCaption). Sibling shape to [PassesSemantics] and reached
 * via [PassesSemantics.documents] so a host wires both at the same root.
 *
 * The trust-claim slots on this surface are [captionBackground] / [captionForeground]:
 * they style the non-suppressible "user-provided document" caption that mirrors the
 * "Self-signed" pill on signed passes. Because the caption is non-removable (see the
 * KDoc on `DocumentTrustCaption`), its style is fixed by the host theme rather than
 * per-document, and it is the host theme's responsibility to keep contrast legal so
 * the caption stays readable.
 *
 * Color values follow the same packed-ARGB shape as [SignatureBadgeColors]: 32 bits
 * `0xAARRGGBB`, converted to Compose via `argb.toLong() and 0xFFFFFFFFL`.
 */
public data class DocumentSemantics(
    public val captionBackground: ArgbColor,
    public val captionForeground: ArgbColor,
    public val tileBackground: ArgbColor,
    public val tileForeground: ArgbColor,
    public val tileLabelForeground: ArgbColor,
    public val laneBackground: ArgbColor,
    public val documentBadgeBackground: ArgbColor,
    public val documentBadgeForeground: ArgbColor,
)
