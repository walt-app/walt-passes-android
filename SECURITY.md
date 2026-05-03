# Security policy

## Reporting a vulnerability

If you think you have found a security issue in walt-passes, please email **cole@walt.is** instead of opening a public GitHub issue.

Helpful things to include:

- A description of the issue and its impact.
- Steps to reproduce, ideally with a minimal `.pkpass` or code sample.
- Affected version (commit hash or release tag).
- Whether you would like to be credited if a fix ships.

## Scope

Walt-passes is the open-source pass-handling kernel that ships inside the [Walt](https://walt.is) Android wallet. The most useful reports target one of:

- The PKPASS parser (`passes-core`): ZIP, JSON, image, signature, manifest verification, `.strings` parsing, locale handling.
- Pass data storage (`passes-storage`): encryption-at-rest, key management, deletion, backup exclusion.
- Security-relevant UI flows (`passes-ui`): URL confirmation sheet, HTML subset renderer, bounded image rendering.

Out of scope:

- Issues in the closed-source Walt Android app outside `core/data-passes` and `feature/passes`.
- Issues that require a rooted, unlocked-bootloader, or otherwise compromised device.
- Issues in third-party PKPASS files (issuer-side mistakes).

## Security-relevant design decisions

A short list of intentional choices a reviewer might want to know up front. If the code does something different, that is a bug worth reporting.

- **Signature policy is lenient with provenance display.** PKCS#7 signatures and `manifest.json` hashes are verified mathematically; tampered passes are rejected. The Apple WWDR certificate chain is validated for *display*, not for gating: self-signed and non-Apple-issuer passes are allowed to import, and the UI surfaces the verification result. The load-bearing security control for malicious passes is the parser hardening below, not the signature check — a valid signature does not imply the pass content is safe to render or store. Lives in `passes-core`.
- **Parser is hardened against malicious input.** ZIP extraction is in-memory with bounded archive size, entry count, and per-entry size; entry names are canonicalized and zip-slip is rejected; the archive's allowed file types are limited to `.json`, `.png`, `.strings`. JSON parsing is strict (no polymorphic open types, no reflection, recursion-depth bound). Image dimensions are validated from the PNG header before decoding. Lives in `passes-core`.
- **`nfc` fields in a pass are parsed-and-ignored.** Importing a `.pkpass` cannot cause Walt to register a payment AID. The field is read for structural validity only; no code path exists to act on it. Deliberate, to avoid HCE conflict with Walt's payment feature.
- **`webServiceURL` is parsed-and-ignored.** Passes never trigger network calls from this library. No OTA pass updates, no push provisioning, no telemetry to issuers. `passes-core` and `passes-storage` make no network calls at all.
- **Back-field URLs go through a visible confirmation sheet.** Tapping a link or actionable value in a pass's back fields shows the full destination (URL, phone number, email) before any external app is opened. URL scheme allowlist: `http`, `https`, `mailto`, `tel`. External `http`/`https` URLs open in Custom Tabs. Lives in `passes-ui`.
- **No WebView, anywhere.** HTML in back fields is rendered through an explicit subset (`<b>`, `<i>`, `<br>`, `<a>`), never via a WebView. No JavaScript, no CSS, no external image loading.
- **Pass data is excluded from Android Auto Backup.** The passes database is configured out of cloud backup and device-to-device transfer. Lives in `passes-storage` manifest rules.
- **Deletion is irreversible.** No trash bin, no undo. Cached decoded data is wiped at delete time.
- **Storage is encrypted with a hardware-backed key.** SQLCipher database; the key is wrapped by Android Keystore, with StrongBox preferred when available.
- **No pass content in logs or telemetry.** The `TelemetryGuard` interface in `passes-core` accepts only enum events and bounded numeric/categorical fields — never user-content strings. Enforced by API shape, not reviewer discipline.
- **walt-android consumes this code directly.** The closed-source Walt app does not parallel-implement any trust-claim-bearing logic. What you audit here is what ships on the device.

## Supported versions

Walt-passes is pre-alpha. Until a v1.0 release, only the latest commit on `main` is supported.
