package `is`.walt.passes.pdf.ui.theme

import `is`.walt.passes.ui.core.ArgbColor

/**
 * The theming contract that walt-android (or any other consumer) supplies to
 * `passes-pdf-ui`. Sibling shape to `passes-ui::PassesSemantics` — both modules ask the
 * host for semantic color slots that have NO Material3 analogue, and reach them via a
 * `staticCompositionLocalOf`. The two contracts are deliberately independent: a host
 * that only renders passes wires `PassesSemantics`; a host that adds documents wires
 * `DocumentSemantics` alongside; one is not nested inside the other so the modules
 * stay independent peers.
 *
 * The trust-claim slots on this surface are [captionBackground] / [captionForeground]:
 * they style the non-suppressible "user-provided document" caption that mirrors the
 * "Self-signed" pill on signed passes. Because the caption is non-removable (see the
 * KDoc on `DocumentTrustCaption`), its style is fixed by the host theme rather than
 * per-document, and it is the host theme's responsibility to keep contrast legal so
 * the caption stays readable.
 *
 * Color values follow the same packed-ARGB shape as `passes-ui::SignatureBadgeColors`:
 * 32 bits `0xAARRGGBB`. The shared `ArgbColor` value class lives in `passes-ui-core`.
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
