# Brainstorming-phase decisions

Decisions made on 2026-05-03 while scoping Walt Passes (parent epic `wlt-0tn` in walt-android), plus the field survey that informed the signature policy. ADRs and code reference these by id (for example `decision-wlt-0tn-q4`).

These are the original v1 brainstorming outcomes. Where a later ADR in [`docs/adr/`](adr/) or the current module layout in `CLAUDE.md` differs, the later document wins.

## decision-wlt-0tn-q1: pass acquisition and signature policy

PKPASS file import is the primary acquisition path.

- **1a. Signature policy: lenient, with mathematical verification, provenance display and defense in depth (Position A).** Always verify the PKCS#7 signature mathematically and reject tampering (manifest hash mismatch). Validate the Apple WWDR certificate chain but do not gate import on it; surface the result in the UI as "Verified Apple issuer: X", "Self-signed" or "Cert chain incomplete". Self-signed and non-Apple-cert passes may be imported.
  - Rationale: Position A is already ahead of every comparable FOSS pass app (see the field survey below). Strict WWDR gating (Position B) was declined to avoid WWDR root distribution and rotation maintenance, friction with self-issuers, and a harder-to-use library for downstream consumers.
  - Parser hardening (size limits, zip-slip protection, schema-strict JSON, bounded image decoding, parse-but-ignore for `nfc` / `webServiceURL` / personalization) is the load-bearing security control regardless of signature policy.
- **1b.** Register an OS-level intent filter for the `application/vnd.apple.pkpass` MIME type and `.pkpass` extension in the wallet app manifest, so tapping a `.pkpass` anywhere offers import into Walt.
- **1c.** PKPASS NFC payload: parse-but-ignore in v1. Never register the AID; documented exclusion to avoid an HCE/AID conflict with payment.
- **1d.** Manual entry and QR-scan import deferred to v2. v1 ships PKPASS file import only.
- Blast radius if a parser bug were exploited for code execution in the app process: card cryptographic keys stay non-exportable in Keystore/StrongBox; transaction history exfiltration is the realistic worst case.

## decision-wlt-0tn-q2: naming

- **2a.** Feature umbrella: "Walt Passes".
- **2b.** Open-source repo: `walt-passes-android` under `github.com/walt-app` (provisioned as `walt-passes`, renamed the same day to add the platform suffix ahead of a `walt-passes-ios` sibling).
- **2c.** UI subtype labels per PKPASS type: `boardingPass` = "Boarding pass", `eventTicket` = "Ticket", `coupon` = "Coupon", `storeCard` = "Store card", `generic` = "Pass".
- Maven coordinates preview: group `is.walt`, artifacts `passes-core` and `passes-ui`.

## decision-wlt-0tn-q3: v1 scope

**In:** all five PKPASS types (`boardingPass`, `eventTicket`, `coupon`, `storeCard`, `generic`) via a generic field-driven renderer; front and back fields; barcode rendering (PDF417, QR, Aztec, Code128); colors, logo, strip, background and thumbnail images; full localization (all locales retained); expiry display with an "Expired" badge; encrypted local storage (SQLCipher + Keystore); `.pkpass` intent filter; back-field URLs, phone numbers and emails via Policy B3; secure deletion.

**Out:** NFC/transit, dynamic updates (`webServiceURL`), lock screen integration (deferred to v1.5), notifications, pass sharing, manual entry and QR-scan import (all deferred to v2), multi-device sync, cloud backup, personalization, wearables, pass editing, search/filter, pass export.

### decision-wlt-0tn-q3.2: back fields

Back fields are in for v1. Actionable content (URLs, phone numbers, emails) follows Policy B3: the URL or value is visible, and a confirmation sheet appears before any action.

- Render a limited HTML subset only: `b`, `i`, `br`, `a`.
- URL scheme allowlist: `http`, `https`, `mailto`, `tel`.
- External URLs open in Custom Tabs.
- Long URLs are middle-truncated in the label and shown in full in the confirmation sheet.
- No JavaScript, no CSS, no external image loads, no auto-fetch, no link preview.

### decision-wlt-0tn-q3.3: localization

Full localization, all locales kept. At display time pick the device locale, then English, then the first available. Re-render on device locale change. All `.lproj` directories stay on disk. Android system fonts cover non-Latin scripts; Compose handles RTL layout. A `.strings` parser is required.

### decision-wlt-0tn-q3.4: pass deletion

- No undo and no trash bin.
- Android Auto Backup excluded for the passes database.
- No PII in logs.
- Cached decoded data wiped on delete.
- Confirmation dialog matching the existing card-deletion pattern.

`VACUUM` after delete was judged unnecessary. SQLCipher, the non-exportable Keystore key, Android file-based encryption, the backup opt-out and app sandboxing together make freed-page bytes unreadable without defeating all of those layers. Flash wear-leveling means `VACUUM` could not guarantee bit-level erasure anyway; the encryption layers are the load-bearing control.

## decision-wlt-0tn-q4: repo location, license, distribution

- **4a.** Separate repository at `github.com/walt-app/walt-passes-android`, not a monorepo subdirectory.
- **4b.** License: Apache 2.0. It is the norm for Android libraries (AndroidX, Hilt, OkHttp), allows reuse without imposing copyleft on consumers, and Walt's privacy and security promises live in the code rather than the license. The GPLv3 precedent among FOSS pass apps applies to apps, not libraries.
- **4c.** Distribution: composite Gradle build for v1 (`includeBuild("../walt-passes-android")` from a sibling checkout), since iteration speed during v1 API churn mattered more than published artifacts. Planned move to GitHub Packages (preferred over Maven Central) once v1 stabilizes. See [ADR 0004](adr/0004-distribution-and-github-packages.md).
- **4d.** Repo bootstrap (CI, README, code of conduct, contributing guide, security policy) deferred to the architecture phase.

## decision-wlt-0tn-q5: library shape

Walt's open pass-handling kernel. The original v1 plan had three modules:

1. `passes-core`: pure Kotlin/JVM; parser, model, signature verification, `.strings` parser, `TelemetryGuard` interface.
2. `passes-storage`: Android-only; SQLCipher with a Keystore key, backup exclusion, deletion and cache wipe.
3. `passes-ui`: Android + Compose; pass front/back composables, barcodes, the B3 URL confirmation sheet, expired badge, bounded image decoding, theming contract.

The wallet app wraps these thinly (dependency wiring, themed screens, the manifest intent filter).

**Decisive constraint:** the wallet app uses these implementations directly and never reimplements any code that bears a trust claim. The wallet app is closed source, so trust through transparency requires every security and privacy claim to be auditable here. The README leads with transparency-for-trust, not library-for-reuse.

## FOSS PKPASS signature-policy field survey (2026-05-03)

Surveyed PassAndroid (Feb 2025), FossWallet (Apr 2026), Catima and Google's Pass Converter.

- None performs PKCS#7 signature verification, verifies manifest hashes, validates the WWDR chain, or surfaces provenance in the UI.
- FossWallet has an unimplemented `TODO check signature before returning` in `persistence/loader/PassLoader.kt`.
- PassAndroid extracts with zip4j `extractAll()` in `UnzipPassController.kt` without validation.
- Hardening is thin across the field: no size limits, no image dimension caps, and FossWallet has no zip-slip canonical-path check.

Implication: even the lenient policy (mathematical verification + provenance display + parser hardening) puts Walt ahead of every comparable FOSS pass app. Strict WWDR gating would have no FOSS prior art for chain plumbing, root distribution or rotation handling.
