# passes-ui composable signatures

The skeleton bead (`wpass-9vv.4`) ships the *theming contract* and *intent payload* types
in Kotlin source. The composable functions themselves wait for the implementation bead,
which lands the Android Gradle Plugin, Jetpack Compose, Material3, ZXing, and Coil.

This document is the design output of the skeleton bead: the exact signatures the
implementation bead is committed to producing. It is the public-API contract for every
composable that the host (walt-android's `feature/passes`) calls.

The signatures below assume the implementation bead's imports:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import is.walt.passes.core.Barcode
import is.walt.passes.core.ImageBytes
import is.walt.passes.core.ImageRole
import is.walt.passes.core.Pass
import is.walt.passes.core.PassInstant
import is.walt.passes.core.PassLocale
import is.walt.passes.core.SignatureStatus
import is.walt.passes.ui.B3UrlIntent
import is.walt.passes.ui.EmailIntent
import is.walt.passes.ui.ExpiredOverlayState
import is.walt.passes.ui.ImageRenderBounds
import is.walt.passes.ui.PhoneIntent
import is.walt.passes.ui.SecurityIntent
import is.walt.passes.ui.UiTelemetryGuard
import is.walt.passes.ui.theme.PassesSemantics
```

## Theming entry point

```kotlin
/**
 * Provides the host's [PassesSemantics] to every passes-ui composable underneath. Wraps
 * the host's own MaterialTheme; the host is expected to call this inside its own theme
 * scope (e.g. inside walt-android's WaltTheme).
 */
@Composable
public fun PassesTheme(
    semantics: PassesSemantics,
    content: @Composable () -> Unit,
)

/** CompositionLocal accessor for the semantics. Reads default to an error in release. */
public val LocalPassesSemantics: ProvidableCompositionLocal<PassesSemantics>
```

The host MUST wrap pass-rendering composables in `PassesTheme(...)`. Reading
`LocalPassesSemantics.current` outside a `PassesTheme` scope is a programmer error and
fails fast.

## Pass front

```kotlin
/**
 * Renders the front of a pass. Layout switches on `pass.type`:
 *
 * - BoardingPass: header / primary "from / to" prominence / secondary / auxiliary, plus
 *   the barcode at the bottom.
 * - EventTicket: header / primary / secondary, with the strip image (if any) above
 *   primary fields.
 * - Coupon, StoreCard, Generic: header / primary / secondary / auxiliary with no
 *   special prominence.
 *
 * The pass's own [Pass.colors] override the host's MaterialTheme.colorScheme for the
 * pass surface only; chrome around the pass (the bottom sheet, the wallet list
 * background) keeps using the host theme.
 *
 * The signature trust badge and expired overlay (if applicable) are composited on top
 * of the front; they are not optional and cannot be suppressed by the caller.
 */
@Composable
public fun PassFront(
    pass: Pass,
    signatureStatus: SignatureStatus,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
    nowEpochMillis: Long = System.currentTimeMillis(),
)
```

## Pass back

```kotlin
/**
 * Renders the back of a pass: the list of `Pass.backFields`, with detected URLs,
 * phone numbers, and email addresses rendered as tappable affordances.
 *
 * Tapping such an affordance fires the matching callback with the parsed
 * [SecurityIntent]; passes-ui never invokes the host's outbound Intent directly. The
 * host's expected wiring is to display the corresponding confirmation sheet and call
 * `Intent.ACTION_VIEW` (or `ACTION_DIAL`, `ACTION_SENDTO`) only on confirm.
 */
@Composable
public fun PassBack(
    pass: Pass,
    onUrlIntent: (B3UrlIntent) -> Unit,
    onPhoneIntent: (PhoneIntent) -> Unit,
    onEmailIntent: (EmailIntent) -> Unit,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
)
```

## Barcode

```kotlin
/**
 * Renders [barcode] using ZXing. The composable enforces a minimum on-screen size
 * (300 dp x 300 dp for QR / Aztec; 500 dp x 100 dp for PDF417 / Code128) so the
 * barcode is reliably scannable at gate distance.
 *
 * `altText`, when present in the [Barcode], is rendered below the barcode for
 * accessibility and as a fallback for scanners that fail.
 */
@Composable
public fun BarcodeView(
    barcode: Barcode,
    modifier: Modifier = Modifier,
)
```

## Security confirmation sheets

Each sheet displays the verbatim target string from its [SecurityIntent], the source
field's label and the issuer's `organizationName`, then a confirm button.

```kotlin
@Composable
public fun B3UrlConfirmSheet(
    intent: B3UrlIntent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    telemetry: UiTelemetryGuard,
)

@Composable
public fun PhoneConfirmSheet(
    intent: PhoneIntent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    telemetry: UiTelemetryGuard,
)

@Composable
public fun EmailConfirmSheet(
    intent: EmailIntent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    telemetry: UiTelemetryGuard,
)
```

The sheets fire `telemetry.onSecuritySheetShown` on first composition,
`telemetry.onSecuritySheetConfirmed` on confirm tap, and
`telemetry.onSecuritySheetDismissed` on any other dismissal. Confirming closes the
sheet AND calls `onConfirm`; dismissal closes WITHOUT firing `onConfirm`.

## Expired badge

```kotlin
/**
 * Renders the non-suppressible expired/voided overlay. [state] is computed via
 * [ExpiredOverlayState.from]; if the host wants to "hide the badge" the only way is
 * to deliberately not call [PassFront] for that pass. The badge composable itself
 * has no `enabled` parameter.
 */
@Composable
public fun ExpiredOverlay(
    state: ExpiredOverlayState,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
)
```

## Bounded image

```kotlin
/**
 * Decodes [bytes] using Android `ImageDecoder` with explicit dimension caps applied
 * via `setOnHeaderDecodedListener`. Decode failures (including bounds violations)
 * yield a placeholder and fire `telemetry.onImageDecodeRejected` with the
 * categorized reason; the composable never throws.
 *
 * The implementation bead must NOT use Coil's default decoder for these images:
 * the bounds enforcement is the trust claim, and a third-party loader would have
 * to be re-audited for that property on every dependency upgrade.
 */
@Composable
public fun BoundedImage(
    bytes: ImageBytes,
    role: ImageRole,
    contentDescription: String?,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
)
```

## What the implementation bead must NOT change

These signature properties are the trust-claim interface. Changing any of them is a
security-policy edit, not a refactor:

1. `onUrlIntent`, `onPhoneIntent`, `onEmailIntent` on `PassBack` are NOT defaulted to
   no-op. The host must supply them explicitly. This forces a conversation if a
   future host author "forgets" to wire confirmation sheets.
2. The expiration overlay has no `enabled` / `suppress` parameter. It either applies
   or it does not, based on `ExpiredOverlayState.from`.
3. The signature trust badge has no `enabled` / `suppress` parameter. It is always
   composited on top of `PassFront`, with the color sourced from
   `PassesSemantics.signatureBadge`.
4. `BoundedImage` has no `bounds: ImageRenderBounds? = null` form that would let a
   caller opt out of decoding limits. The default is the only way to take the
   default; opting out requires a deliberate `ImageRenderBounds(...)` with explicit
   higher caps.
5. The security confirmation sheets do not accept a "skip confirmation" parameter.
   The host's outbound `Intent` fires only on user confirm.
