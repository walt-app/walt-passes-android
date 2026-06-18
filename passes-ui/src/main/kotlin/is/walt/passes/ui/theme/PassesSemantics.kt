package `is`.walt.passes.ui.theme

/**
 * The theming contract that walt-android (or any other consumer) supplies to `passes-ui`.
 *
 * `passes-ui` deliberately does NOT define its own `MaterialTheme` colors and typography.
 * The host app's `MaterialTheme` (e.g. `WaltTheme` in walt-android's `core/ui`) provides
 * everything `passes-ui` needs for general chrome — backgrounds, surfaces, body text. See
 * ADR 0003.
 *
 * `PassesSemantics` adds the slots that have NO M3 analogue and are specific to the
 * pass-rendering and security-confirmation surfaces in this module:
 *
 * - [signatureBadge]: per-`SignatureStatusKind` colors for the trust badge that sits on
 *   every rendered pass. Its visual treatment is a trust-claim surface, not a brand
 *   choice — see TRUST_CLAIMS.md.
 * - [expiredBadge]: the non-removable "Expired" overlay applied to a `Pass` whose
 *   `expirationDate` is in the past or whose `voided` flag is set.
 * - [securitySheet]: the visual emphasis applied to URL / phone / email confirmation
 *   sheets. These sheets show *what the user is about to do* before they commit; their
 *   styling intentionally departs from neutral chrome so the user does not click through.
 * - [categoryAccent]: per-`PassType` accent strip color. The mockup design system uses
 *   distinct accents for boarding pass / event / coupon / store-card / generic; the
 *   actual hex values come from the host theme, not from this contract.
 *
 * Document-rendering tokens (caption, tile, lane chrome) are NOT on this contract — they
 * live in `passes-document-ui::DocumentSemantics`, supplied via that module's sibling
 * `LocalDocumentSemantics`. The split exists because `passes-ui` and `passes-document-ui` are
 * independent peers; nesting one's tokens inside the other's data class would force
 * either module to depend on the other.
 *
 * Color values are packed ARGB integers (`0xAARRGGBB`), shared with `passes-document-ui` via
 * the [ArgbColor] value class re-exported from `passes-ui-core`. Consumers built on
 * Compose convert via [toComposeColor] at the API boundary; consumers on other UI
 * toolkits unpack directly. See `passes-ui/COMPOSABLE_SIGNATURES.md` for how this maps to
 * `@Composable` functions.
 */
public data class PassesSemantics(
    public val signatureBadge: SignatureBadgeColors,
    public val expiredBadge: ExpiredBadgeStyle,
    public val securitySheet: SecuritySheetStyle,
    public val categoryAccent: CategoryAccentColors,
    public val unverifiedArtifact: UnverifiedArtifactStyle = UnverifiedArtifactStyle.Placeholder,
)

/**
 * Color slots for the trust badge that appears on every rendered pass. The four slots
 * mirror `passes-core`'s `SignatureStatusKind`; adding an arm to `SignatureStatusKind`
 * forces a corresponding addition here, making "what color does the new trust band
 * wear?" a deliberate design choice rather than an unspecified default.
 *
 * `appleVerified` is the only slot that should read as positive / trusted; the others
 * read as informational, cautionary, or warning depending on the host's palette.
 * Concrete color values are the host theme's responsibility.
 */
public data class SignatureBadgeColors(
    public val unsignedBackground: ArgbColor,
    public val unsignedForeground: ArgbColor,
    public val selfSignedBackground: ArgbColor,
    public val selfSignedForeground: ArgbColor,
    public val appleVerifiedBackground: ArgbColor,
    public val appleVerifiedForeground: ArgbColor,
    public val certChainIncompleteBackground: ArgbColor,
    public val certChainIncompleteForeground: ArgbColor,
)

/**
 * Visual treatment of the "Expired" overlay. The overlay is non-suppressible — see
 * ADR 0003 D5 — so its style is fixed by the host theme rather than per-pass.
 *
 * [scrimAlpha] is the alpha (0..255) of the scrim that dims the front of an expired
 * pass. The scrim's RGB is the host's `MaterialTheme.colorScheme.scrim`; only the
 * alpha is contract-controlled here so a host can dial the badge up or down without
 * forking the rendering code.
 */
public data class ExpiredBadgeStyle(
    public val pillBackground: ArgbColor,
    public val pillForeground: ArgbColor,
    public val scrimAlpha: Int,
)

/**
 * Styling for the URL / phone / email confirmation sheets. These sheets present the
 * *visible* target of an outbound action (the actual URL, phone number, or address)
 * before the user confirms. The styling exists so the sheets visibly differ from
 * chrome dialogs and bottom sheets used elsewhere in walt-android, reducing the chance
 * of muscle-memory dismissal.
 *
 * [emphasisBackground] / [emphasisForeground] style the panel that contains the
 * to-be-actioned target string itself; [bodyForeground] styles the explanatory copy.
 *
 * [eyebrowForeground] and [mutedForeground] are consumed by the [B3EmphasisStyle.DomainHero]
 * layout (wpass-48v) for the `LEAVING WALT` / `CALLING` / `EMAILING` eyebrow and the
 * forensic / provenance / hairline chrome respectively. Defaults approximate M3
 * `colorScheme.outline` (eyebrow) and `outlineVariant` (muted) so a host that has not
 * yet wired R2 tokens still gets a reasonable hierarchy; production callers
 * (walt-android) override both to flow the brand palette through the same way
 * [bodyForeground] already does.
 */
public data class SecuritySheetStyle(
    public val sheetBackground: ArgbColor,
    public val emphasisBackground: ArgbColor,
    public val emphasisForeground: ArgbColor,
    public val bodyForeground: ArgbColor,
    public val confirmContainer: ArgbColor,
    public val confirmForeground: ArgbColor,
    public val cancelForeground: ArgbColor,
    public val eyebrowForeground: ArgbColor = ArgbColor(0xFF73777F.toInt()),
    public val mutedForeground: ArgbColor = ArgbColor(0xFFC4C7C5.toInt()),
)

/**
 * Per-`PassType` accent strip colors. The mockup design system positions these as
 * "quiet, not loud" — they distinguish boarding from event from coupon at a glance
 * inside the wallet list without competing with the pass's own `PassColors`.
 *
 * `passes-ui` does not pick these values. The host theme does.
 */
public data class CategoryAccentColors(
    public val boardingPass: ArgbColor,
    public val eventTicket: ArgbColor,
    public val coupon: ArgbColor,
    public val storeCard: ArgbColor,
    public val generic: ArgbColor,
)

/**
 * Visual treatment for a user-generated, unsigned scannable artifact (`ScannableCard`).
 * Powers the chrome around `ScannableCardTile` and `ScannableCardScreen`, both of which
 * must read as a different artifact class from a verified PKPASS tile at a glance.
 *
 * The trust-claim is C1 + C2 from `docs/SCANNABLE_CARD_THREAT_MODEL.md`: every
 * `ScannableCardTile` renders the non-suppressible "Created by you" caption AND is
 * visually distinct from a `PassFront`-style tile through redundant treatment (dashed
 * border + leading color band + smaller corner radius). The redundancy is load-bearing:
 * theming any single dimension flat must not collapse the artifact-class distinction.
 *
 * These slots NEVER reuse `SignatureBadgeColors`. A `ScannableCard` has no signature to
 * band; borrowing the verified palette would re-create the trust-conflation risk this
 * style exists to prevent. Walt-android supplies concrete tokens — `passes-ui` ships
 * [Placeholder] only so tests and Compose previews compose without a host theme.
 *
 * [accent] colors the dashed border and the leading band. [captionBackground] /
 * [captionForeground] color the "Created by you" caption strip. [captionIconTint] is
 * currently unrendered: the caption's leading pencil glyph was removed in the wpass-v3u
 * restyle (the caption is now text-only), so this token tints nothing in `passes-ui`
 * today. It is retained for public-API stability (locked by `PublicApiSurfaceTest`) and
 * parity with `DocumentSemantics.captionIconTint`, whose caption still carries an icon.
 * Contrast (WCAG AA) is the host theme's responsibility.
 */
public data class UnverifiedArtifactStyle(
    public val accent: ArgbColor,
    public val captionBackground: ArgbColor,
    public val captionForeground: ArgbColor,
    public val captionIconTint: ArgbColor = captionForeground,
) {
    public companion object {
        /**
         * Neutral grayscale placeholder so `PassesSemantics()` default-constructs and
         * Compose previews / tests render without a host theme. Hosts MUST override
         * with brand tokens; relying on the placeholder in production would leave the
         * "Created by you" caption unstyled relative to surrounding chrome.
         */
        public val Placeholder: UnverifiedArtifactStyle = UnverifiedArtifactStyle(
            accent = ArgbColor(0xFF6B6B6B.toInt()),
            captionBackground = ArgbColor(0xFFF2F2F2.toInt()),
            captionForeground = ArgbColor(0xFF202020.toInt()),
        )
    }
}
