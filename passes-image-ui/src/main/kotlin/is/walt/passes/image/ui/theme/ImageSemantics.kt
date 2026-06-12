package `is`.walt.passes.image.ui.theme

import `is`.walt.passes.ui.core.ArgbColor

/**
 * The theming contract that walt-android (or any other consumer) supplies to
 * `passes-image-ui`. Sibling shape to `passes-ui::PassesSemantics` and
 * `passes-pdf-ui::DocumentSemantics` — all three ask the host for semantic color slots
 * that have NO Material3 analogue and reach them via a `staticCompositionLocalOf`. The
 * three contracts are deliberately independent; a host wires each at the screen-graph
 * root alongside the others, none nested inside another.
 *
 * The trust-claim slots are [captionBackground] / [captionForeground] /
 * [captionIconTint]: they style the non-suppressible "user-provided image" caption. The
 * caption is non-removable (see the KDoc on [ImageTrustCaption]); its style is fixed by
 * the host theme rather than per-image. None of these slots can hide the caption — a
 * host wanting a flat treatment sets [captionBackground] transparent, which restyles
 * but does not suppress.
 *
 * [captionIconTint] defaults to [captionForeground] so a consumer that does not opt into
 * a separate accent gets a consistent monochrome caption for free. The default is wired
 * in the constructor, which keeps the slot's addition non-breaking for existing call
 * sites. [captionIconTint] must stay after [captionForeground] — the default references
 * an earlier parameter and the field order is load-bearing.
 */
public data class ImageSemantics(
    public val captionBackground: ArgbColor,
    public val captionForeground: ArgbColor,
    public val captionIconTint: ArgbColor = captionForeground,
    public val tileBackground: ArgbColor,
    public val tileForeground: ArgbColor,
    public val tileLabelForeground: ArgbColor,
    public val laneBackground: ArgbColor,
    public val imageBadgeBackground: ArgbColor,
    public val imageBadgeForeground: ArgbColor,
)
