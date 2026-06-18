# ScannableCard generator: threat model

The trust claim that this repository carries is "every security-and-privacy-critical
behavior Walt makes about pass handling lives in code you can read." For the
ScannableCard feature, that decomposes into a per-threat enumeration plus six
load-bearing controls. Each threat is listed below with: what it is, what
control mitigates it, and (for accepted-risk items) the rationale for accepting
the residual exposure.

This document is the security-side companion to the `wpass-lzi` epic
("Manual barcode-card generator"). Where the epic and its children record
*what* gets built and in which module, this document records *why each piece of
it has to exist*.

The structural posture is **new artifact class, not new subtype of `Pass`**.
PKPASS handling guarantees "every `Pass` you see has been cryptographically
verified against the issuer's chain." A ScannableCard has no issuer and no
signature; its bytes were typed by the user. Letting it inherit `Pass`'s shape
would mean inheriting `Pass`'s trust language too, and that would degrade the
verified-PKPASS trust signal to the user's eye. The architecture refuses that
risk by making ScannableCard a sibling at every layer (data model, storage
table, UI lane, trust caption).

## Vocabulary

This document uses STRIDE labels (Spoofing, Tampering, Repudiation, Information
disclosure, Denial of service, Elevation of privilege) where they help classify
a threat. The PDF threat model (`docs/PDF_THREAT_MODEL.md`) uses the same
implicit shape; this doc just names the categories explicitly because the
ScannableCard surface skews more toward spoofing/elevation (user-typed payload
that another device may auto-act on) than toward parser-corruption (the PDF
case).

## Six load-bearing controls

The mitigations below all reduce to combinations of six structural controls.
Each is named here so individual threats can reference them by short label
rather than re-stating the rationale.

**C1. Distinct artifact class end-to-end.** `ScannableCard` is not a `Pass`,
does not implement a `Pass` interface, does not share a sealed parent with
`Pass`, and is not co-iterated with `Pass` in any kernel API. The data model
(`passes-core`), storage table (`passes-storage` — new `scannable_cards`
table, not a column on `passes`), and UI surface (`passes-ui` — separate
`ScannableCardView` / `ScannableCardTile` composables in their own lane) are
sibling structures. A future contributor proposing a unifying
`DisplayableArtifact` interface or a shared lane is amending this document, not
filing a refactor.

**C2. Non-suppressible "Created by you" caption.** Every `ScannableCardTile`
and every full-screen `ScannableCardView` renders the "Created by you"
caption. The caption is not themable away by the consumer (walt-android can
choose its font and color tokens, but cannot hide it or replace the wording).
The tile is also visually distinct from a verified-PKPASS tile by at least two
of: border treatment, leading icon, color band, typography weight — picked for
redundancy so theming a single dimension flat cannot collapse the distinction.

**C1 / C2 — consumer-side concession (wallet-row register).** The kernel
exposes a sibling composable `ScannableCardRowTile` (wpass-pnb; consumer epic
wlt-6ub) for hosts that interleave scannable cards with passes / PDFs in a
single homogeneous wallet list rather than in their own carousel lane. The row
intentionally drops the carousel tile's four-distinguisher contract: there is
no dashed outline, no smaller corner radius vs a `PassFront`-style tile, and no
in-row "Created by you" caption. The concession is permitted strictly when, and
only when, all three of the following hold:

1. The row owns no signature dot or other verified-pass affordance. Pinned at
   the kernel surface by `ComposableSurfaceLockTest.scannableCardRowTileHas
   ExactlyThreeUserVisibleParameters` (count: 3 — `card`, `onClick`, `modifier`;
   no `showSignatureBadge`, no `leadingIcon`) and at the consumer (walt-android)
   by `WalletListTest`'s "no scannable-card row owns a signature dot" invariant.
2. The row owns no coloured leading strip styled to read as a verified-pass
   band. The kernel surface uses the `unverifiedArtifact.accent` token (the
   same neutral token the carousel tile's leading strip uses). Per-card
   user-chosen colour was removed from the kernel (`wpass-q5p`); routing it
   through this strip at list scale would have re-created the trust-conflation
   risk row 1 names.
3. The detail surface (`ScannableCardScreen`) retains the bottom-docked
   non-suppressible `ScannableCardTrustCaption`. The trust caption shifts from
   list-row to detail-surface only; a user who taps a row to *use* the artifact
   still sees "Created by you" before the scan target renders.

The trade is bounded. `ScannableCardTile` and its four-distinguisher contract
remain the kernel's recommended surface for hosts that present scannable cards
in their own lane; `ScannableCardRowTile` is the alternative for the
homogeneous-list register and nothing else. A future consumer wanting a wallet
list that also drops the detail-surface caption is amending this row, not
filing a refactor.

**C2 — detail-surface relocation concession (host details row).** A consumer
(Walt, `wlt-3cer`) wanted to consolidate the trust signal into a single row
inside its own host-rendered details section rather than have it docked at the
bottom of the kernel detail surface (`ScannableCardScreen`). The kernel grants
**relocation, not suppression** (`wpass-gv6`), through the
`ScannableCardScreen(trustCaption = TrustCaptionPlacement.Hosted)` parameter.
The concession is permitted strictly when, and only when, all of the following
hold:

1. The relocated caption is the **kernel-owned `ScannableCardTrustCaption`
   composable**, mounted by the host in its details section. The verbatim
   wording and the layout live only in that composable (pinned by
   `ComposableSurfaceLockTest`); the host chooses *location*, never *content*.
2. A neutral type label is **not** a substitute. A "Pass type: Scanned" row does
   not carry the provenance claim; folding the caption into such a label, or
   showing the label *instead of* the caption, collapses C2. The verbatim
   "Created by you" caption must be present, relocated, somewhere always-visible
   on the detail surface.
3. `TrustCaptionPlacement.Hosted` is the **only** way to move the docked copy.
   There is no boolean that hides it, no theme token that suppresses it, and the
   placement type is `Docked | Hosted` (both render the verbatim kernel caption)
   — pinned by `scannableCardScreenTrustCaptionParamIsThePlacementType`.

The kernel cannot verify at runtime that the host actually mounted the relocated
caption — exactly as it cannot verify condition 3 of the wallet-row concession
above. The obligation therefore shifts to the consumer and is pinned
consumer-side by a walt-android test that the details section renders the kernel
`ScannableCardTrustCaption`. `Docked` remains the default and the recommended
surface; `Hosted` is the bounded alternative for a host that owns an
always-present details section and nothing else. A future consumer wanting to
relocate the caption to a surface that is *not* always present (a collapsible
panel, a tab the user may never open) is amending this row, not filing a PR.

**C3. Input hygiene at the create boundary.** `passes-core` validates
`ScannableCardCreateInput` for: per-format length caps (Code128 ~80 chars,
EAN-13 / UPC-A fixed-length with checksum, QR per-version cap with a
conservative ~2000-char ceiling), per-format charset rules (EAN-13 / UPC-A
numeric only, Code39 limited alphanumeric, Code128 the printable ASCII subset),
and Unicode Cf (Format) / Cc (Control) codepoint rejection in both `payload`
and `label`. The Cf/Cc rejection mirrors the discipline that
`FieldLinkScanner.kt:67` and the PDF document-label path already enforce.

**C4. Create-time URI-scheme preview for QR.** When the user creates a QR
ScannableCard, `passes-core`'s URI-scheme classifier inspects the payload
against a conservative allowlist (`http`, `https`, `tel`, `sms`, `mailto`,
`geo`, `wifi`, `bitcoin`, `ethereum`, `magnet`, `market`, `intent`). A
match (or a "looks URI-shaped but unrecognized scheme" fallback) raises the
walt-android-side confirmation dialog before the row is persisted. The user
must explicitly confirm "yes, this QR is meant to encode an actionable URI."
Pattern source: `B3UrlConfirmSheet` in `passes-ui/src/main/kotlin/.../SecuritySheets.kt`.

**C5. ZXing-JVM as encoder only; no runtime decoding of untrusted bytes.**
The kernel uses `com.google.zxing:core` (Apache 2.0, pure JVM) exclusively to
produce a bit matrix from a user-typed payload + format. The decoder (`zxing`'s
`MultiFormatReader`) is **not** linked, not invoked, and not in the
dependency closure. ZXing has had decoder-side CVEs historically; the kernel
avoids that surface entirely by using only the encoder path. Encoder bugs
remain a concern (see Threat 6) but the attack-surface delta is small because
encoder input is the user's own keystrokes after C3 validation.

**C5 amendment — decoding of untrusted bytes now occurs, under confinement
(wpass-7rv; composite artifact wpass-8lu, consumer epic wlt-yjn5).** The
encoder-only stance above held while the *only* way bytes entered the system
was a user typing a payload. The composite (image + extracted barcode) artifact
and the live-scan path changed that: the kernel now links and invokes a decoder
(`decodeYPlane` in `passes-barcode-core`; `BarcodeImageDecoder` in
`passes-barcode`) on bytes the user did not type. C5 is therefore no longer
"no runtime decoding of untrusted bytes" — it is "untrusted-byte decoding is
confined to a sandbox or to a still-codec-free path," enforced two ways:

- **Static image bytes decode in a permission-stripped ISOLATED process.**
  Gallery picks, file-picker images, and system-camera manual snaps all hand
  their bytes to `BarcodeImageDecoder`'s `isolatedProcess` service, where the
  still-image codec (libwebp / Skia / Quram — the historical RCE class) runs
  with no Keystore, no network, no storage. Only `BarcodeDecodeResult`
  (`{payload, format}`) crosses the binder back; raw bytes never decode in the
  app process. This is the new structural mitigation that lets C5 survive the
  feature.
- **Live-camera frames decode in-process, but carry no still-image codec.**
  `decodeYPlane` reads a first-party `YUV_420_888` luminance plane straight from
  CameraX `ImageAnalysis` — already-decoded sensor pixels, never a file format —
  so the RCE class that justifies isolating the static path is structurally
  absent (wpass-7xo). Decode is pure-JVM ZXing. This is the one place untrusted
  bytes decode in-process, and it is bounded to the no-codec live path.

The decoder (`MultiFormatReader` core) is now in the dependency closure and
invoked, so Threat 6's encoder-CVE cadence extends to decoder advisories on
these two paths (no longer "decoder-only advisories are informational"). The
decoded payload is never trusted as a usable code until the consumer's
confirmation surface (C4 URI-scheme gate / image-keep confirm) clears it — a
misread code cannot silently become a scannable artifact. See Threat 14.

**C6. No secret material in the artifact.** The `ScannableCard` data model
carries `payload`, `format`, `label`, `color`, `createdAt` and nothing else.
There is no `secret`, `hmacKey`, `totpSeed`, `counter`, or any other field
that would let the artifact rotate, derive, or sign anything. 2FA / OATH /
TOTP support, if ever added to Walt, is a separate artifact class with a
separate threat model. This is enforced by what is NOT in the schema, not by
runtime validation.

## Per-threat enumeration

### 1. Visual conflation of unverified ScannableCard with verified PKPASS — Spoofing

**What.** The most consequential failure mode is the **trust-UX failure on
the verified pass it sits next to**, not on the ScannableCard itself. If the
user cannot tell at a glance which tile is a cryptographically verified PKPASS
and which is "a barcode I typed in last week," then Walt's signature-status
ladder (`AppleVerified` / `SelfSigned` / `CertChainIncomplete` / `NoSignature`)
loses meaning. A user who learns to read all tiles as "trusted because they
appear in Walt" is one phishing-PKPASS away from a credential leak — even
though the phishing PKPASS would be correctly tagged `NoSignature` by the
existing PKPASS pipeline.

**Mitigation.** C1 (distinct class end-to-end — data, storage, UI lane) and
C2 (non-suppressible "Created by you" caption with ≥2 visual distinguishing
elements). The two combine: the user sees a different-shaped tile in a
different-titled lane with a different caption.

A bounded consumer-side concession permits a homogeneous wallet-row register
(`ScannableCardRowTile`) when the row carries no signature dot, no
verified-band-styled leading strip, and the detail surface retains the
non-suppressible trust caption. Full conditions and rationale are recorded in
the C1 / C2 concession subsection above. The trust caption shifts from
list-row to detail-surface only; the artifact-class distinction at list scale
is then carried by the absence of pass-only chrome (signature dot, verified
band) rather than by the carousel tile's four redundant distinguishers.

**Status.** Mitigated structurally. This is the load-bearing concern of the
entire epic; every downstream child must trace back to this row.

### 2. Hostile URI payload encoded into a QR that another device auto-acts on — Spoofing / Elevation of privilege

**What.** The user creates a "QR card" intending to encode their library card
number, but pastes (deliberately or by accident, or under social-engineering
pressure) a URI-shaped string: `https://attacker/`, `wifi:S:foo;T:WPA;P:bar;;`,
`bitcoin:1abc?amount=...`, `intent://attacker#Intent;...`. The QR is then
scanned by *another* person's device — a friend's phone, a colleague's, a
kiosk — which may auto-open the URL, auto-join the Wi-Fi, auto-launch the
intent, or auto-prompt a payment. The vector is "Walt as a delivery channel for
URIs the recipient device trusts because the QR is in someone's wallet."

**Mitigation.** C4: at create time, the URI-scheme classifier raises a
confirmation dialog naming the scheme and the rendered payload (with `payload`
wrapped in FSI/PDI bidi isolates per C3). The user must explicitly accept
"this QR is meant to encode an actionable URI" before the row is persisted.
Unrecognized-but-URI-shaped strings (`unknown-scheme://x`) trigger the
fallback warning path rather than silent acceptance.

**Status.** Mitigated, with the residual that a user who clicks through the
preview deliberately is choosing to ship the URI. Walt is not in the business
of overriding user intent; the mitigation is informed consent, not refusal.

### 3. Bidi / control-character spoofing in display label — Spoofing

**What.** `ScannableCardTile` and `ScannableCardView` render the user-supplied
`label` alongside the "Created by you" caption. A `label` containing U+202E
(Right-to-Left Override) or other Cf/Cc codepoints could rearrange visible
glyphs to spoof Walt UI text — e.g. constructing a label that visually reads
"AppleVerified" against the trust caption.

**Mitigation.** C3: `passes-core` validation rejects any `label` containing a
Cf or Cc codepoint, returning `InvalidLabel(BidiOrControlChar)` from
`ScannableCardCreateResult`. The UI layer additionally wraps the
already-validated label in FSI (U+2068) / PDI (U+2069) isolates as
defense-in-depth, mirroring `B3UrlConfirmSheet` and the
`DocumentTrustCaption` discipline.

**Status.** Mitigated, with defense-in-depth at the UI layer.

### 4. Bidi / control-character spoofing in payload preview — Spoofing

**What.** During create-time URI preview (C4), the dialog renders the raw
`payload` so the user can see what they typed. The detail surface
(`ScannableCardScreen`) also renders the payload as a human-readable caption
below the barcode (GH #102 — fallback for when a point-of-sale scanner cannot
read the code; opt-in via `ScannableCardView.showPayloadCaption`). The same
bidi/control-char attack applies to both displays.

**Mitigation.** C3: `passes-core` rejects payloads containing Cf/Cc codepoints
*before* any preview is shown or any caption is rendered. Neither the dialog
nor the detail-surface caption ever receives a payload that could spoof its
surrounding chrome. The fallback "looks URI-shaped but unrecognized scheme"
path goes through the same validator. The UI layer additionally wraps the
caption in FSI (U+2068) / PDI (U+2069) isolates as defense-in-depth, mirroring
the label-isolation discipline in threat #3.

**Status.** Mitigated.

### 5. Payload-size denial of service — Denial of service

**What.** QR supports up to 7089 numeric characters per code (version 40);
the matrix size grows quadratically. A user (or paste from a hostile source)
that submits a maximum-capacity QR causes a slow encode and an oversized
on-screen matrix that may struggle to render at sane DP. The kernel encoder is
fast (<50ms for typical payloads) but degrades visibly at the upper end.

**Mitigation.** C3: per-format payload caps codified in `passes-core` and
returned as `InvalidPayload(TooLong)` before the encoder runs. Conservative
ceilings: Code128 80 chars, EAN-13 / UPC-A fixed by format, Code39 80 chars,
QR ~2000 chars. The 2000-char QR ceiling sits well below format max but well
above any realistic library / loyalty / URL payload.

**Status.** Mitigated by hard caps. Caps are enforced in `passes-core` (first
line) and `passes-storage` (second line, as a row-level constraint, defense
in depth so a future encoder-bypass call cannot land an oversized blob).

### 6. ZXing encoder CVE exposure — Tampering / Denial of service

**What.** ZXing has had public CVEs across its history (mostly decoder-side,
some encoder-side). The kernel takes a runtime dependency on its encoder code
path, so a CVE in `MultiFormatWriter` / `QRCodeWriter` / `Code128Writer`
becomes a Walt CVE exposure window.

**Mitigation.** C5 narrows the linked surface to encoder classes only
(decoder symbols are not invoked, so even if linked they are unreachable from
the Walt code path). The dependency is pinned to a specific Maven version in
`gradle/libs.versions.toml`. The upgrade policy is: monitor ZXing's GitHub
security advisories monthly, upgrade within 30 days of a published advisory
that affects encoder code, treat decoder-only advisories as informational.
Track this as `wpass-lzi.X` (encoder-dependency hygiene) if the cadence
becomes operationally heavier than that.

**Updated by the C5 amendment (wpass-7rv).** The decoder is now linked and
invoked on the composite image-decode and live-scan paths, so decoder advisories
are no longer informational: apply the same 30-day cadence to decoder CVEs that
are reachable from `decodeYPlane` / `BarcodeImageDecoder`. The blast radius of a
*static*-path decoder bug is contained to the isolated process (C5 amendment),
which lowers severity but does not remove the upgrade obligation.

**Status.** Mitigated by surface reduction (C5) and a stated cadence; the
upgrade-cadence bead is the follow-up artifact.

### 7. Cross-artifact exfiltration via storage compromise — Information disclosure

**What.** A bug elsewhere in the kernel that grants out-of-process read access
to the SQLCipher database would also expose ScannableCard rows. The threat is
inherited from the PKPASS / PDF storage posture, not new to this feature.

**Mitigation.** ScannableCard rows live in the same SQLCipher database as
PKPASS and PDF rows, with the same Keystore-sourced key, the same Auto Backup
exclusion (XML rules pattern from `passes-storage`), and the same
irreversible-delete contract. The threat model from `wpass-9vv.1` (closed) and
the `passes-storage` ADR (0002) cover the cross-cutting controls; this
section just records that the new table inherits them by living in the same
database.

**Status.** Mitigated by inheritance from the existing storage posture.

### 8. Auto Backup leakage of payload to Google — Information disclosure

**What.** Android's Auto Backup, if not excluded, would upload the SQLCipher
database file to the user's Google account by default. ScannableCard payloads
(library numbers, loyalty IDs, occasionally something more sensitive) would
land in a third-party cloud the user did not explicitly opt into for this
data.

**Mitigation.** The `passes-storage` Auto Backup exclusion (XML
`<exclude/>` rule covering the encrypted database file) already applies; the
new `scannable_cards` table sits inside the same DB file. The
`wpass-lzi.6` storage child must verify the exclusion still covers the
extended schema (regression test, not a new mechanism). Documented as a
storage-bead acceptance criterion, not a new control here.

**Status.** Mitigated by inheritance, with regression-test acceptance criterion
on `wpass-lzi.6`.

### 9. Cashier / POS accepts a forged loyalty number — Out of scope / Accepted-by-architecture

**What.** A user could type any merchant's loyalty number into a
ScannableCard and present it at checkout. The cashier's scanner reads the
number and the POS may credit the points to an account the user does not own.

**Mitigation.** None applicable from Walt. **Walt is a display device, not an
issuer or an authentication authority.** The POS is the authoritative trust
boundary for "is this loyalty account credit-worthy"; if a POS accepts an
account number without an additional auth signal (PIN, app login, ID check),
that is the POS's threat model, not Walt's. A "server-side validation" hook
inside Walt that tried to call merchant APIs to validate numbers would
introduce a new attack surface (key custody, request-replay, merchant-side
phishing-of-Walt) without addressing the underlying problem — POS designs that
accept unauthenticated account references will accept them whether displayed
from Walt, Google Wallet, the merchant's own app, or a printed plastic card.

**Status.** Out of mission. Documented here so future contributors do not
propose adding it without amending this row.

### 10. Color picker as injection vector — n/a

**What.** Hostile inputs to a color picker have historically been a vector in
browser CSS / SVG parsers (named-color injection, `var(--…)` escapes). A
naive "type a hex code" picker could allow inputs that round-trip as something
unexpected.

**Mitigation.** The kernel no longer exposes a per-card user colour at all
(`wpass-q5p` removed `ScannableCard.color` and `ScannableColor`). With no
colour field on the artifact, the kernel has no colour parsing or storage
surface to attack, and the consumer's create flow no longer presents a colour
picker.

**Status.** Removed at the kernel; no parsing surface remains.

### 11. Future TOTP / HMAC-OATH secret leakage — Explicit non-feature

**What.** A common feature-creep request on barcode wallets is "let me also
store my 2FA codes here." The TOTP / HMAC-OATH shared secret is a long-lived
credential whose compromise is materially worse than a loyalty-number leak.
Storing such secrets next to plaintext loyalty payloads under the same data
class would mean a single bug exposes both.

**Mitigation.** C6: the `ScannableCard` data model has no field that can
carry a secret. 2FA support, if it ever ships in Walt, will be a separate
artifact class with a separate storage table, a separate UI surface, and its
own threat model that addresses key rotation, screenshot blocking, biometric
gating, etc.

**Status.** Out of scope by structural refusal.

### 12. Encoder output cache poisoning — n/a

**What.** A renderer that caches encoded bit matrices keyed on `payload`
could in principle return a stale or wrong matrix for a given input.

**Mitigation.** The encoder is deterministic and fast (<50ms typical) per
parent epic open question #6, so the kernel runs it synchronously per render
without an LRU. If a future optimization adds caching, it must key on
`(payload, format, version, errorCorrection)` and is a separate review.

**Status.** n/a in v1; future-revisit gated by epic open question #6.

### 13. Walt-android consumer-side attack surface — Out of scope (called out)

**What.** The walt-android form (text entry, format dropdown, name field,
color picker) is a new attack surface introduced by the consumer. Risks
include: clipboard auto-paste of secrets, an accidental enter-key submit
before the URI preview renders, focus-stealing during the confirmation dialog,
and the standard consumer-side UI hygiene set.

**Mitigation.** Mitigations are filed against walt-android's `wlt-*` issue
tracker, not against this document. The cross-repo handoff spec
(`wpass-lzi.10`) enumerates the exact obligations on the consumer (where the
URI dialog must intercept, what the field must reject pre-submit, how
clipboard-paste interacts with the preview). This document records that the
boundary exists.

**Status.** Out of scope for this document; tracked as `wlt-*` follow-ups via
the handoff spec.

### 14. Composite image-decode: hostile image bytes / misread payload — Remote code execution / Tampering

**What.** The composite (image + extracted barcode) artifact (`wpass-8lu`,
consumer epic `wlt-yjn5`) introduces two new ways untrusted data enters the
system, neither of which existed for the type-it-yourself ScannableCard:

1. **Hostile image bytes.** A user imports an image (gallery, file picker, or a
   system-camera snap) that the kernel must decode to find a barcode. A crafted
   image targeting a still-image codec bug (libwebp / Skia / Quram have a CVE
   history of heap overflows reachable from a single malformed file) could
   achieve code execution at decode time.
2. **Misread payload presented as authoritative.** The decoder could misread a
   barcode (damaged scan, ambiguous symbology) and, if the result were stored
   silently as a usable code, the user would later present a wrong loyalty /
   ticket number at a POS believing it correct.

**Mitigation.** Decode-surface confinement plus mandatory confirmation:

- **Static image bytes decode only in the permission-stripped isolated
  process** (`BarcodeImageDecoder`), so a codec RCE is contained to a sandbox
  with no Keystore / network / storage — see the C5 amendment. This covers all
  three still sources; the system-camera snap is first-party bytes written to an
  app-private cache file, but is decoded through the same isolated path so a
  malicious gallery/file substitution gets identical treatment.
- **Live auto-detect** decodes in-process but only first-party sensor YUV (no
  still-image codec; C5 amendment, wpass-7xo), and produces a *code-only*
  artifact — no image is retained on that path.
- **The decoded payload is never a usable code until confirmed.** Actionable
  payloads (URI schemes) raise the C4 create-time confirmation; the composite
  also passes a consumer-side image-keep confirm before persist. Decode is *not*
  the trust boundary — user confirmation is. A misread that the user does not
  recognise is the residual risk, bounded the same way Threat 9 bounds a typo:
  Walt is a display device, the POS is the authority.
- **Transient camera stills are swept** to one-at-most in the consumer's cache
  (`wlt-noq5`); the persisted image lives only in SQLCipher (Threats 7, 8
  inherited).

The image-codec RCE class is the kernel's to contain (isolated process); the
manual-snap "system camera, never CameraX `ImageCapture`" rule and the
confirmation surfaces are the consumer's, recorded in walt-android
`docs/decisions-and-learnings.md`. Decoder advisories on these paths are now
in-scope for the Threat 6 upgrade cadence.

**Status.** Mitigated by isolation (static bytes) + no-codec path (live frames)
+ mandatory confirmation. Consumer obligations verified on-device in `wlt-yjn5.1`.

## Inventory: PKPASS controls and ScannableCard equivalents

| PKPASS control                                  | ScannableCard equivalent                                                                |
|-------------------------------------------------|-----------------------------------------------------------------------------------------|
| `ParserConfig.maxArchiveBytes` (10 MB)          | Per-format payload caps in `passes-core` (Code128 80, QR ~2000, etc.); enforced again at storage |
| Manifest hash + PKCS#7 signature                | Not applicable; provenance is "user-typed", structurally a sibling class                |
| `SignatureStatus` four-band badge               | "Created by you" caption + visually distinct tile in its own lane (C1 + C2)             |
| `FieldLinkScanner` Cf/Cc rejection              | Same posture; applied to `payload` and `label` at the create boundary (C3)              |
| `B3UrlConfirmSheet`                             | Create-time URI-scheme preview for QR payloads (C4); same dialog pattern                |
| `ExpiredOverlayState`                           | Not applicable (no expiration metadata; user can delete)                                |
| `TelemetryGuard` PII discipline                 | New `ScannableCardTelemetryGuard` mirrors the existing discipline (counts / formats only, no payload bytes) |
| Encrypted-at-rest (SQLCipher) + Auto Backup off | Same database, same XML rules apply automatically                                       |
| Irreversible delete with cache wipe             | Same `ON DELETE CASCADE` and unwind contract; no encoder cache to wipe in v1            |
| Apple WWDR root chain                           | n/a — no issuer chain exists for user-typed input                                       |
| (n/a)                                           | New: distinct artifact class end-to-end (C1)                                            |
| (n/a)                                           | New: encoder-only ZXing surface (C5)                                                    |
| (n/a)                                           | New: no-secrets schema (C6)                                                             |

## Explicit non-features

The list below is load-bearing: a future contributor proposing any of these
items must amend this document, not just file a PR. The non-features below are
not "deferred to v2"; they are deliberately absent because each one re-opens a
threat row above.

- **No unifying `DisplayableArtifact` interface or shared lane.** Re-opens row 1.
- **No camera or image-upload payload entry in v1.** Deferred by product owner
  2026-05-17. Image-upload re-opens an OCR + binary-decoder surface that is
  out of scope; manual typing keeps the input boundary minimal. Sibling
  feature, file separately.
- **No "Verified" or "Trusted" badge on any ScannableCardTile** under any
  combination of consumer theming. C2 forbids it.
- **No server-side validation of payloads against merchant APIs.** Re-opens
  row 9 and adds an entirely new key-custody / network surface.
- **No TOTP / HMAC-OATH / rotating-secret support.** Re-opens row 11.
- **No ZXing decoder symbols in the dependency closure.** Re-opens row 6.
- **No payload-bytes telemetry.** The `ScannableCardTelemetryGuard` may
  surface format counts and create/delete event counts; payload contents and
  payload length distributions are PII and never leave the device.
- **No bypass of the "Created by you" caption on the detail surface**
  (`ScannableCardScreen`), through theming, layout, or consumer-supplied
  composables. The list-row register (`ScannableCardRowTile`) shifts the
  caption from list-row to detail surface under the bounded C1 / C2
  concession above; the detail surface itself remains non-suppressible. The
  `TrustCaptionPlacement.Hosted` parameter (`wpass-gv6`) *relocates* the
  detail-surface caption into a host details row under the bounded C2
  relocation concession above — it does not suppress it (the verbatim kernel
  caption must still be mounted, always-visible, by the host). C2 forbids any
  bypass beyond those two bounded concessions: no boolean that hides the
  caption, no substitution of a neutral type label for the verbatim claim.

## How each control is tested

Each downstream child of the epic carries the tests pinning its slice of the
above controls. The mapping is recorded in the children's acceptance criteria;
the matrix below is the at-a-glance summary so a reviewer of any single child
can trace back here.

| Control | Pinned by                                  |
|---------|--------------------------------------------|
| C1      | `wpass-lzi.2` (data model surface test), `wpass-lzi.6` (separate table assertion), `wpass-lzi.8` (separate-lane composable test) |
| C2      | `wpass-lzi.8` (non-suppressible caption test, ≥2-distinct-elements snapshot); `wpass-pnb` adds `scannableCardRowTileHasExactlyThreeUserVisibleParameters` to pin the wallet-row concession shape, and `rowTileDoesNotRenderTrustCaption` / `rowTileRendersFormatSubtitle` to pin the caption-shift contract; `wpass-gv6` adds `scannableCardScreenHasExactlyFourUserVisibleParameters` + `scannableCardScreenTrustCaptionParamIsThePlacementType` (relocation-not-suppression shape lock) and `fullScreenHostedPlacementOmitsKernelDockedCaption` / `hostedPlacementHostStillRendersKernelCaptionVerbatim` to pin the detail-surface relocation concession |
| C3      | `wpass-lzi.4` (length caps, charset, Cf/Cc rejection unit tests)             |
| C4      | `wpass-lzi.5` (URI classifier unit tests), `wpass-lzi.9` (dialog gating test) |
| C5      | `wpass-lzi.3` (encoder integration). C5 amendment (wpass-7rv): the original "decoder not in dependency closure" build assertion no longer holds — decode confinement is pinned instead by the isolated-decode tests (`BarcodeDecodeServiceInstrumentedTest`, `YPlaneFrameDecodeTest`) and, consumer-side, by walt-android `CompositeImportInstrumentedTest` (no host-process decode of source bytes) + `CameraScanSecurityGuardTest` (no CameraX `ImageCapture` in `src/main`) |
| C6      | `wpass-lzi.2` (schema snapshot — no `secret`/`hmac`/`totp` fields permitted) |

## Out of scope for this document

- The walt-android consumer-side form, picker, lane integration, and intent
  surface — tracked as `wlt-*` issues per the handoff spec (`wpass-lzi.10`).
- The wallet's payment / HCE surface — unchanged by this feature; documented
  in the parent payment-side trust documentation.
- The PKPASS / PDF artifact classes — documented in their own threat models
  (referenced in `SECURITY.md` and `docs/PDF_THREAT_MODEL.md`).
- Performance tuning of the encoder — implementation detail of `wpass-lzi.3`;
  not a security control.
