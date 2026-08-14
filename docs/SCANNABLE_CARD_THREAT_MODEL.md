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

**C1 / C2 — consumer-side concession (wallet-row register): WITHDRAWN
(`wpass-80y.4`, 2026-08-10).** This row permitted a homogeneous wallet-row
register, `ScannableCardRowTile` (wpass-pnb; consumer epic wlt-6ub) — a flat
label-led row with a neutral leading strip, for hosts interleaving scannable
cards with passes / PDFs in one list instead of a dedicated carousel lane. It
dropped the carousel tile's four-distinguisher contract, including the in-row
"Created by you" caption, in exchange for three conditions: no signature
affordance on the row, no leading strip styled to read as a verified-pass band,
and the detail surface retaining the non-suppressible caption.

**The concession is withdrawn because the surface it permitted no longer
exists.** `ScannableCardRowTile` is deleted from `passes-ui`, along with its
`leadingSlot` hook (wpass-2a2) and its surface-lock and behavioural tests. The
26.08.08 redesign replaced the consumer's homogeneous row list with a stacked
deck of class-tinted card faces (walt-android `WalletStack`, wlt-38v8.3), and
no consumer composed the row tile at the point of deletion. A trust-reasoned
surface that nothing ships is a liability in an audit trail: it invites a
future contributor to adopt it on the strength of a concession argued for a
list shape that is gone.

Two things this withdrawal is **not**. It is not a finding that the row shape
was unsafe — conditions 1 and 3 held for its whole life. And it is not a
statement about colour: condition 2's "would re-create the verified-band read
at list scale" rationale had already lost its premise to the "colour carries no
trust meaning" row below, and the honest resolution of `wpass-80y.4` is that
the question the condition posed became moot rather than answered.

Reintroducing a homogeneous row register — kernel-side or by a consumer
reimplementing one — is amending this document, not filing a refactor. It would
have to re-argue conditions 1 and 3 from the current premises (see the "colour
carries no trust meaning" row for what colour can and cannot carry) rather than
restore this row as written. `ScannableCardTile` and its four-distinguisher
contract remain the kernel's surface for hosts that present scannable cards in
their own lane.

**C1 / C2 — list-face code render concession (wpass-tjc.2; consumer epic
wlt-mx2d).** The redesigned wallet list renders a scannable card's — and a
composite artifact's extracted — ACTUAL code on its list card face, through the
kernel's `CompactCodeView` (`(payload, format)` only). This goes one step past
the wallet-row concession above: its condition 3 reasoned that "a user who taps
a row to *use* the artifact still sees 'Created by you' before the scan target
renders," and a code usable at a reader straight from the list means the user
may never tap through at all. That shift is recorded here, and it is permitted
strictly when all three of the following hold:

1. The kernel render stays mechanism-only. No label, eyebrow, caption, or
   signature affordance can be composed inside `CompactCodeView`; the shape is
   pinned by `ComposableSurfaceLockTest.compactCodeViewHasExactlyFourUser
   VisibleParameters` (count: 4 — `payload`, `format`, `modifier`,
   `contentDescription`) and by its no-overload lock.
2. The consumer card carries the C1 class distinction structurally: a neutral
   (never issuer-colored) card surface with the artifact class named in the
   eyebrow ("QR CODE" / "CODE 128" / "IMAGE, QR CODE"), and no verified
   affordance of any kind on any list card. Only user-created codes render at
   list scale — the payload is user-authored, not a trust signal. Signed pkpass
   barcodes stay detail-surface-only; a pass card face never renders its code
   in the list.
3. Detail-surface provenance is unchanged: the bottom-docked caption by
   default, or the audited `HostedTypeRow` carrier under the concession below.

**Accepted residual risk.** A user who scans straight from the list never meets
the in-words provenance signal for that use. This is judged bounded for the
same reasons as the foldout placement below: the artifact is the user's own
creation, the list-level class distinction (condition 2) is still in view at
the moment of scanning, and Walt is a display device, not an issuer — the POS /
recipient remains the authority on whether the code is credit-worthy (Threat
9). A consumer wanting to render a list-face code without the condition-2
eyebrow taxonomy, or wanting pkpass barcodes at list scale, is amending this
row, not filing a PR.

> **Dormant (wpass-80y, 2026-08-08).** The consumer no longer exercises this
> list-face code render concession (the wallet-row concession above is
> unaffected and stays live): the 26.08.08 rules block quoted in the "colour
> carries no trust meaning" row below says "No previews on list cards", and the
> redesign removes the code tile, barcode band, image badge and photo thumbnail
> from every list card face. The concession is not withdrawn - `CompactCodeView`
> stays, and a host may still render an extracted code at list scale - but it is
> unexercised, and condition 2's "neutral (never issuer-colored) card surface"
> is replaced by the class tint that the "colour carries no trust meaning" row
> grants. Conditions 1 and 3 govern unchanged for any host that takes the
> concession up again, and the accepted residual risk above applies only while
> it is exercised.

**Redesign context — where the C1 list distinction now lives (wpass-tjc.4;
consumer epic wlt-mx2d).** The redesigned wallet list changes what
distinguishes card classes, and the change is recorded here so the
verified-band confusion class can be checked against it:

> **Partly superseded (wpass-80y, 2026-08-08).** The first bullet's
> issuer-colour posture no longer holds: colour is decoupled from the issuer
> and applied to every artifact class. The second bullet (no verified
> affordance at list scale) stands and is now load-bearing. See the
> "colour carries no trust meaning" row below.

- **Issuer `pass.json` colors now drive pkpass list-card faces** (previously
  parsed but unused off the pass front; ADR 0003 D4 addendum). Issuer color
  appears on pkpass cards ONLY: document, scannable, and composite cards keep
  the neutral surface + class eyebrow posture of condition 2 above (document
  eyebrows "PDF" / "IMAGE" per the ADR 0005 list-surface addendum). A white issuer card
  is distinguished from the wallet background by a hairline, not by tinting —
  color never becomes the class signal.
- **No list card of any class carries a signature or verified affordance.** The
  trust ladder (signature badge, trust captions) lives exclusively on detail
  surfaces. The verified-band confusion class is therefore structurally absent
  at list scale: there is no verified visual for a user-created card to
  imitate, which is a stronger posture than the per-row distinguisher
  accounting the original C1/C2 text assumed. Pinned consumer-side: the
  `WalletListTest` "no signature dot" invariant extends to every card class
  under `wlt-mx2d` (pin lands with the consumer redesign; until then the
  existing scannable-row invariant holds).

**C1 / C2 - colour carries no trust meaning, for any class (wpass-80y;
consumer epic wlt-38v8).** The C1 / C2 text above, and the redesign row that
precedes it, treated colour as trust-adjacent: issuer colour was permitted on
pkpass cards because it read as pass identity, and forbidden on document,
scannable, and composite cards because taking it was what would let a
user-authored artifact present as issuer-signed. The consumer's 26.08.08
revision removes that premise entirely. Its rules block, verbatim:

> Colour means class. Nothing else. The pass file's backgroundColor is read,
> never rendered.
> Card stock is identical in light and dark. No previews on list cards.
> Expired washes out (#EFEAE3) instead of tinting. Any item is reassignable:
> Details > Color.
> No card presents as verified - signature is stated in words, on detail.

Three changes matter to this document. Colour is (a) decoupled from the issuer,
so every pkpass renders the same class tint regardless of `pass.json`
`backgroundColor`, which is parsed but not rendered at that surface; (b)
applied to every artifact class, so documents, scannables and composites now
carry a tint where the neutral-surface rule previously forbade one; and (c)
user-reassignable per item from a picker on the detail surface, so any artifact
can carry any of the seven palette colours.

**The verified-band confusion class is still closed, from different premises.**
It no longer rests on "a user-created artifact cannot wear the chrome a signed
pass wears," because with per-item reassignment there is no chrome that only a
signed pass can wear. It rests on these instead:

1. **No surface presents as verified.** No list card of any class renders a
   signature affordance, verified band, or pill (the consumer's last one was
   deleted under `wlt-l9cb`), and no card face carries one at detail scale
   either. There is no verified visual for a user-created card to imitate -
   the same structural argument the second "Redesign context" bullet makes,
   now carrying the load the colour distinction used to share.
2. **Signature status is stated in words, on the detail surface only.**
   "Verified - signed by issuer" / "Unverified - unsigned by issuer" /
   "User provided pass, issuer cannot be verified". Text, not chrome, so it
   cannot be imitated by a colour choice; `wlt-mx2d.7`'s
   no-false-forensic-claims rule holds verbatim.
3. **Colour is uniform per class by default and overridable by the user.** A
   Bronze PDF or a Teal pkpass is a user preference, and this document says so
   out loud: it is not a violation, not a spoof, and not something a consumer
   should guard against. Colour that any user can reassign at will cannot
   encode provenance even accidentally - which is precisely why it is safe to
   grant, and precisely why nothing may be inferred from it.

**What this means at the kernel surface.** `ScannableCardScreen(faceTint = …)`
(wpass-80y.1) and `DocumentView(faceTint = …)` (wpass-80y.2) exist so the
consumer can tint the card face without reimplementing these surfaces. Both are
presentation-only, and both are bounded by the same four rules:

- The tint reaches the card face only. The panel behind a code stays
  `SCAN_CODE_PANEL` (literally white in both themes) and the rasterised page /
  decoded image render identically tinted or not. Real content stays
  theme-independent and scannable; the unchanged-posture claim in C5 / Threat 14
  is untouched.
- Neither parameter can suppress the barcode, the payload readback, or the
  trust caption. `faceTint` is not a second route to the C2 bypass that the
  `HostedTypeRow` concession audits: the caption placement is still the only
  audited carrier-of-provenance choice.
- Ink on a tinted face is derived from the tint's luminance (`inkOn`), pinned
  at ≥ 4.5:1. Legibility of the in-words provenance signal cannot be tuned away
  by a hostile or careless tint - which is what keeps premise 2 above true for
  an arbitrary consumer colour.
- **The kernel stores no colour.** `wpass-q5p` removed `ScannableCard.color`,
  `ScannableColor` and the storage column; no `Document` arm has ever carried
  one. They stay removed. Which colour an item carries is consumer state
  (walt-android's `WalletColorRepository`, keyed per wallet entry), so the
  kernel never learns why a colour was chosen and has nothing to leak in
  Threats 7 / 8.

Both surfaces decide what counts as a tint the same way, and share one
predicate to keep it that way: `passes-ui-core`'s `faceIsTinted` gates on
`isSpecified && alpha > 0f`, so a fully transparent tint falls back to the
documented untinted default on either arm rather than painting nothing (and, on
the scannable arm, deriving ink from luminance 0). The two landed a day apart
with independent gates and diverged on exactly that case; `wpass-80y.5` fixed
the scannable side and hoisted the predicate so it cannot recur. The predicate
is pinned by `FaceTintTest` in `passes-ui-core`; the scannable arm additionally
pins that its face and ink resolve through it
(`fullyTransparentTintFallsBackToTheDefaultFace`, via the injectable
`facePaint`), while the document arm's frame is a `Modifier.background` whose
branch no unit-level assertion can observe
(`…FallsBackToTheDefaultFrame` checks only that the surface composes intact).
The predicate decides legibility, not provenance: neither surface can lose its
trust caption either way.

**Bound of this row.** What is granted is a consumer-chosen face tint on the
two detail surfaces named above, plus the consumer's freedom to colour its own
list cards. What is not granted, and would be an amendment rather than a PR:
deriving a colour from signature status, verification outcome, or issuer
identity anywhere in the kernel; re-adding a colour field to a kernel artifact
model or storage table; or a `faceTint` reaching the code panel or the page
render. The document-side mirror of this row is ADR 0005 D1.C, which makes the
same argument for the PDF / image tower and withdraws its neutral-surface rule.
The `ScannableCardRowTile` accent question this row was the precondition for is
closed: `wpass-80y.4` found the tile had no consumer left and deleted it rather
than deciding whether its leading strip could take an item's tint. See the
withdrawn wallet-row concession above.

**C2 — host "Pass type" row concession (detail surface).** A consumer (Walt,
`wlt-3cer`) consolidates the provenance signal into a single "Pass type" row
inside its own host-rendered details section — values *Image / Scanned / Pkpass
/ PDF / "Image, Scanned"* across artifact classes — rather than carrying it as
the kernel's bottom-docked `ScannableCardTrustCaption`. The kernel grants this
through `ScannableCardScreen(trustCaption = TrustCaptionPlacement.HostedTypeRow)`
(`wpass-gv6`): under that mode the kernel renders **no** trust caption on the
detail surface, and the host carries the claim with its own type label.

This is a deliberate **weakening of the detail-surface mitigation**, and it is
recorded as such. Two things this concession explicitly blesses that the
docked-caption contract forbade:

1. **Neutral-type-label substitution.** A "Pass type: Scanned" row *is* an
   accepted carrier of the provenance claim under this mode. It is a weaker
   signal than the verbatim "Created by you" sentence: it names the artifact
   class rather than stating, in words, "you made this and Walt did not verify
   it." The consumer accepts that trade to keep one consistent provenance/type
   row across all artifact classes instead of a class-specific caption.
2. **Collapsible, not-always-visible placement.** The "Pass type" row may sit
   inside a collapsed-by-default details foldout. A user who never expands the
   foldout does not see the provenance signal on the detail surface at all.

**Why this is bounded rather than an open hole.** The load-bearing mitigation for
Threat 1 (visual conflation with a verified PKPASS) is C1 + C2 *combined*, and
C1 is untouched: the wallet **list** still distinguishes the artifact class
structurally, and no list card of any class carries a signature or verified
affordance — so there is no verified visual at list scale for a user-created
card to imitate (premise 1 of the "colour carries no trust meaning" row; the
class is named by the card's type tile, and its tint carries nothing). The
detail surface is reached only *after* the user has
already seen that list-level distinction, and on a card they themselves created
and can delete. The "Pass type" row, even collapsed, is a labelled, discoverable,
consistent location for provenance. And Walt remains a display device, not an
issuer — the POS / recipient is the authority for whether an artifact is
credit-worthy (Threat 9). The residual risk is that a user who relies solely on
the detail surface, never expands the foldout, and ignores the list-level
distinction loses the in-words provenance reminder; the consumer judges that
acceptable for a user viewing their own self-created card.

**Bound of the concession.** `HostedTypeRow` is permitted strictly for a host
that (a) renders a "Pass type" row enumerating the artifact class on its detail
surface, and (b) preserves the C1 list-level distinctions. The kernel cannot
verify either at runtime — the same structural limit that applied to the
withdrawn wallet-row concession's condition 3 — so the obligation shifts to the
consumer and is
pinned consumer-side by a walt-android test that the details section renders a
"Pass type" row (the pin moved from the earlier "host renders the kernel
caption"). `Docked` remains the default and the recommended surface for hosts
that do not own a details section. A future consumer wanting to drop **both** the
detail-surface caption **and** the C1 list-level distinction is amending this
row, not filing a PR. There is still no `showCaption: Boolean`: the placement is
the audited `Docked | HostedTypeRow` choice, pinned by
`scannableCardScreenTrustCaptionParamIsThePlacementType`.

**C3. Input hygiene at the create boundary.** `passes-core` validates
`ScannableCardCreateInput` for: per-format length caps (Code128 ~80 chars,
EAN-13 / UPC-A fixed-length with checksum, QR per-version cap with a
conservative ~2000-char ceiling, PDF417 ~800 chars, Aztec ~1500 chars),
per-format charset rules (EAN-13 / UPC-A numeric only, Code39 limited
alphanumeric, Code128 the printable ASCII subset; QR, PDF417 and Aztec are
byte-capable and admit any character), and Unicode Cf (Format) / Cc (Control)
codepoint rejection in both `payload` and `label`. The Cf/Cc rejection runs
before the per-format rules, so it covers the byte-capable formats too, and
mirrors the discipline that `FieldLinkScanner.kt:67` and the PDF document-label
path already enforce.

The PDF417 and Aztec caps (`wpass-pl7.1`) were set to hold at any plausible
error-correction level, because the level itself was not pinned until the writer
arms landed. `wpass-pl7.6` pinned PDF417 at error-correction level 3 and Aztec
at 33%, and re-derived both caps against those pins by binary-searching the
largest payload each writer accepts: **for single-byte payloads** PDF417 holds
1,766 characters against the 800 the validator allows, and Aztec 3,000 against
1,500. Re-derive if either pin rises.

**The caps do not, however, guarantee encodability, and this section previously
overstated that they did.** They count characters while writer capacity is
consumed in bytes. Measured over three-byte characters the same writers hold far less
than their caps allow, so a 700-character CJK payload clears the validator and
cannot be rendered. No exact predicate is available at the validator: each writer
picks a compaction mode per run, so capacity swings with the payload's
composition, and a byte ceiling tight enough to be safe would reject ordinary
accented text well inside the character cap. The per-width measurements live on
the caps themselves in `ScannableFormatConstraints`, which is where they would be
re-derived.

What is in place instead is attribution rather than prevention: the encoder lifts
both writers' over-capacity errors to `EncoderFailureReason.PayloadTooDense`, so
the failure is typed and actionable ("shorten this") rather than opaque. That arm
only reaches a user if something runs the encoder before persisting, and nothing
currently does — `ScannableCardCreateResult.EncoderFailure` exists and is consumed
but never produced. Tracked as `wpass-1kg`; until it lands, an oversized multibyte
payload saves and renders as the accessible-but-empty placeholder.

The same bead closed the charset half of that property, which the length caps do
not cover. Both new writers default to ISO-8859-1 while the validator admits any
visible character on a byte-capable format, so a payload the user can legitimately
type was unrenderable: `PDF417Writer` threw outright, and `AztecWriter` did worse
— it encoded and decoded back transliterated, a silent corruption. Pinned
`PDF417_AUTO_ECI` and `CHARACTER_SET` respectively; both measured to leave the
all-ASCII case byte-identical in matrix size and capacity. QR had the same
silent-transliteration defect (it predates these writers) and was closed
separately by `wpass-qj6` with the same `CHARACTER_SET` pin. QR's pin is not free
the way Aztec's was: `QRCodeWriter` prepends a UTF-8 ECI header to every
byte-mode symbol, so ASCII byte-mode symbols keep their matrix size but change
bit pattern, the v40-M byte-mode ceiling drops one byte (2,331 to 2,330, tracked
in `ScannableFormatConstraints`), and every stored non-ASCII QR card re-renders
as a different — now correctly decoding — symbol.

**C4. Create-time URI-scheme preview for the byte-capable symbologies.** When the
user creates a ScannableCard in a format that can carry an actionable payload,
`passes-core`'s URI-scheme classifier inspects the payload against a conservative
allowlist (`http`, `https`, `tel`, `sms`, `mailto`, `geo`, `wifi`, `bitcoin`,
`ethereum`, `magnet`, `market`, `intent`). A match (or a "looks URI-shaped but
unrecognized scheme" fallback) raises the confirmation dialog before the row is
persisted. The user must explicitly confirm "yes, this code is meant to encode an
actionable URI." Pattern source: `B3UrlConfirmSheet` in
`passes-ui/src/main/kotlin/.../SecuritySheets.kt`.

**C4 forward obligation — DISCHARGED (`wpass-pl7.6`).** PDF417 and Aztec are
byte-capable and carry the same actionable URIs, so the gate had to widen to them
when their writers landed. It was not a gap before that, because
`ScannableCardInputValidator` refused both formats outright; the moment they became
creatable it would have been one, and nothing in the compiler would have said so —
`requiresCreateConfirmation()` was `QrPayloadKind`-scoped and took no format
argument.

The fix makes the format part of the signature:
`QrPayloadKind.requiresCreateConfirmation(format)` now consults
`ScannableFormat.canCarryActionablePayload()`, an exhaustive `when` in `passes-core`.
Two properties follow. A future roster member is a compile error at one kernel site
rather than a gate that quietly stops covering a format. And the kernel now owns a
predicate for which symbologies need confirming, which is where the trust claim
requires that decision to live.

**The consumer's copy is not yet retired.** walt-android still carries an identical
`canCarryAutoActingPayload()` in `feature/passes/common/AutoActingSymbologies.kt`,
and it is the copy actually in the path — called ahead of the kernel predicate at
both create-time call sites. Until it is deleted (`wpass-j6b`) the two can drift,
and a correction made here would not take effect on its own. The discharge above is
therefore complete on the kernel side only.

Widening the trigger also made the sheet's own copy wrong: every arm read "this QR
will…". The strings now say "this code", since an Aztec boarding pass raising a
dialog about a QR is a trust surface that describes something other than what the
user is about to save.

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

A bounded concession once permitted a homogeneous wallet-row register
(`ScannableCardRowTile`), shifting the trust caption from list-row to detail
surface. It is withdrawn (`wpass-80y.4`) along with the composable itself — see
the C1 / C2 concession subsection above, which records the conditions it ran
under and what reintroducing such a register would have to re-argue.

A deeper consumer-side concession (`HostedTypeRow`, `wpass-gv6`) lets a
host drop the detail-surface caption entirely and carry provenance with its own
"Pass type" row — a neutral type label, possibly inside a collapsed foldout.
Under that mode the detail-surface arm of this mitigation is reduced to the host
type row, and the load shifts almost entirely onto C1's list-level distinction
(the user has already seen the artifact class on the list before reaching the
detail surface, on a card they created themselves). This is a real reduction in
defense-in-depth, accepted deliberately; full conditions, rationale, and residual
risk are in the C2 "Pass type" row concession subsection above.

**Status.** Mitigated structurally, with the detail-surface layer reducible to a
host "Pass type" row under the bounded `HostedTypeRow` concession (C1 list-level
distinction then carries the load). Under the `wlt-38v8` colour system the
list-level distinction is carried per the "colour carries no trust meaning" row
above: a class tint that any user can reassign, a class eyebrow, and no verified
affordance on any card of any class - colour distinguishes nothing on its own
and is not relied on to. This is the load-bearing concern of the entire epic;
every downstream child must trace back to this row.

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
"this code is meant to encode an actionable URI" before the row is persisted.
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
(`ScannableCardScreen`) also renders the payload as a human-readable readback
on its card face, below the code panel (GH #102 — fallback for when a
point-of-sale scanner cannot read the code). The same bidi/control-char attack
applies to both displays.

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
QR ~2000 chars, PDF417 800 chars, Aztec 1500 chars. Every ceiling sits well
below its format max but well above any realistic library / loyalty / URL
payload — for the two 2D additions the sizing case is a boarding pass, whose
IATA BCBP string runs ~60 characters per leg.

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

**Widened by `wpass-pl7.1`.** The reachable decoder set grew from five
symbologies to seven: `AztecReader` and `PDF417Reader`, with their own
detector and error-correction code, are now invoked on both decode paths.
Advisories against either are in scope for the same 30-day cadence.

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

### 10. Color picker as injection vector - Out of scope (consumer-side)

**What.** Hostile inputs to a color picker have historically been a vector in
browser CSS / SVG parsers (named-color injection, `var(--…)` escapes). A
naive "type a hex code" picker could allow inputs that round-trip as something
unexpected.

**Mitigation.** The kernel no longer exposes a per-card user colour at all
(`wpass-q5p` removed `ScannableCard.color` and `ScannableColor`). With no
colour field on the artifact, the kernel has no colour parsing or storage
surface to attack.

**Updated by wpass-80y.** A colour picker exists again, but consumer-side: Walt
persists per-item overrides in its own `WalletColorRepository` and passes the
result to `ScannableCardScreen(faceTint)` / `DocumentView(faceTint)` as an
already-constructed Compose `Color`. The kernel parses no colour string, stores
no colour, and validates none - a `Color` is an opaque packed value with no
parse step to attack, and nothing the kernel does with it (paint the face,
derive ink from its luminance) branches on its value beyond contrast. Picker
input hygiene is the consumer's, filed under `wlt-38v8`; a hostile value can at
worst produce an ugly card, since ink contrast is derived and pinned.

**Status.** No parsing or storage surface at the kernel; picker and persistence
are consumer-side.

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
- **The symbology allowlist bounds both sub-threats.** `DECODE_HINTS` pins
  `POSSIBLE_FORMATS` to exactly the `ScannableFormat` roster, so a hostile image
  can only reach the parsers for symbologies Walt actually renders, and the
  reader has correspondingly fewer candidate interpretations to confuse. The
  allowlist is the reason both risks scale with roster size rather than with
  ZXing's full format list.

**Roster growth is a deliberate re-weighting of this threat (`wpass-pl7.1`).**
Adding PDF417 and Aztec widens the reachable parser surface by two symbologies
and gives the reader two more candidate interpretations of an ambiguous region.
Accepted because the alternative was worse: without them an imported
boarding-pass screenshot — the single most-reported import — decodes to nothing
at any input scale, and the user gets no scannable code at all. DataMatrix stays
out for exactly this reason, having no reported need to pay for. The misread
half of the risk is bounded as above: decode is not the trust boundary, the
consumer's `confirmBarcode(payload, format)` gate is, and it is
format-agnostic — it fires for every decoded symbology, including the two new
ones, and preserves the decoded format verbatim rather than normalising it.

**The scale ladder narrows the decode-cost surface it widens (`wpass-pl7.2`).**
Roster growth alone did not make the reported screenshot decode: ZXing refuses
it at the resolution the picker delivers and reads it immediately once the image
is area-averaged down, so `decodeLuminance` now tries several scales of one
image instead of one. More attempts per image is more CPU per hostile image, and
the budget it spends is the same one `DecodeWatchdog` kills the sandbox over — so
the ladder is bounded at both ends. Every rung caps the longest side, *including
the largest*, which is the substantive change: decode cost stops scaling with a
hostile image's area, and the 50 MP canvas the header caps still admit is now
decoded at roughly 5 MP — measurably cheaper than the single uncapped attempt it
replaces. A wall-clock budget (`DecodeLadder.budget`, a fraction of
`decodeTimeoutMs` and checked against it at construction) drops the remaining
rungs on a device slower than the one the caps were measured on, so the failure
mode under load is "no barcode found" rather than a killed process. The live
path keeps its single attempt per frame: its retry is the next frame.

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
| `B3UrlConfirmSheet`                             | Create-time URI-scheme preview on the byte-capable formats (C4); same dialog pattern                |
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
- **No colour-derived trust signal anywhere in the kernel.** No surface may pick
  or vary a colour from signature status, verification outcome, or issuer
  identity, and no kernel artifact model or storage table may carry a colour
  field. Colour enters the kernel only as a consumer-supplied `faceTint` on the
  two detail surfaces, and nothing may be inferred from it. Re-opens the
  "colour carries no trust meaning" row.
- **No server-side validation of payloads against merchant APIs.** Re-opens
  row 9 and adds an entirely new key-custody / network surface.
- **No TOTP / HMAC-OATH / rotating-secret support.** Re-opens row 11.
- **No ZXing decoder symbols in the dependency closure.** Re-opens row 6.
- **No payload-bytes telemetry.** The `ScannableCardTelemetryGuard` may
  surface format counts and create/delete event counts; payload contents and
  payload length distributions are PII and never leave the device.
- **No unbounded bypass of the "Created by you" provenance signal on the detail
  surface** (`ScannableCardScreen`), through theming, layout, or
  consumer-supplied composables. One bounded concession exists, recorded above
  and nowhere else (the list-row register that was the second was withdrawn
  with `ScannableCardRowTile` in `wpass-80y.4`):
  `TrustCaptionPlacement.HostedTypeRow` (`wpass-gv6`) lets a host drop the
  detail-surface caption and carry provenance with its own "Pass type" row (a
  neutral type label, possibly collapsed) under the C2 "Pass type" row
  concession, with C1's list-level distinction carrying the load. Outside those
  two concessions C2 forbids any bypass: there is no `showCaption: Boolean`, and
  a host that drops the detail-surface caption without *both* a "Pass type" row
  *and* the C1 list-level distinction is amending the C2 concession, not filing
  a PR.

## How each control is tested

Each downstream child of the epic carries the tests pinning its slice of the
above controls. The mapping is recorded in the children's acceptance criteria;
the matrix below is the at-a-glance summary so a reviewer of any single child
can trace back here.

| Control | Pinned by                                  |
|---------|--------------------------------------------|
| C1      | `wpass-lzi.2` (data model surface test), `wpass-lzi.6` (separate table assertion), `wpass-lzi.8` (separate-lane composable test) |
| C2      | `wpass-lzi.8` (non-suppressible caption test, ≥2-distinct-elements snapshot); `wpass-pnb`'s wallet-row pins (`scannableCardRowTileHasExactlyFourUserVisibleParameters`, `rowTileDoesNotRenderTrustCaption`, `rowTileRendersFormatSubtitle`) were removed with the concession and the composable in `wpass-80y.4`; `wpass-gv6` adds `scannableCardScreenHasExactlyFiveUserVisibleParameters` (four at the time; `wpass-80y.1`'s `faceTint` bumped it) + `scannableCardScreenTrustCaptionParamIsThePlacementType` (placement is the audited carrier-of-provenance choice, not a Boolean) and `fullScreenHostedTypeRowOmitsKernelCaption` / `hostedTypeRowStillRendersBarcodeAndPayloadCaption` to pin the "Pass type" row concession; the consumer-side pin (Walt details section renders a "Pass type" row) lives in walt-android `wlt-3cer`; `wpass-80y` pins the colour-is-not-trust row with `ScannableCardTrustSurfaceTest.faceTintDoesNotSuppressBarcodeLabelPayloadOrTrustCaption` (+ its dark-tint twin), `codePanelIsLiterallyWhiteNotAThemeTokenOrTheFaceTint`, `inkOnClearsWcagAaAgainstEveryTintIncludingTheWorstCase`, and `DocumentFaceTintTest.faceTintDoesNotSuppressTheTrustCaptionOnEitherArm` / `faceTintLeavesThePageRenderRequestUnchanged`; `wpass-80y.5` pins the shared tint gate with `passes-ui-core`'s `FaceTintTest` plus `fullyTransparentTintFallsBackToTheDefaultFace` (scannable face and ink resolution, via `facePaint`) and `…DefaultFrame` (document arm composes intact) |
| C3      | `wpass-lzi.4` (length caps, charset, Cf/Cc rejection unit tests); `wpass-pl7.6` re-derives the PDF417 / Aztec caps against the pinned error-correction levels (`theTwoDimensionalFormatsEncodeAtTheirValidatorCap`, `theTwoDimensionalFormatsRoundTripAtTheirValidatorCap`) and pins the charset fix (`nonAsciiPdf417PayloadSurvivesAutoCompaction`); `noFormatIsDecodeOnly` and `isCreatableAgreesWithWhatTheValidatorAccepts` keep the now-empty decode-only refusal wired for a future decode-first addition, plus the byte-capable-charset rules in `ScannableFormatConstraintsTest` |
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
