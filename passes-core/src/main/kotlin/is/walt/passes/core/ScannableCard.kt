package `is`.walt.passes.core

/**
 * A user-generated, unsigned scannable artifact: a typed-in payload plus a chosen barcode
 * format that renders scannably on screen. Sibling of [Pass], NOT a subtype — the two share
 * no parse pipeline, no signature posture, and no UI lane. The trust UX distinction is
 * load-bearing: a [Pass] has been cryptographically verified before construction; a
 * [ScannableCard] never can be, because the user typed the data.
 *
 * No unifying `DisplayableArtifact` interface exists, deliberately. Re-introducing one would
 * re-create the trust-conflation risk the wpass-lzi epic explicitly avoids — if a single
 * type can hold either kind of artifact, every consumer-side `when` becomes a chance to
 * forget the trust-caption arm.
 */
public data class ScannableCard(
    public val id: ScannableCardId,
    public val payload: String,
    public val format: ScannableFormat,
    public val label: String,
    public val color: ScannableColor?,
    public val createdAt: PassInstant,
)

/**
 * Type-safe identifier for a [ScannableCard]. Mirrors the value-class pattern used elsewhere
 * in passes-core (see [PassLocale], [PassInstant]) so a raw [String] cannot be mistaken for
 * a card ID at a call site. The wrapped value is opaque on purpose — passes-core does not
 * mint IDs (storage does); this type only conveys "this string identifies a stored card."
 */
@JvmInline
public value class ScannableCardId(public val value: String)

/**
 * 32-bit packed ARGB color (`0xAARRGGBB`) for the card's background tint. Distinct from
 * [ColorValue] (which is 24-bit RGB for PKPASS color fields) because the two have different
 * semantics: PKPASS colors come from a verified archive and never carry alpha; scannable-card
 * colors are user-chosen and may want translucency for layered UI treatments.
 *
 * passes-core cannot depend on passes-ui-core's `ArgbColor`, which would invert the module
 * dep direction. The consumer module provides a conversion extension when rendering.
 */
@JvmInline
public value class ScannableColor(public val argb: Int)
