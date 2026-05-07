# ADR 0006: rotating-credential ticket scope

- Status: Accepted
- Date: 2026-05-07
- Tracks: `wpass-27m` (explain-and-link UX at import), `wpass-4vc` (Walt-native rotating-barcode extension architecture exploration)
- Decision context: `decision-wlt-0tn-q1` (PKPASS file import as primary path), `decision-wlt-0tn-q3` (v1 explicitly OUTs `webServiceURL` polling, dynamic updates, manual entry, QR scan import); empirical evidence in `docs/research/rotating-credential-tickets.md`.

## Context

Ticketmaster SafeTix and analogous rotating-credential ticket flows
have become the dominant distribution path for major-venue tickets in
the markets Walt targets. The user-visible surface in the Ticketmaster
app is a rotating QR (or PDF417) accompanied by an "Add to Google
Wallet" button; no PDF or PKPASS export is offered for SafeTix events,
and Apple Wallet's iOS path uses a companion-app pattern that is not
applicable on Android.

The empirical research recorded in
`docs/research/rotating-credential-tickets.md` establishes that:

- The rotating barcode is generated locally from TOTP secrets that
  Ticketmaster provisions to either Google Wallet (via `object:insert`
  on the Wallet API) or to its own iOS app (via the companion-app
  pattern). The save-to-Wallet JWT carries an object reference, not
  the secrets.
- The Ticketmaster ticket Activity sets `FLAG_SECURE`, which blocks
  screen capture, system screenshots, recents thumbnails, and
  accessibility-service bitmap retrieval at the OS level.
- The Save-to-Google-Wallet flow uses an explicit `PayClient` SDK
  intent or an auto-verified Google App Link; neither is interceptable
  by a third-party Android wallet.
- PKPASS `webServiceURL` polling is rate-capped at approximately 20
  pushes per pass per day on Apple Wallet and 3 per day on Google
  Wallet, two orders of magnitude below the cadence a 15-second TOTP
  rotation would require.
- Smart Tap NFC at the venue gate requires merchant-provisioned ECDH
  keys held by Google; no third-party wallet can participate without
  Google merchant onboarding the venue does not own.
- Reverse-engineered SafeTix renderers exist (TicketGimp, Amosa,
  Secure.Tickets) but require DevTools secret extraction against
  Ticketmaster's authenticated session, in violation of Ticketmaster's
  Terms of Use and plausibly of DMCA Section 1201, and are the subject
  of active litigation.
- No FOSS or commercial third-party wallet has solved this problem.
  Pass2U and WalletPasses implement Apple's poll-then-replace flow
  but cannot rotate barcodes; PassAndroid, FossWallet, and Catima
  hold static codes only.
- The standards-track horizon (W3C VC 2.0 Recommendation 2025-05-15;
  EUDI Wallet member-state mandate 2026-09; Lufthansa+Amadeus EUDI
  pilot 2025) demonstrates that ticketing under verifiable credentials
  is technically feasible, but no major ticketing platform has
  announced participation. No current regulatory lever (DMA, eIDAS
  2.0, GDPR Article 20, US v. Live Nation) compels Ticketmaster to
  release ticket payloads to third-party wallets.

This ADR codifies Walt's posture toward this category. The posture
serves two purposes: it preserves the v1 trust claim by refusing to
ship rotating-credential rendering Walt cannot honestly back, and it
makes explicit which paths are excluded so that a future contributor
does not silently re-introduce them.

## Decisions

### D1. Rotating-credential tickets remain out of scope for v1

Walt's v1 import surface is PKPASS file import (per
`decision-wlt-0tn-q1`) and PDF document import (per ADR 0005).
Rotating-credential tickets, regardless of issuer, are out of scope.
This is consistent with the existing v1 OUT list in
`decision-wlt-0tn-q3`, which already excludes `webServiceURL`-driven
dynamic updates, manual entry, and QR scan import.

Walt does not register a Smart Tap HCE service. Walt does not poll
`webServiceURL` at any cadence. Walt does not implement any
reverse-engineered SafeTix rendering. Walt does not attempt to
intercept Save-to-Google-Wallet intents or decode the resulting JWTs
for rotation secrets that the JWTs do not carry.

Rationale: every one of the closed paths catalogued in the research
document either fails technically (`FLAG_SECURE`, explicit GMS
intents, Smart Tap merchant binding, APNs rate caps) or fails the
trust-claim posture (reverse engineering against Ticketmaster's
authenticated session). The v1 design ships nothing that depends on
any of them.

### D2. Import surfaces detect and explain rather than fail silently

When Walt's import surface receives an artifact that is recognizably a
rotating-credential ticket, it surfaces a typed UX message that
explains why Walt cannot hold it and offers a deep link to the
appropriate wallet (Google Wallet on Android). It does not present a
generic "import failed" error and does not attempt to render a static
fallback that would not work at the gate.

Detection signals at the import surface (which lives in walt-android's
ingestion layer; `passes-core` has no Android intent surface):

- A shared or pasted URL matching `pay.google.com/gp/v/save/*`.
- A shared or pasted URL matching `tickets.ticketmaster.*`,
  `am.ticketmaster.*`, `ticketmaster.dk/*`, or other Ticketmaster
  ticket paths in markets Walt supports.
- A `.pkpass` file whose `passTypeIdentifier` matches a known
  rotating-credential issuer (forward compatibility; no current
  matches).

The detection list is conservative by intent: false negatives degrade
to the existing "could not import" path; false positives would mislead
users and are worse. Issuer-specific match strings live alongside the
import surface and are tracked in `wpass-27m`.

Walt does not parse, decode, or otherwise inspect the contents of a
detected Save-to-Google-Wallet JWT beyond what is required to confirm
the URL pattern. The JWT is treated as opaque even though its payload
is signed-but-not-encrypted.

Rationale: silent failure is incompatible with the transparency-for-
trust posture. A user who attempts to add a SafeTix ticket to Walt and
receives a generic error has no way to distinguish "Walt is broken"
from "Walt deliberately does not hold this kind of ticket." The
explain-and-link UX makes the deliberate choice legible. The deep
link to Google Wallet preserves the user's ability to use the ticket
without converting Walt into a Google Wallet client.

### D3. Walt-native PKPASS rotating-barcode extension is the long-term seam, deferred

Because Walt is its own wallet and not Apple Wallet, it can interpret
a Walt-namespaced extension to PKPASS without violating the PKPASS
specification: Apple Wallet ignores unknown keys, and `userInfo` is
the spec-blessed app-specific data slot. A future cooperating issuer
could ship a PKPASS that carries both a static fallback `barcodes[]`
entry (for Apple Wallet, Google Wallet's PKPASS importer, and any
other wallet that does not understand the extension) and a
`userInfo.waltRotatingBarcode` block shaped after Google's
`RotatingBarcode` REST type.

Walt's renderer would compute TOTP locally on display; the signed
PKPASS manifest stays static, since the secret is signed, not the
rendered code. This sidesteps the `webServiceURL` rate caps, APNs
delivery semantics, and the companion-app entitlement entirely.

This is the long-term seam for cooperating issuers (smaller ticketing
platforms, festivals, season-ticket holders, conference issuers,
self-issuers). Incumbents will not adopt it. The trust-claim
alignment is direct: an audit-friendly public extension spec living
in this repository is exactly the format the transparency-for-trust
posture is set up to ship.

The extension is not implemented in v1 and is not committed to as a
deliverable here. It is tracked under `wpass-4vc` for v2 / v3
architecture discussion. If pursued, it requires its own threat
model addressing: TOTP secret at rest in SQLCipher, secret-touching
code paths in `passes-core`, secret-handling telemetry guard, and the
behavior of the existing `PKCS#7` manifest verification when a
rotating-barcode block is present in `userInfo`.

Rationale: refusing the closed paths in D1 leaves Walt with no answer
to issuers who would want to issue rotating tickets to Walt directly.
A documented extension shape, even deferred, is the answer to that
question. Promising it as a v1 deliverable would over-commit; saying
nothing about it would leave Walt's long-term posture toward rotating
tickets ambiguous.

### D4. OpenID4VCI / EUDI-Wallet-holder alignment is the standards-track direction, deferred

The standards-track path with regulatory weight is the EU Digital
Identity Wallet under eIDAS 2.0. The W3C Verifiable Credentials 2.0
Recommendation (2025-05-15) and OpenID for Verifiable Credential
Issuance (OpenID4VCI) are the format and protocol of record. The EWC
large-scale pilot has demonstrated travel and event credentials
including a Lufthansa + Amadeus boarding pass pilot in 2025. The
member-state delivery mandate is 2026-09.

When a ticketing issuer (incumbent or otherwise) begins emitting
ticket-shaped Electronic Attestations of Attributes, Walt-as-EUDI-
holder is the path that lets Walt accept those credentials without
issuer-specific code in the parser. Tracking is deferred until either
a concrete issuer signals intent or the September 2026 mandate
produces relying-party demand on the wallet side.

No bead is filed for this work yet. The horizon is one to two years.

Rationale: structural alignment with verifiable credentials is the
posture, not the build. Building EUDI-holder primitives ahead of
issuer demand would be premature; declaring no posture would leave a
gap in the trust narrative.

### D5. Explicitly excluded approaches

The following approaches are excluded by this ADR. Re-introducing any
of them is a security-policy and trust-claim change that requires an
ADR amendment.

| Excluded approach                                       | Reason for exclusion                                                                                                                                                                          |
|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Reverse-engineered SafeTix rendering                    | Trust-claim incompatible. Violates Ticketmaster ToU. Plausibly violates DMCA Section 1201. Subject of active patent litigation. Fails the audit standard "every trust-claim-bearing behavior is implemented in code that lives in this repository" because the secrets are extracted from another company's authenticated user session. |
| Save-to-Google-Wallet JWT interception or decoding for rotation secrets | Technically forecloses on the JWT itself (per Google's documented best practice it does not carry the secret). Interception is also not viable (explicit GMS intent on Android). |
| `FLAG_SECURE` bypass via accessibility, MediaProjection, or overlay tricks | OS-level block by design; circumvention attempts are detection-evading by nature and incompatible with the trust-claim posture even if they worked.                                  |
| Smart Tap HCE impersonation at venue gates              | Cryptographically infeasible without Google merchant onboarding. Attempt would require key-extraction or session-spoofing techniques that do not align with the trust-claim posture.        |
| `webServiceURL` polling at TOTP cadence                 | Two orders of magnitude over Apple's APNs cap and Google Wallet's daily cap; bandwidth-prohibitive due to per-rotation pass re-signing. Excluded already by `decision-wlt-0tn-q3`.            |
| Background screen capture of the Ticketmaster app or any other wallet | Blocked by `FLAG_SECURE`. Excluded for the same trust-posture reasons as the bypass row above. |

A test in `passes-core` and `passes-pdf-core` enforces the absence of
any symbol named `verifyRotatingBarcode`, `decodeSaveToWalletJwt`, or
similar would over-specify; the actual enforcement is in this ADR
plus code review against the catalogued shape.

## Consequences

- The v1 import surface is unchanged. PKPASS file import and PDF
  document import continue as the only acquisition paths.
- `wpass-27m` becomes the bead for landing the explain-and-link UX in
  walt-android's ingestion layer. The detection patterns in D2 are
  the contract; the UX shape is design work that lives downstream of
  this ADR.
- `wpass-4vc` becomes the bead for the long-term Walt-native
  rotating-barcode extension architecture exploration. It is
  explicitly not on the v1 critical path.
- Future PRs that add `webServiceURL` handling, JWT decoding for
  rotation secrets, accessibility-service usage that touches
  third-party wallet windows, or Smart Tap HCE registration require
  an ADR amendment. The catalogued exclusions in D5 are the test.
- The trust narrative gains an explicit negative-scope statement.
  Documenting what Walt does not do, and why, is part of the
  transparency-for-trust commitment; this ADR is the canonical
  reference for that statement on rotating-credential tickets.

## Open follow-ups

- `wpass-27m`: detection patterns and explain-and-link UX in
  walt-android's ingestion layer; design conversation deferred until
  import-flow polish gets attention.
- `wpass-4vc`: extension-spec design for the Walt-native rotating
  barcode extension. Includes an extension threat model not yet
  written.
- EUDI-holder posture: no bead yet. To be filed when concrete issuer
  signal or relying-party demand emerges, or when the 2026-09 mandate
  produces a downstream effect Walt should align with.
- Empirical revisit: this ADR is grounded in research as of
  2026-05-07. The standards-track and antitrust horizons (US v. Live
  Nation remedy, EUDI member-state delivery, any new EU Commission
  proceeding) are time-sensitive. A revisit is appropriate when any
  of those horizons moves materially.
