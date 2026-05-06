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
 * Color values are packed ARGB integers (`0xAARRGGBB`), matching the shape `passes-core`
 * already uses for `ColorValue.rgb`. Consumers built on Compose convert via
 * `Color(argb.toLong())` at the API boundary; consumers on other UI toolkits unpack
 * directly. See `passes-ui/COMPOSABLE_SIGNATURES.md` for how this maps to `@Composable`
 * functions.
 */
public data class PassesSemantics(
    public val signatureBadge: SignatureBadgeColors,
    public val expiredBadge: ExpiredBadgeStyle,
    public val securitySheet: SecuritySheetStyle,
    public val categoryAccent: CategoryAccentColors,
    public val documents: DocumentSemantics,
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
 */
public data class SecuritySheetStyle(
    public val sheetBackground: ArgbColor,
    public val emphasisBackground: ArgbColor,
    public val emphasisForeground: ArgbColor,
    public val bodyForeground: ArgbColor,
    public val confirmContainer: ArgbColor,
    public val confirmForeground: ArgbColor,
    public val cancelForeground: ArgbColor,
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
 * A 32-bit ARGB color, packed `0xAARRGGBB`. Mirrors `passes-core`'s `ColorValue.rgb`
 * shape but with an alpha channel, since theme tokens may legitimately want to
 * express transparency that pass.json's RGB triplet cannot.
 *
 * Compose-side conversion: `androidx.compose.ui.graphics.Color(argb.argb.toLong())`.
 * The `Long` cast is required because `Color(Int)` interprets its argument as RGB,
 * not ARGB; using the `Long` overload preserves alpha.
 */
@JvmInline
public value class ArgbColor(public val argb: Int)
