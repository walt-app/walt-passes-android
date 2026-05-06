# Walt Passes (Android)

> The open-sourced pass-handling implementation that ships in the [Walt](https://walt.is) Android wallet app.

## Why this repository exists

Walt is a privacy-focused NFC tap-to-pay wallet. Its core promise is that user data stays on the device. As Walt adds support for boarding passes, event tickets, loyalty cards, and other pass types (mirroring Apple Wallet and Google Wallet's "pass" concept), users have a reasonable question: *what does Walt actually do with my pass data?*

This repository answers that question by being the auditable source of truth for Walt's pass handling. Walt's main app is closed source. The pass-handling code is not. Every security-and-privacy claim Walt makes about pass handling is implemented in code that lives here.

**This repository exists for transparency, not for library reuse.** Reuse is welcome as a side effect. The primary commitment is the audit trail.

## Status

Pre-alpha. Architecture and design phase. No releases yet.

## Trust claim audit map

| Walt claim | Where to look |
|---|---|
| PKPASS parser is hardened against malicious input | `passes-core` — `PassParser`, `ParserConfig`, ZIP/JSON/PNG hardening |
| Signature verification rejects tampering; provenance is shown to the user | `passes-core` — `SignatureVerifier`, `SignatureStatus` |
| Pass blob storage is encrypted at rest with hardware-backed keys | `passes-storage` — SQLCipher integration, Keystore key provider |
| Pass data is excluded from Android Auto Backup | `passes-storage` — backup rules / manifest config |
| Pass content never appears in logs or telemetry | `passes-core` — `TelemetryGuard` interface; structurally PII-forbidden by API shape |
| Back-field URLs go through a visible-confirmation flow before opening | `passes-ui` — `UrlConfirmationSheet` composable |
| Pass deletion is irreversible; caches are wiped | `passes-storage` — deletion logic |
| Expired passes show an "Expired" badge but are not auto-deleted | `passes-ui` + `passes-storage` |
| PDF document import is contained in an isolated renderer process; bytes are never extracted, parsed for fields, or shared back out | [`docs/PDF_THREAT_MODEL.md`](docs/PDF_THREAT_MODEL.md); [`docs/adr/0005-pdf-document-support.md`](docs/adr/0005-pdf-document-support.md); `passes-pdf` (forthcoming) |

(These point to *intended* locations; modules are not yet implemented.)

## Modules

- **`passes-core`** — Pure Kotlin/JVM. PKPASS parser, model, signature verifier, `.strings` parser, secure-defaults `ParserConfig`, `TelemetryGuard` interface. No Android dependencies. KMP-friendly.
- **`passes-storage`** — Android. SQLCipher database with Keystore-sourced key, Android Auto Backup exclusion, irreversible deletion with cache wipe.
- **`passes-ui`** — Android + Jetpack Compose. Pass front/back composables, barcode/QR rendering, B3 URL confirmation sheet, expired badge, bounded image rendering. Themable.

## Scope (v1)

**In:**
- All five PKPASS types: boarding pass, event ticket, coupon, store card, generic.
- PKPASS file import via OS-level intent filter and in-app file picker.
- Front + back fields displayed; barcode/QR rendering; full localization (all locales retained, re-render on locale change); expired badge; visible-URL confirmation for back-field actionables.
- Encrypted local storage; irreversible deletion.
- PDF document import (e.g., concert tickets distributed as PDF) as a sibling concept to PKPASS, in a separate "Documents" lane. Rendering happens in an isolated process; PDF bytes are never extracted, parsed for fields, or shared back out. See [`docs/PDF_THREAT_MODEL.md`](docs/PDF_THREAT_MODEL.md) and [`docs/adr/0005-pdf-document-support.md`](docs/adr/0005-pdf-document-support.md).

**Out:**
- NFC / transit passes (HCE conflict with payment).
- Dynamic server-pushed updates (`webServiceURL`).
- Multi-device sync, cloud backup of pass data.
- Manual entry, QR-scan import (deferred to v2).
- Lock screen integration (deferred to v1.5).

## Security policy

See [`SECURITY.md`](SECURITY.md) for vulnerability disclosure.

## License

Apache 2.0. See `LICENSE`.
