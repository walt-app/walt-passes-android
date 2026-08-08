# ADR 0003: passes-ui theming and security composables

- Status: Accepted
- Date: 2026-05-04
- Tracks: parent epic `wpass-9vv`; design bead `wpass-9vv.4`
- Decision context: `decision-wlt-0tn-q3-2` (in-app B3 confirmation), `decision-wlt-0tn-q5` (three-module shape)
- Reference: walt-android `core/ui/src/main/kotlin/is/walt/core/ui/theme/` (authoritative for color and typography)

## Context

`passes-ui` is the third Gradle module of `walt-passes-android`: the Android +
Compose surface that walt-android's `feature/passes` calls to render passes and
mediate the security-confirmation dialogs that pass-embedded URLs, phone numbers,
and email addresses require.

The bead's deliverable is the *design* of the module: the theming contract walt-
android supplies, the composable signatures the host calls, and the trust-claim-
bearing flows (with how each is tested). The implementation bead that follows
lands the Android Gradle Plugin, Jetpack Compose, Material3, ZXing, Coil's
`ImageDecoder` integration, and the actual `@Composable` function bodies.

This ADR fixes the contract today; the implementation bead does not renegotiate it.

## Decisions

### D1. The host's MaterialTheme is the source of truth for color and typography

`passes-ui` does not declare its own brand color palette, its own typography scale,
or its own font family. Those live in walt-android's
`core/ui/src/main/kotlin/is/walt/core/ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`,
`Shape.kt`, plus `tokens/`), and `passes-ui` reads them through Material3's
`MaterialTheme.colorScheme`, `MaterialTheme.typography`, and `MaterialTheme.shapes`
at composition time.

This is a reversal of the alternative we considered (defining a parallel theming
contract that the host populates with its own values), and it is the right call:

- **Single source of truth.** The walt-android theme is the only place a designer
  changes a brand color and has it propagate. A parallel contract introduces a
  drift surface where `passes-ui` could be styled inconsistently with the rest of
  the wallet.
- **Material3-native.** walt-android already builds on M3 (`MaterialTheme(colorScheme,
  typography, shapes)`). Reusing that contract means `passes-ui` composables read
  `MaterialTheme.colorScheme.surface` etc. like every other Compose surface in the
  app, with no translation layer.
- **Open-source review value.** A reader auditing this repository sees `passes-ui`
  composables that read `MaterialTheme.X`. They look at walt-android's `core/ui` for
  the values. Duplicating tokens here would force a reader to verify a copy is in
  sync, with no upside.

The Walt Design System Claude project remains the source of *flow and UX* — screen
sequencing, layouts, interactions, the boarding/event/train per-category visual
language — but it is not the source of color, typography, or font tokens.

See `CLAUDE.local.md`'s "Theming source of truth" section for the operational form
of this rule.

### D2. `PassesSemantics` adds the slots M3 does not name

A small set of `passes-ui`-specific slots have no Material3 analogue. They are
expressed as a `PassesSemantics` data class and threaded through composition via
a single `LocalPassesSemantics` provided at `PassesTheme(...)` scope:

- `signatureBadge`: per-`SignatureStatusKind` foreground / background pair for
  the trust badge that sits on every rendered pass.
- `expiredBadge`: pill foreground / background and scrim alpha for the
  non-suppressible expired / voided overlay.
- `securitySheet`: emphasis, body, confirm, cancel slots for the URL / phone /
  email confirmation sheets — explicitly distinct from chrome dialogs so the
  user does not muscle-memory through.
- `categoryAccent`: per-`PassType` accent strip color for the wallet-list view.

Color values are packed ARGB integers (`ArgbColor(0xAARRGGBB)`), matching
`passes-core`'s existing `ColorValue.rgb` shape but with an alpha channel.
Compose-side conversion is a one-line `Color(argb.argb.toLong())` at the API
boundary.

Why ARGB integers, not `androidx.compose.ui.graphics.Color`: the skeleton module
is JVM-only today (mirroring `passes-storage`'s skeleton) and does not have
Compose on its classpath. The implementation bead introduces Compose along with
the `@Composable` function bodies and converts at the boundary. Keeping the
contract type primitive lets the JVM `PublicApiSurfaceTest` exercise the entire
shape without an Android device.

### D3. The host MUST scope passes-ui composables in `PassesTheme(semantics) { ... }`

There is no fallback default for `LocalPassesSemantics`. Reading it outside a
`PassesTheme` scope is a programmer error and the implementation bead fails fast
(an `IllegalStateException` at first composition is the chosen mechanism, not a
silent fallback).

Why fail-fast: a silent default produces a "looks fine in dev, wrong in prod"
hazard where the trust badge renders in some default neutral that the host did
not approve. Failing at the first composition surfaces the missing scope while
the developer is still in front of the screen.

The implementation bead's screenshot tests cover the failure mode explicitly: a
test that calls `PassFront` outside `PassesTheme` asserts the exception, so a
future refactor cannot regress the rule into "oh, just use a default."

### D4. Pass colors override the theme on the pass surface only

The PKPASS spec lets each pass declare its own `foregroundColor`,
`backgroundColor`, and `labelColor` (already surfaced as `PassColors` in
`passes-core`). On the pass surface itself, those colors override the host's
`MaterialTheme.colorScheme.surface` / `onSurface`. Off the pass — wallet list
chrome, bottom sheets, expired overlay, security confirmation sheets — the host
theme is authoritative.

Why scoped: a pass-supplied color does not get to recolor a security
confirmation sheet. The sheets are an in-app surface owned by walt-android's
trust UI; allowing the pass to influence their appearance opens an
"adversarial pass darkens cancel button into invisibility" attack.

The override is implemented by reading `Pass.colors` inside `PassFront` and
applying it to the pass-front composable's local surface, not by mutating
`MaterialTheme.colorScheme`. Composables outside `PassFront` (the security
sheet family) read `MaterialTheme.colorScheme` and `LocalPassesSemantics`
directly and ignore `Pass.colors` entirely.

### D5. Trust-claim composables have no off switch

Five composable surfaces are trust-claim-bearing: the signature trust badge,
the expired/voided overlay, the URL confirmation sheet, the phone confirmation
sheet, and the email confirmation sheet. Their public composable signatures
deliberately omit the parameters that would let a caller suppress them:

- `PassFront` does not accept `expiredOverlay: ExpiredOverlayState`. The state
  is computed inside the composable from `Pass.expirationDate`, `Pass.voided`,
  and a `nowEpochMillis` parameter. There is no API path that lets a host
  display an expired pass as not-expired.
- `B3UrlConfirmSheet`, `PhoneConfirmSheet`, `EmailConfirmSheet` do not accept a
  `skipConfirmation: Boolean` shortcut. The host's outbound `Intent` fires
  inside `onConfirm`, which the user's tap is the only thing that calls.
- `BoundedImage` does not accept `bounds: ImageRenderBounds? = null`. The
  default is the only way to take the default; opting out requires explicitly
  constructing an `ImageRenderBounds(...)` with higher caps, which is a visible
  signal at the call site.
- `PassBack` does not default the `onUrlIntent` / `onPhoneIntent` /
  `onEmailIntent` callbacks. The host must wire all three; missing any one is
  a compile error, not a runtime no-op.

#### Bounded opt-ins for cross-chrome disclosure (wpass-hy2)

Two cases admit a single opt-in boolean each: when the host has already
disclosed the same signal in its own chrome and the kernel-rendered chrome
would double-label the user. These are explicit, non-default, and pinned in
`ComposableSurfaceLockTest` so they cannot grow further without review:

- `PassFront.showSignatureBadge: Boolean = true` (wpass-btz). Default-true
  preserves the original posture: a hosted `PassFront` carries the band's pill.
  `false` suppresses the pill but not the `onPassRendered(type, band)`
  telemetry — the band is still derived and recorded. Intended for surfaces
  like walt-android's import-confirm view where a sibling `TrustChip` already
  shows the band. A host that opts out without rendering an equivalent
  disclosure is breaching the contract; review at the call site.
- `PassFront.showExpiredOverlay: Boolean = true` (wpass-d0k). Default-true
  preserves the original posture: an expired or voided pass on `PassFront`
  carries the black scrim and pill. `false` suppresses the scrim composition
  only; the underlying `Pass.expirationDate` / `voided` data is unchanged, the
  `ExpiredOverlay` composable itself remains non-suppressible if a host calls
  it directly, and there is still no API that *displays* an arbitrary expiry
  state. Intended for surfaces like walt-android's archival detail screen
  where a sibling `PAST PASS` eyebrow and `Expired {date}` pill already
  present the same status.

The companion change — `B3UrlConfirmSheet` / `PhoneConfirmSheet` /
`EmailConfirmSheet` taking `emphasisStyle: B3EmphasisStyle = Container`
(wpass-48v) — is a pure layout switch, not a suppression. Both `Container`
and `DomainHero` render the verbatim target string on-screen, fire the same
telemetry on the same gestures, and block dispatch on an explicit confirm tap.
The split exists so the verbatim target does not visually equate with the
issuer Verified band on hosts that surface both.

#### What is still forbidden

Removing or weakening any non-opt-out property above is a deliberate
security-policy edit, not a refactor. The bounded opt-ins do NOT open the door
to:

- `PassFront(.., signatureBand: SignatureBand = ...)` or any path that lets a
  host *display* a band different from what `signatureStatus.toBand()` produces.
- `PassFront(.., expiredOverlay: ExpiredOverlayState = ...)` or any path that
  lets a host *display* an expiry state different from
  `ExpiredOverlayState.from(pass, nowEpochMillis)`.
- A `skipConfirmation: Boolean` on the security sheets.
- An overload of `ExpiredOverlay` or `BoundedImage` that elides the existing
  shape.

`ComposableSurfaceLockTest` pins the user-visible parameter counts for each
surface so any of the above would fail the lock before code review even
opens the file.

### D6. Telemetry follows the `TelemetryGuard` discipline

`UiTelemetryGuard` mirrors the structural-PII-prevention pattern from
`passes-core`'s `TelemetryGuard` and `passes-storage`'s `StorageTelemetryGuard`:
every event method takes enums (`PassType`, `SignatureBand`, `SecurityIntentKind`,
`ImageDecodeRejection`) and primitives only. There is no overload that accepts a
`String`, `Pass`, or `PassField`.

The discipline is enforced by `PublicApiSurfaceTest`: an attempt to add a
free-form `String` parameter to any of the six event methods fails the lock
test on the next run.

The events that walt-android cares about, and the trust-relevant signal each
carries:

- `onPassRendered(type, signatureBand)` — baseline.
- `onPassBackOpened(type)` — pass-back-tap rate, useful for the wallet list
  layout.
- `onSecuritySheetShown(intentKind, type)` — denominator for the
  confirm/dismiss rates.
- `onSecuritySheetConfirmed(intentKind, type)` — numerator (positive).
- `onSecuritySheetDismissed(intentKind, type)` — numerator (negative). A
  spike in this rate is the signal walt-android wants to see; a phishing
  pattern would manifest as users opening the sheet and bailing.
- `onImageDecodeRejected(reason)` — bound-violation rate. A spike correlates
  with either a malformed pass archive in the wild or a bounds threshold that
  needs tuning.

`NoopUiTelemetryGuard` is provided for hosts that have not yet wired a guard.
Production walt-android wires a real one.

### D7. Skeleton bead is JVM-only; implementation bead brings AGP and Compose

This module's `build.gradle.kts` declares only `kotlin.jvm` today, mirroring the
shape `passes-storage`'s skeleton bead used. The composable signatures live in
`COMPOSABLE_SIGNATURES.md` as Kotlin pseudo-code rather than as `@Composable
fun ...` declarations.

Rationale, restated from the storage precedent:

- The contract types (theming data classes, security intents, image bounds,
  expired-state ADT, telemetry guard interface) are pure Kotlin and ship today.
- The `@Composable` function bodies wait for the implementation bead, which
  brings in:
  - `com.android.library` Gradle plugin
  - Compose BOM, Material3, foundation, runtime, ui
  - ZXing core for barcode rendering
  - Lifecycle / animation / accessibility instrumentation tests
- Splitting the work this way gives walt-android's `feature/passes` a fixed
  contract to wire its Hilt modules and intent handlers against now, in
  parallel with the implementation bead.

## Consequences

- The implementation bead can land AGP, Compose, Material3, ZXing, and the
  `@Composable` function bodies against a fixed contract; no public-API
  renegotiation.
- walt-android's `feature/passes` (tracked as `wlt-4pg` in the closed-source
  repository) can write its outbound-intent confirmation routing today, binding
  against the `B3UrlIntent` / `PhoneIntent` / `EmailIntent` types with stub
  composables.
- A reader auditing this repository can verify the trust-claim surface from
  three documents alone: `TRUST_CLAIMS.md` (what the surfaces are),
  `COMPOSABLE_SIGNATURES.md` (the API shape), and this ADR (why each shape
  is fixed).
- `passes-ui` builds on JVM-only today; the AGP wiring is a follow-on.
  `PublicApiSurfaceTest` runs on the JVM CI host, exercising the contract types
  without an Android device.

## Open follow-ups

- Implementation bead: AGP wiring, Compose BOM, Material3, ZXing dependency,
  `BoundedImage` decoder pipeline (must be `ImageDecoder.setOnHeaderDecodedListener`,
  not Coil's default loader), screenshot tests covering each `PassType` rendering
  path, instrumentation tests covering security-sheet interaction, the API-
  surface lock test that pins the trust-claim parameter omissions documented in
  D5.
- Localization: `PassFront` and `PassBack` accept a `PassLocale` parameter. The
  implementation bead picks the matching `LocalizedStrings` from `Pass.locales`
  and falls back to default locale for missing keys. A future bead may add
  walt-android-side locale-change wiring; the contract here is a parameter and
  is stable.
- Accessibility: every composable in this contract takes a
  `contentDescription: String?` (where applicable) and the implementation bead
  must pass non-null content descriptions for the trust badge, expired pill,
  and barcode altText. Accessibility-test coverage is a follow-on.

## Addendum 2026-07-18: Issuer colors extend to the wallet-list pass card face

Tracks: kernel `wpass-tjc.4`; consumer epic `wlt-mx2d` (wallet redesign).

D4 scoped issuer colors to "the pass surface itself" and made the host theme
authoritative for wallet-list chrome; in practice the parsed `PassColors` went
unused at list scale. The redesigned wallet list renders each pkpass as an
Apple-Wallet-style card whose face is a miniature pass surface, and D4's
override now extends to it: `pass.json` `backgroundColor` drives the card
face, `foregroundColor` / `labelColor` drive its text under the consumer's
contrast guard, with the category accent as fallback. A white issuer card is
distinguished from the wallet background by a hairline, not by tinting.

The D4 boundaries are otherwise unchanged, and two are load-bearing:

- **Only pkpass cards take issuer color.** Document, scannable, and composite
  list cards stay neutral with the class named in the eyebrow — see the
  "list-face code render" and "redesign context" rows in
  `SCANNABLE_CARD_THREAT_MODEL.md`. Issuer color functions as pass identity,
  never as the artifact-class signal.
- **Color is presentation, not trust.** No signature or verified affordance
  renders on any list card; the trust ladder stays on detail surfaces. The
  security-sheet family and other off-pass surfaces remain issuer-color-free
  exactly as D4 records.

> **Superseded (wpass-80y, 2026-08-08).** The extension of D4 to the wallet-list
> pass card face is withdrawn - the consumer no longer renders issuer color at
> list scale. The two load-bearing boundaries above survive in stronger form:
> color now functions as neither issuer identity nor class signal anywhere, and
> off-pass surfaces stay issuer-color-free. See the addendum below.

## Addendum 2026-08-08: Card color is class-based and user-assigned, not issuer-derived

Tracks: kernel `wpass-80y.3` (this addendum), `wpass-80y.1` (the
`ScannableCardScreen` tint), `wpass-80y.2` (the `DocumentView` tint, in
`passes-document-ui`). Consumer epic: walt-android `wlt-38v8`.

The consumer's 26.08.08 revision decouples card color from the issuer: "Colour
means class. Nothing else. The pass file's backgroundColor is read, never
rendered." Every pkpass renders the same class tint regardless of `pass.json`,
every other artifact class gets its own class tint, and the user can reassign
any item's color from a picker on the detail surface. This reverses the
2026-07-18 addendum above, which had extended D4's issuer-color override onto
the wallet-list pass card face.

What changes and what does not:

- **D4 itself is unchanged.** `Pass.colors` still overrides the theme on the
  kernel pass surface (`PassFront`) and nowhere else. What is withdrawn is the
  2026-07-18 *extension* of that override to the consumer's list card face,
  along with its contrast guard and white-issuer hairline special case. A
  consumer that renders its own card faces is free to ignore `PassColors`; a
  consumer that composes `PassFront` still gets D4's behavior.
- **Colors reach kernel surfaces as consumer-supplied tints.**
  `ScannableCardScreen(faceTint)` (`wpass-80y.1`) and `DocumentView(faceTint)`
  (`wpass-80y.2`) take an already-constructed `Color`. The kernel stores none,
  parses none, and infers nothing from one. Ink on a tinted face is derived from
  the tint's luminance (`inkOn`), pinned at ≥ 4.5:1, so an arbitrary consumer
  color cannot render the in-words trust text illegible - the theming analogue
  of D5's "trust-claim composables have no off switch."
- **The security-sheet family is untouched.** `B3UrlConfirmSheet`,
  `PhoneConfirmSheet` and `EmailConfirmSheet` read `MaterialTheme.colorScheme`
  and `LocalPassesSemantics` only. They take no tint parameter and must not: the
  "adversarial pass darkens cancel button into invisibility" attack D4 names
  applies verbatim to a user- or consumer-chosen color.
- **Color carries no trust meaning.** Recorded in full in
  `SCANNABLE_CARD_THREAT_MODEL.md` ("colour carries no trust meaning") and
  ADR 0005 D1.C; this addendum is the theming-side pointer to them.

### Tests pinning this addendum

| Claim | Test                                                                                       |
|-------|----------------------------------------------------------------------------------------------|
| Ink on a tinted face clears WCAG AA | `ScannableCardTrustSurfaceTest.inkOnClearsWcagAaAgainstEveryTintIncludingTheWorstCase` (both palette rows, the luminance extremes, and the mid-tones a 0.5 flip gets wrong) |
| A tint cannot suppress a trust-claim surface | `ScannableCardTrustSurfaceTest.faceTintDoesNotSuppressBarcodeLabelPayloadOrTrustCaption` + its dark-tint twin; `codePanelIsLiterallyWhiteNotAThemeTokenOrTheFaceTint` |
| The security-sheet family takes no tint | `ComposableSurfaceLockTest.b3UrlConfirmSheetHasExactlySixUserVisibleParameters` + the `phoneConfirmSheet…` / `emailConfirmSheet…` twins (six params, none of them a color) |
