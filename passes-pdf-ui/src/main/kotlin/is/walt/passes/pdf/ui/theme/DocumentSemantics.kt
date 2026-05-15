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
 * The trust-claim slots on this surface are [captionBackground] / [captionForeground]
 * / [captionIconTint]: they style the non-suppressible "user-provided document"
 * caption that mirrors the "Self-signed" pill on signed passes. Because the caption is
 * non-removable (see the KDoc on `DocumentTrustCaption`), its style is fixed by the
 * host theme rather than per-document, and it is the host theme's responsibility to
 * keep contrast legal so the caption stays readable. None of these slots can hide the
 * caption — a host wanting a flat, borderless treatment sets [captionBackground]
 * transparent, which restyles the caption but does not suppress it.
 *
 * [captionIconTint] defaults to [captionForeground] so a consumer that does not opt
 * into a separate accent gets a consistent monochrome caption; a consumer that wants
 * the info glyph in a brand accent colour sets it explicitly. The default is wired in
 * the constructor (Kotlin lets a later parameter's default reference an earlier one),
 * which keeps the slot's addition non-breaking for existing call sites. Because that
 * default references an earlier parameter, the field order is load-bearing here —
 * [captionIconTint] must stay after [captionForeground]. A future slot that needs no
 * such cross-reference should prefer the end of the list (the conventional
 * defaults-last shape), so it does not shift `componentN()` indices for the slots
 * below it.
 *
 * Color values follow the same packed-ARGB shape as `passes-ui::SignatureBadgeColors`:
 * 32 bits `0xAARRGGBB`. The shared `ArgbColor` value class lives in `passes-ui-core`.
 */
public data class DocumentSemantics(
    public val captionBackground: ArgbColor,
    public val captionForeground: ArgbColor,
    public val captionIconTint: ArgbColor = captionForeground,
    public val tileBackground: ArgbColor,
    public val tileForeground: ArgbColor,
    public val tileLabelForeground: ArgbColor,
    public val laneBackground: ArgbColor,
    public val documentBadgeBackground: ArgbColor,
    public val documentBadgeForeground: ArgbColor,
    // wpass-jil: the "Tap for full screen" banner inside DocumentView. Label text is
    // sourced from the consumer so walt-android controls wording; the colour slots
    // default to the existing badge tokens to keep the addition non-breaking.
    //
    // The String defaults below are soft — English placeholders so the surface still
    // composes in tests / dev builds. Production hosts (walt-android et al.) are
    // expected to override with localised copy through their `DocumentSemantics`
    // construction. Defaults are NOT a contract.
    public val fullScreenBannerLabel: String = "Tap for full screen",
    public val fullScreenBannerBackground: ArgbColor = documentBadgeBackground,
    public val fullScreenBannerForeground: ArgbColor = documentBadgeForeground,
    // wpass-6ag review M1: close affordance on the full-screen surface is themed
    // alongside the banner so every consumer-facing string flows through this contract.
    public val closeFullScreenLabel: String = "Close",
)
