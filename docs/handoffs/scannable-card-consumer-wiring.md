# ScannableCard consumer-wiring handoff spec

> Cross-repo handoff from `passes-android` (open-source kernel) to `walt-android`
> (closed-source consumer). The kernel surfaces below are merged; the entry-point
> UI that invokes them lives in walt-android and must be filed as the `wlt-*`
> issues listed at the bottom of this document.

- Parent epic (this repo): `wpass-lzi` — "Manual barcode-card generator: type a
  payload, get a scannable Walt artifact"
- Source bead for this spec: `wpass-lzi.10`
- Threat model: [`docs/SCANNABLE_CARD_THREAT_MODEL.md`](../SCANNABLE_CARD_THREAT_MODEL.md)
  (controls C1–C6 are what the consumer must NOT defeat)

## 1. Context

`ScannableCard` is a **new artifact class**, not a subtype of `Pass`. A user
types a payload (number, alphanumeric, URL), picks a barcode format, picks a
label and color, and the kernel produces a Walt artifact that renders the
barcode scannably on screen.

Naming note: the bead text uses the working name "BarcodeCard" throughout. The
shipped kernel type is `ScannableCard`. The two refer to the same artifact;
this document uses `ScannableCard` to match the code.

The trust-UX claim Walt makes is: "every `Pass` you see has been
cryptographically verified against the issuer's chain." A `ScannableCard` has
no issuer and no signature — the user typed the bytes. Letting it inherit
`Pass`'s shape would degrade the verified-PKPASS trust signal. The kernel
therefore ships it as a **sibling** of `Pass`: separate data model, separate
storage table, separate UI lane, separate trust caption. **walt-android must
preserve that distinction at the consumer surface or the threat model's C1 / C2
controls collapse.**

## 2. Kernel API reference

### 2.1 Data model (`passes-core`, pure JVM)

`ScannableCard` — the artifact itself.
`passes-core/src/main/kotlin/is/walt/passes/core/ScannableCard.kt:17`

```kotlin
@ConsistentCopyVisibility
public data class ScannableCard internal constructor(
    public val id: ScannableCardId,
    public val payload: String,
    public val format: ScannableFormat,
    public val label: String,
    public val color: ScannableColor?,
    public val createdAt: PassInstant,
)
```

The constructor is `internal`. The only way to mint one is through the
validator (or, equivalently, through `PassRepository.createScannableCard`,
which calls the validator internally).

`ScannableFormat` — the five v1 symbologies.
`passes-core/src/main/kotlin/is/walt/passes/core/ScannableFormat.kt:22`

```kotlin
public enum class ScannableFormat {
    Code128, Ean13, UpcA, Code39, Qr
}
```

### 2.2 Create input + result family

`ScannableCardCreateInput` — the consumer's submit payload.
`passes-core/src/main/kotlin/is/walt/passes/core/ScannableCardCreateInput.kt:13`

```kotlin
public data class ScannableCardCreateInput(
    public val payload: String,
    public val format: ScannableFormat,
    public val label: String,
    public val color: ScannableColor?,
)
```

`ScannableCardCreateResult` — exhaustive sealed family returned by the
validator. Walt-android branches on this with `when` and never inspects
exception messages.
`passes-core/src/main/kotlin/is/walt/passes/core/ScannableCardCreateResult.kt:9`

```kotlin
public sealed interface ScannableCardCreateResult {
    public data class Success(val card: ScannableCard)             : ScannableCardCreateResult
    public data class InvalidPayload(val reason: PayloadRejection) : ScannableCardCreateResult
    public data class InvalidLabel(val reason: LabelRejection)     : ScannableCardCreateResult
    public data class UnsupportedFormat(val format: ScannableFormat) : ScannableCardCreateResult
    public data class EncoderFailure(val reason: EncoderFailureReason) : ScannableCardCreateResult
}
```

`PayloadRejection` arms (same file, line 32): `TooLong(actual, max)`,
`WrongCharset(format, offendingChar)`, `WrongLength(actual, required, format)`,
`InvalidCheckDigit(format)`, `ContainsControlChar`, `ContainsBidiChar`, `Empty`.

`LabelRejection` arms (line 66): `TooLong(actual, max)`, `ContainsBidiChar`,
`ContainsControlChar`, `Empty`.

`EncoderFailureReason` arms (line 89): `WriterRejected(format, detail)`,
`PayloadTooDense`. The `detail` string on `WriterRejected` is the only
third-party string that crosses the kernel boundary on this surface; **the
consumer must NOT forward it to telemetry verbatim** (hash or bucket it).

### 2.3 Validator

`ScannableCardInputValidator` — the single choke point. The existence of a
`ScannableCard` value asserts the validator approved it.
`passes-core/src/main/kotlin/is/walt/passes/core/ScannableCardInputValidator.kt:17`

```kotlin
public object ScannableCardInputValidator {
    public const val MAX_LABEL_LENGTH: Int = 64

    public fun validate(
        input: ScannableCardCreateInput,
        id: ScannableCardId,
        createdAt: PassInstant,
    ): ScannableCardCreateResult
}
```

Validation order is fail-fast: label trim/empty → label bidi/control → label
length → payload trim/empty → payload bidi/control → payload length → payload
charset → structural (e.g. EAN-13 Mod-10 check digit).

**Usage note for live form validation.** The consumer needs in-form feedback
as the user types; it should call `validate(input, sentinelId, sentinelTime)`
with throwaway id / time values and ignore `Success.card`. The real id and
`createdAt` are minted by `passes-storage` at persist time. Live validation
never persists.

### 2.4 Format constraints (informational, NOT public)

`ScannableFormatConstraints` is `internal` to `passes-core` by design — the
validator's typed rejections are the public contract. For UI copy authors,
the effective rules are:

| Format | Length | Charset |
|---|---|---|
| Code128 | ≤ 80 chars | Printable ASCII 0x20–0x7E |
| Code39  | ≤ 80 chars | `A-Z 0-9 SPACE - . $ / + %` (uppercase only) |
| Ean13   | exactly 13 digits | `0-9` (+ Mod-10 check digit) |
| UpcA    | exactly 12 digits | `0-9` (+ Mod-10 check digit) |
| Qr      | ≤ ~2000 chars (byte mode) | Any (per-mode limits apply) |

`ScannableCardInputValidator.MAX_LABEL_LENGTH = 64` for the label field across
all formats.

The consumer renders these caps as form helper text. It does not re-implement
the rules.

### 2.5 QR URI-scheme classifier

`QrPayloadClassifier` — classifies a QR payload by its URI scheme so the
consumer can gate auto-acting payloads behind the confirmation sheet.
`passes-core/src/main/kotlin/is/walt/passes/core/QrPayloadClassifier.kt:27`

```kotlin
public object QrPayloadClassifier {
    public fun classify(payload: String): QrPayloadKind
}
```

`QrPayloadKind` arms
(`passes-core/src/main/kotlin/is/walt/passes/core/QrPayloadKind.kt:31`):
`PlainText`, `Url(scheme, host, raw)`, `Phone(number)`, `Sms(number)`,
`Mailto(address)`, `Geo(coords)`, `Wifi(ssid)`, `Bitcoin(address)`,
`Ethereum(address)`, `Magnet`, `Market(productId)`, `Intent(raw)`,
`UnknownScheme(scheme, raw)`.

The classifier runs ONLY for the QR symbology. The four linear formats
(Code128, Code39, Ean13, UpcA) do not auto-act when scanned and skip
classification entirely.

### 2.6 Encoder (consumer rarely calls directly)

`BarcodeEncoder.encode()` is called by the UI render layer
(`ScannableCardScreen`, `ScannableCardTile`) when rendering the matrix. The
consumer does not normally call it — the UI surfaces accept a `ScannableCard`
and render the barcode themselves.
`passes-core/src/main/kotlin/is/walt/passes/core/BarcodeEncoder.kt:30`

```kotlin
public object BarcodeEncoder {
    public fun encode(payload: String, format: ScannableFormat): EncodeResult
}
```

### 2.7 Storage (`passes-storage`, Android)

`PassRepository` is the single contract walt-android's `core/data-passes` Hilt
module binds against. ScannableCard methods:
`passes-storage/src/main/kotlin/is/walt/passes/storage/PassRepository.kt:127`

```kotlin
public suspend fun createScannableCard(
    input: ScannableCardCreateInput,
): StorageResult<ScannableCardRecordId>

public suspend fun loadScannableCard(
    id: ScannableCardRecordId,
): StorageResult<ScannableCard>

public suspend fun deleteScannableCard(
    id: ScannableCardRecordId,
): StorageResult<Unit>

public fun observeScannableCards(): Flow<List<ScannableCard>>
```

`createScannableCard` owns id + `createdAt` minting and calls the kernel
validator internally. A validation rejection bubbles up as
`StorageError.ScannableCardRejected(reason: ScannableCardRejectionReason)`
preserving the typed kernel rejection — never as a generic infra failure, and
the row never reaches disk.
`passes-storage/src/main/kotlin/is/walt/passes/storage/StorageResult.kt:73`,
`passes-storage/src/main/kotlin/is/walt/passes/storage/StorageResult.kt:103`.

Default Android implementation: `SqlCipherPassRepository`
(`passes-storage/src/main/kotlin/is/walt/passes/storage/SqlCipherPassRepository.kt`).
This is what the consumer's DI graph binds; no separate ScannableCard store is
exposed.

Irreversible delete: `deleteScannableCard` mirrors `delete` for passes — single
transaction, no undo, no soft-delete, no VACUUM. The consumer renders the
confirmation UI; the repository trusts the call.

### 2.8 UI (`passes-ui`, Compose)

`ScannableCardTile` — home-lane tile, embeds the trust caption inside itself.
`passes-ui/src/main/kotlin/is/walt/passes/ui/ScannableCardTile.kt:76`

```kotlin
@Composable
public fun ScannableCardTile(
    card: ScannableCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Renders four redundant distinguishers vs a verified PKPASS tile: dashed
outline, leading accent band, smaller corner radius (8 dp), non-suppressible
"Created by you" caption. The barcode preview is a visual identifier, NOT a
scan surface — `onClick` is the consumer's navigation hook to
`ScannableCardScreen`.

`ScannableCardScreen` — full-screen scan surface.
`passes-ui/src/main/kotlin/is/walt/passes/ui/ScannableCardScreen.kt:38`

```kotlin
@Composable
public fun ScannableCardScreen(
    card: ScannableCard,
    modifier: Modifier = Modifier,
)
```

`ScannableCardTrustCaption` — standalone "Created by you" caption.
`passes-ui/src/main/kotlin/is/walt/passes/ui/ScannableCardTrustCaption.kt:47`
Composed inside the tile and screen by default; exposed in case the consumer
needs it elsewhere. **Do not theme it away.**

```kotlin
@Composable
public fun ScannableCardTrustCaption(modifier: Modifier = Modifier)
```

`BarcodeCreateConfirmSheet` — create-time URI-scheme confirmation gate (QR
only).
`passes-ui/src/main/kotlin/is/walt/passes/ui/BarcodeCreateConfirmSheet.kt:71`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BarcodeCreateConfirmSheet(
    payloadKind: QrPayloadKind,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
)

public fun QrPayloadKind.requiresCreateConfirmation(): Boolean
```

**Inverted button prominence vs `B3UrlConfirmSheet`:** Cancel is the filled,
focused action; Confirm is the lower-emphasis text button. A stray dismissal
or muscle-memory misclick lands on Cancel, not on a silent persist. The
consumer must NOT re-style the buttons to swap that emphasis — the divergence
is pinned by `ComposableSurfaceLockTest`.

`requiresCreateConfirmation()` returns `false` only for `QrPayloadKind.PlainText`.
The composable itself short-circuits to no output for `PlainText`, so the
consumer's create flow can be a single unconditional
`BarcodeCreateConfirmSheet` invocation when the format is QR. Linear formats
skip the gate entirely at the caller.

### 2.9 Theming

`PassesTheme` / `PassesSemantics` are the contract; walt-android's `core/ui`
module provides the real tokens (see `CLAUDE.local.md`).
`passes-ui/src/main/kotlin/is/walt/passes/ui/theme/PassesTheme.kt:23`
`passes-ui/src/main/kotlin/is/walt/passes/ui/theme/PassesSemantics.kt:38`

ScannableCard-specific tokens live on `PassesSemantics.unverifiedArtifact`
(`UnverifiedArtifactStyle`): `accent`, `captionBackground`, `captionForeground`,
`captionIconTint`. The shipped `Placeholder` default is grayscale for previews
only; **the consumer must provide real tokens before any surface that renders
ScannableCard goes through.**

## 3. Create flow (sequence)

Numbered steps the consumer wires up. Steps 1–5 are walt-android UI; step 6+
crosses into kernel calls.

1. **Entry point.** Consumer's "+" button → "Create a barcode card" menu item
   in the new-artifact picker.
2. **Format picker.** User selects one of the five `ScannableFormat` values.
   Helper text under each option carries the format's length / charset
   summary from §2.4.
3. **Payload field.** Single-line text input. On every change, the consumer
   calls `ScannableCardInputValidator.validate(input, sentinelId, sentinelTime)`
   and renders an inline error from the `PayloadRejection` arm if any; the
   submit button stays disabled while a rejection is showing.
4. **Label field.** Single-line text input, ≤ 64 chars
   (`ScannableCardInputValidator.MAX_LABEL_LENGTH`). Same live-validation
   discipline as the payload field, surfacing `LabelRejection` arms.
5. **Color picker.** User picks a `ScannableColor` (or no color). This is
   pure UI state in walt-android — the value flows into the
   `ScannableCardCreateInput`.
6. **Submit.** Consumer assembles `ScannableCardCreateInput`.
7. **QR branch.** If `format == ScannableFormat.Qr`:
    1. Call `QrPayloadClassifier.classify(input.payload)`.
    2. If `kind.requiresCreateConfirmation()` is `true`, show
       `BarcodeCreateConfirmSheet(payloadKind = kind, ...)`. The user's
       Cancel returns them to the form with state preserved; Confirm
       proceeds to step 8.
    3. If `false` (`PlainText`), proceed directly to step 8.
8. **Persist.** Call `passRepository.createScannableCard(input)`. Branch
   on `StorageResult`:
    - `Success(recordId)` → step 9.
    - `Failure(StorageError.ScannableCardRejected(reason))` → return to form
      with the inline error for the typed reason. This is rare (the live
      validator should have caught it), but it can fire on race / belt-and-
      suspenders paths.
    - `Failure(StorageError.KeyUnavailable | KeyUnwrapFailed | DatabaseLocked
      | Unsupported | Unknown)` → the existing wallet-wide storage error
      surface; not ScannableCard-specific.
9. **Navigate.** Pop the create flow, return user to home with the new card
   in the "Your barcodes" lane (see §5).

```kotlin
val result = `is`.walt.passes.core.ScannableCardInputValidator.validate(
    input = ScannableCardCreateInput(
        payload = userTypedPayload,
        format  = userPickedFormat,
        label   = userTypedLabel,
        color   = userPickedColor,
    ),
    id = sentinelIdForLiveValidation,
    createdAt = clock.now(),
)
when (result) {
    is ScannableCardCreateResult.Success           -> { /* submit-button enabled */ }
    is ScannableCardCreateResult.InvalidPayload    -> showInline(result.reason)
    is ScannableCardCreateResult.InvalidLabel      -> showInline(result.reason)
    is ScannableCardCreateResult.UnsupportedFormat -> showInline(/* format not in this build */)
    is ScannableCardCreateResult.EncoderFailure    -> showInline(/* see §2.2 */)
}
```

```kotlin
// On submit, after live validation passed:
if (input.format == ScannableFormat.Qr) {
    val kind = QrPayloadClassifier.classify(input.payload)
    if (kind.requiresCreateConfirmation()) {
        show(BarcodeCreateConfirmSheet(
            payloadKind = kind,
            telemetry   = uiTelemetryGuard,
            onConfirm   = { persist(input) },
            onCancel    = { returnToForm() },
        ))
    } else {
        persist(input)
    }
} else {
    persist(input)
}

suspend fun persist(input: ScannableCardCreateInput) {
    when (val r = passRepository.createScannableCard(input)) {
        is StorageResult.Success -> navigateHome(newCardAt = r.value)
        is StorageResult.Failure -> when (val e = r.error) {
            is StorageError.ScannableCardRejected -> showInline(e.reason)
            else                                   -> showWalletStorageError(e)
        }
    }
}
```

## 4. UI surfaces and embedding examples

```kotlin
// Home lane: render a card tile, navigate on tap.
LazyRow {
    items(scannableCards) { card ->
        ScannableCardTile(
            card    = card,
            onClick = { navigator.go(ScannableCardRoute(card.id)) },
        )
    }
}

// Detail screen: full-screen scan surface.
@Composable
fun ScannableCardDetail(card: ScannableCard) {
    ScannableCardScreen(card = card)
    // Walt-android adds: overflow menu (rename, delete), max-brightness-while-visible
    // (see wlt-* below). The kernel does not own those affordances.
}
```

The kernel does not ship rename / delete / share / overflow-menu affordances
inside `ScannableCardTile` or `ScannableCardScreen`. Those are walt-android
responsibilities, listed in §7.

## 5. Lane placement guidance

**Default: a separate "Your barcodes" lane below the verified-passes lane on
home.** Rationale: this is the visible UX manifestation of threat-model
control C1 (distinct artifact class end-to-end). Mixing ScannableCards into
the verified-passes lane would re-introduce the visual conflation the kernel
already pays to prevent in `ScannableCardTile`'s rendering. Mixing them
into the PDF documents lane would conflate "passively held document" with
"actively scannable card."

If a future product iteration wants a single unified lane, that requires a
threat-model amendment and a re-audit of C1 / C2, not just a UI change.

## 6. Out of scope for v1

The following are explicitly **NOT** v1 and must NOT be wired in this round:

- **Camera-based payload entry.** Deferred by product owner 2026-05-17.
  Manual typing only. (GitHub #77 will track this as a follow-on.)
- **Photo-as-pass / JPEG upload.** Sibling feature, file separately.
  (GitHub #74.)
- **PDF QR extraction.** Sibling feature, file separately. (GitHub #60,
  #67.)
- **TOTP / HMAC-OATH secret generation.** Out of mission.
- **App Links / Add-to-Walt vendor cooperation.** Long-term.

## 7. `wlt-*` issues to file in walt-android

The auto-classifier blocked filing these from a `passes-android` session on
2026-05-17. A future walt-android session should file the following. Each is
sized for an independent PR; the listed order respects the natural blockers
(DI before UI, UI before lane).

### wlt-* "ScannableCard DI wiring: bind `PassRepository` for ScannableCard methods"
**Type:** task. **Priority:** P2.
walt-android's `core/data-passes` Hilt module already binds
`PassRepository` for the pass and document surfaces. This issue extends
that binding to expose the four ScannableCard methods
(`createScannableCard`, `loadScannableCard`, `deleteScannableCard`,
`observeScannableCards`) and provides the `core/domain-passes`-side wrapper
the feature module calls into. No new repository implementation —
`SqlCipherPassRepository` already implements the interface; the consumer
just needs to surface the methods through its existing DI graph.

### wlt-* "ScannableCard create flow UI: form, format picker, color picker, label field"
**Type:** feature. **Priority:** P2. **Blocks:** the home-lane integration.
Wires the consumer entry point: "+" menu item, format picker (5 options
with helper text from §2.4), payload field with live validation against
`ScannableCardInputValidator`, label field (≤ 64 chars) with live
validation, color picker, submit. On submit the flow: invokes
`QrPayloadClassifier.classify` for QR + `requiresCreateConfirmation()`,
shows `BarcodeCreateConfirmSheet` when required, calls
`passRepository.createScannableCard`, handles each
`ScannableCardRejectionReason` arm with inline error copy. See sequence in
§3 of this spec.

### wlt-* "ScannableCard home-lane integration: `ScannableCardTile` placement under verified passes"
**Type:** feature. **Priority:** P2. **Blocks:** none (final consumer-side piece).
Adds a "Your barcodes" lane below the verified-passes lane on home. Subscribes
to `passRepository.observeScannableCards()` and renders each via
`ScannableCardTile(card, onClick = { navigate to detail })`. The detail
destination wraps the kernel's `ScannableCardScreen(card)` plus the
consumer-owned overflow menu (next issue) and the screen-brightness hook
(issue after that). See lane-placement rationale in §5.

### wlt-* "ScannableCard edit/delete UX: overflow menu + delete-confirm sheet"
**Type:** feature. **Priority:** P3.
The kernel ships no rename / delete / share / overflow affordance inside its
UI surfaces. This issue adds the walt-android overflow menu on the detail
screen with two actions: Rename (re-opens the label field with current
value; submit calls a future kernel `updateScannableCardLabel` — file a
companion bead in `passes-android` first if needed; for v1 ship without
rename) and Delete (shows the wallet's standard delete-confirm sheet, then
calls `passRepository.deleteScannableCard(id)` on confirm). The
`deleteScannableCard` call is irreversible (no undo) — the consumer is
responsible for the confirm UI; the repository trusts the call. See
`PassRepository.kt:140`.

### wlt-* "ScannableCard screen: brightness max while visible"
**Type:** feature. **Priority:** P3.
On the detail screen, walt-android sets the window brightness to max for
the duration the `ScannableCardScreen` is visible, so a scanner picks up
the barcode reliably under poor lighting. Mirrors the existing
PKPASS-detail brightness behavior in walt-android. Tear down the override
on dispose so it does not bleed into other screens. Kernel surface
unaffected.

## 8. Acceptance / done

This spec is complete when:

- The markdown is merged into `passes-android` `main` at
  `docs/handoffs/scannable-card-consumer-wiring.md`.
- A `bd remember` entry exists in `passes-android`'s beads pointing to this
  doc so a future walt-android session surfaces it via
  `bd memories scannable-card`.
- Each `wlt-*` proposal in §7 is concrete enough for a walt-android session
  to refile in five minutes.

`wpass-lzi.10` closes on those three.
