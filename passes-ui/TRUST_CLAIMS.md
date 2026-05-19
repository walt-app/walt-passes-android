# passes-ui trust-claim-bearing UI flows

The trust claim that this repository carries is "every security-and-privacy-critical
behavior Walt makes about pass handling lives in code you can read." For `passes-ui`,
that decomposes into six surfaces. Each is described below with: what the user sees,
what the trust claim is, and how the implementation bead's tests pin it.

## 1. Signature trust badge

**What the user sees.** A small pill on every rendered `PassFront`, color-coded by
`SignatureStatus`:

| Status                  | Pill copy           | Meaning                                                  |
|-------------------------|---------------------|----------------------------------------------------------|
| `Unsigned`              | "Unsigned"          | The archive carried no signature.                        |
| `SelfSigned`            | "Self-signed"       | A signature was present but did not chain to Apple's CA. |
| `AppleVerified`         | "Verified"          | The PKCS#7 signature chained to Apple's WWDR root.       |
| `CertChainIncomplete`   | "Signature unknown" | A signature was present but the chain was incomplete.    |

**Trust claim.** The badge reflects the `SignatureStatus` recorded by `passes-core` at
parse time and persisted by `passes-storage`. The UI cannot upgrade or downgrade a
status, and the status cannot be rebound by the host.

`PassFront.showSignatureBadge` (default `true`) is a presentation opt-out introduced
in wpass-btz so a host that has already disclosed the band in its own chrome (e.g.
walt-android's import-confirm `TrustChip`) does not double-label the user with two
visually different "verified" affordances. The band itself remains
non-suppressible: `onPassRendered(type, band)` fires regardless of the boolean, and
the underlying status is still derived from `passes-core`. A host that opts out
without disclosing the band elsewhere is breaking the contract — pinned in code by
`ComposableSurfaceLockTest.passFrontHasExactlyEightUserVisibleParameters`, which
forces a reviewer conversation if anyone tries to add a third suppression
parameter that would let a host hide *both* the badge and any cross-chrome
disclosure.

**How tested.**

- `PublicApiSurfaceTest` (already in this skeleton) pins `SignatureBand` to the four
  documented bands.
- The implementation bead's screenshot tests render every band and assert the pill
  color comes from `PassesSemantics.signatureBadge.<bandBackground>`.
- The implementation bead's instrumentation tests assert that there is no public API
  shape that lets a caller pass an alternative `SignatureStatus` for display while
  storing a different one. The badge always reads from the same `SignatureStatus`
  the storage row recorded.

## 2. URL confirmation (B3 sheet family)

**What the user sees.** When the user taps a URL on the back of a pass, a bottom
sheet rises that shows: the issuer's `organizationName`, the source field's label,
the verbatim URL the host is about to open, and a "Confirm" / "Cancel" pair.

**Trust claim.** Three parts.

1. *No URL leaves the device without the user seeing the verbatim target.* The host
   does not open `Intent.ACTION_VIEW` for a pass-derived URL except inside the
   confirm callback of `B3UrlConfirmSheet`. This holds for both
   `B3UrlEmphasisStyle.Container` (the original layout) and
   `B3UrlEmphasisStyle.DomainHero` (the wpass-48v layout): the latter places the
   verbatim URL in a smaller monospace "forensic row" rather than the orange
   container, but the string is still on-screen before the user can tap confirm.
2. *The displayed URL is identical to the URL that opens.* The sheet does not show
   `example.com` and open `attacker.example`. The string in [B3UrlIntent.url] IS the
   string the host hands to its `Intent`. The `DomainHero` layout's "hero" line
   shows [B3UrlIntent.registrableDomain] when present, but this is a presentation
   aid above the verbatim row — never a substitute. A `B3UrlIntent` constructed
   with a hero string that disagrees with the URL still routes the URL to the
   `Intent`; the registrable-domain field is structurally non-actionable.
3. *The displayed URL is rendered as it was typed.* The sheet defends against the
   Unicode-bidi class of attacks, where a pass author embeds U+202E
   (Right-to-Left Override), zero-width marks, or other formatting/control
   characters in a URL so the visual rendering of the string differs from the
   bytes that resolve via `Uri.parse`. Two layers:
   - **Detection-stage rejection.** `FieldLinkScanner.containsRenderingHazard`
     rejects any field whose value contains a Unicode Cf (format) or Cc (control)
     codepoint. The rejection is field-level: a clean URL adjacent to a hostile
     one in the same field becomes non-tappable too, since the surrounding
     context is untrustworthy.
   - **Rendering-stage isolation.** The sheet wraps every user-controlled string —
     the URL, the issuer's organization name, and the source field's label — in
     U+2068 / U+2069 (FSI / PDI) bidi isolates. Within the isolate, the Unicode
     Bidirectional Algorithm cannot reorder glyphs across the boundary, so any
     residual directional context from chrome cannot reorder the displayed target
     and any directional content within cannot leak outward.

**How tested.**

- `PublicApiSurfaceTest` pins the three arms of `SecurityIntent`.
- `FieldLinkScannerTest` includes 11 bidi-spoofing cases: U+202E,
  U+200B (zero-width space), U+200E (LTR mark), U+061C (Arabic Letter Mark),
  ASCII control bytes, the same hazards in phone and email positions, and a
  "clean URL adjacent to hostile URL" case that asserts the entire field
  surfaces no links. Plus `urlBytesAreVerbatimNoCfStripping` (a clean URL passes
  through byte-equal — no silent sanitization) and the categorization helper
  test covering Cf and Cc enumerable points.
- `TrustClaimSurfaceTest.securitySheetUrlIsBidiIsolated` and
  `securitySheetIsolatesOrganizationName` verify the sheet's rendered text is
  bracketed by FSI / PDI.
- The implementation bead's instrumentation tests will assert that no
  `ACTION_VIEW` intent is dispatched in the test harness without the user
  crossing through the sheet.

## 3. Phone confirmation

**What the user sees.** Identical structure to the URL sheet: organization name,
source field label, verbatim phone digits, confirm/cancel pair.

**Trust claim.** A pass-embedded phone number cannot trigger `Intent.ACTION_DIAL`
without explicit confirmation. The displayed digits are the digits dialled.

**How tested.** Same shape as URL: the implementation bead pins the contract and
asserts no dial intent fires without confirmation in the test harness.

## 4. Email confirmation

**What the user sees.** Same structure: organization name, source field label,
verbatim email address, confirm/cancel pair. The composer launched on confirm
contains ONLY the address; the pass-supplied subject line and body are NOT
forwarded into the composer's pre-fill.

**Trust claim.** A pass cannot pre-fill the user's outbound email beyond the
recipient address. (Subject and body could be a phishing vector if pre-filled
under the user's display name.)

**How tested.** The implementation bead's tests assert the resulting `Intent`'s
extras do not contain `Intent.EXTRA_SUBJECT` or `Intent.EXTRA_TEXT` under any
input shape from the pass.

## 5. Expired / voided overlay

**What the user sees.** A pass that has either passed its `expirationDate` or been
marked `voided: true` carries a dim scrim over its front and a pill reading
"Expired" or "Voided".

**Trust claim.** A pass whose validity window has closed cannot present as valid
*without the host disclosing that status elsewhere on the same screen*. The kernel
overlay itself has no `enabled` parameter on `ExpiredOverlay` and no theme token
that hides it; what `PassFront.showExpiredOverlay = false` (wpass-d0k) does is
suppress the **scrim composition step** on `PassFront` specifically, for the
single case where the host's own chrome already presents the expired/voided state
(e.g. walt-android's archival detail surface with a `PAST PASS` eyebrow and an
`Expired {date}` pill). The underlying pass data is unchanged — the expiration
date and voided flag still flow through to the host — and `ExpiredOverlay` itself
remains non-suppressible if called directly. A host that suppresses the overlay
without rendering an equivalent disclosure is breaking the contract; the bounded
shape of the opt-in (a single suppression boolean on a single composable, with no
overload that lets a host *display* an arbitrary expiry state) is what makes the
breach legible at code-review time.

**How tested.**

- `PublicApiSurfaceTest` covers `ExpiredOverlayState.from` end-to-end: voided over
  date, equal epoch as expired, future as none, no expiration as none.
- The implementation bead's screenshot tests render expired and voided passes and
  assert the scrim and pill are present.
- The implementation bead's API-surface lint asserts there is no overload of
  `PassFront` that accepts an explicit `ExpiredOverlayState` (the only path is via
  `ExpiredOverlayState.from`, computed inside the composable from the pass's own
  fields).

## 6. Bounded image rendering

**What the user sees.** Pass images (logo, icon, strip, background, thumbnail,
footer, plus their @2x and @3x variants) are decoded and displayed at the size the
layout calls for. A maliciously oversized image is silently replaced with a
placeholder.

**Trust claim.** A hostile pass archive cannot OOM-crash the host process via a
multi-gigabyte bitmap allocation. The decode pipeline applies `ImageRenderBounds`
at decode-header time, so the decoder never allocates a backing bitmap larger than
the configured ceiling. The host's process stays alive even when handed a 50000 x
50000 PNG.

**How tested.**

- `PublicApiSurfaceTest` pins `ImageRenderBounds.Default` to 1920 x 1920 / 4 MP and
  rejects non-positive dimensions at construction.
- The implementation bead's instrumentation tests feed a known-oversized PNG
  (generated in-test, not committed) and assert the resulting bitmap dimensions are
  bounded AND that `UiTelemetryGuard.onImageDecodeRejected(ExceedsArea)` fired.
- The implementation bead's docs lock the rule that Coil's default decoder is NOT
  used for pass images; only the `ImageDecoder.setOnHeaderDecodedListener` path is.

## Out of scope for `passes-ui`

The trust claim "encrypted at rest" is `passes-storage`'s problem; this module does
not re-state it. The trust claim "PKCS#7 signature verified against Apple's WWDR
root" is `passes-core`'s; this module displays the result.

This module's contribution to the audit trail is exactly the six surfaces above.
