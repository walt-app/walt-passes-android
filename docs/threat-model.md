# Walt Passes Threat Model

**Status**: v1 architecture-phase draft
**Last updated**: 2026-05-03
**Scope**: walt-passes (passes-core, passes-storage, passes-ui), v1 release scope

This document is the threat model for the walt-passes pass-handling kernel. It is the auditable security artifact behind the trust claims in `README.md` and the security-and-privacy commitments in the consuming Walt wallet app. It is intentionally honest about residual risk: where mitigations are partial, this document says so.

If you find a discrepancy between this model and the code, the code is authoritative and the model is a bug. Please report via `SECURITY.md`.

---

## Table of contents

1. [Scope and out-of-scope](#1-scope-and-out-of-scope)
2. [Assets](#2-assets)
3. [Trust boundaries](#3-trust-boundaries)
4. [Adversary model](#4-adversary-model)
5. [Attack surface inventory](#5-attack-surface-inventory)
6. [Threats and controls](#6-threats-and-controls)
7. [Cross-cutting controls](#7-cross-cutting-controls)
8. [Supply-chain risk](#8-supply-chain-risk)
9. [Side channels and observability](#9-side-channels-and-observability)
10. [Residual risk and v2 candidates](#10-residual-risk-and-v2-candidates)
11. [Audit map](#11-audit-map)

---

## 1. Scope and out-of-scope

### In scope (v1)

- The three Gradle modules in this repository: `passes-core`, `passes-storage`, `passes-ui`.
- The PKPASS input format as parsed by `passes-core`.
- The on-device storage of pass blobs and decoded fields handled by `passes-storage`.
- The composables that render pass content and perform user-confirmed actions in `passes-ui`.
- The Walt Android process insofar as walt-passes runs inside it. Walt's process is a hostile environment for parser bugs because it also handles payment data.

### Out of scope (v1)

- The Walt Android application's own modules outside `core/data-passes`, `feature/passes`, and the manifest intent filter. Walt-android has its own threat model.
- Network protocols. v1 does not implement `webServiceURL`, OTA pass updates, push provisioning, or any pass-related network traffic. PKPASS field `webServiceURL` is parsed-and-ignored.
- NFC / HCE for passes. PKPASS field `nfc` is parsed-and-ignored. Walt-passes never registers an AID. (See decision `wlt-0tn-q1` for rationale; HCE conflict with payment is the load-bearing reason.)
- Manual entry and QR-scan import of passes (deferred to v2).
- Lock-screen integration, notifications, sharing, multi-device sync, cloud backup of pass data, personalization, wearables.
- Attacks against the user's lock screen, fingerprint sensor, or Android Verified Boot. Walt inherits the platform's protections; if those fail, Walt cannot independently defend.
- Physical attacks against a powered-off device with FBE enabled.

### Assumptions

- Android API 26+ (matches walt-android `minSdk`), File-Based Encryption enabled by the platform, hardware-backed Keystore available, StrongBox preferred when available.
- The user has set a screen lock. Walt-android already requires this for payments; walt-passes inherits that posture.
- Google Play Protect is not relied on as a security control, but its presence reduces some attack vectors (rogue apps, sideloaded malware).
- The attacker does not have root or Verified Boot bypass. With root, all on-device guarantees collapse.

---

## 2. Assets

| # | Asset | Location | Sensitivity | Why it matters |
|---|---|---|---|---|
| A1 | DPAN private keys (payment cards) | Android Keystore / StrongBox, non-exportable | Critical | Card cloning would result if exfiltrated. **Non-reachable from any RCE in Walt's process** because Keystore exposes only key handles, not raw key material. |
| A2 | Transaction history (payment) | walt-android local DB (SQLCipher + Keystore key) | High | Privacy core promise. Reachable in-memory if Walt's process is compromised, since SQLCipher needs the derived key in process memory to operate. |
| A3 | Pass blobs (PKPASS contents) | `passes-storage` SQLCipher DB | Medium-high | May contain boarding-pass names, ticket holder identity, loyalty IDs. Privacy-relevant but not financial. |
| A4 | Pass DB encryption key material | Wrapped at rest in Keystore; in-memory while DB is open | High | If exfiltrated alongside the encrypted blob, decrypts pass data. |
| A5 | User confirmations for outbound URLs | `passes-ui` URL confirmation sheet | Medium | If bypassed, user is silently routed to attacker-controlled URLs and inferences leak (referrer, IP, activity). |
| A6 | Walt-android source code | Closed-source binary on device | Low (already shipped) | Reverse-engineering possible from APK; closed source is not a security control. |
| A7 | walt-passes source code | This repository, public | None (transparency asset) | Public by design. The asset value is *integrity*: nobody silently slipping logic into a release that contradicts this threat model. |
| A8 | User attention / consent | Runtime, displayed UI | Medium | Phishing inside a pass UI (B3 confirmation bypass, deceptive labels) is a real concern. |

**Key insight:** The walt-passes parser runs in the same process as walt-android. A parser RCE has the blast radius of any RCE in that process. The pre-existing payment threat model already accepts that A2 is reachable on process compromise; A3 and A4 are added by adding passes. **A1 is *not* added to the reachable set** because Keystore non-exportability is hardware-enforced.

---

## 3. Trust boundaries

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Untrusted: external                          │
│  Attacker-controlled .pkpass file delivered via:                    │
│    - email attachment                                               │
│    - chat message                                                   │
│    - browser download                                               │
│    - file picker                                                    │
│    - share sheet                                                    │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ Android intent: application/vnd.apple.pkpass
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Walt Android process (medium trust to itself; hostile to inputs)   │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  passes-core (PURE KOTLIN/JVM, NO Android deps)               │  │
│  │   - ZIP extractor (size-bound, in-memory)                     │  │
│  │   - JSON deserializer (kotlinx.serialization, strict)         │  │
│  │   - PKCS#7 / X.509 parser (BouncyCastle)                      │  │
│  │   - .strings parser                                           │  │
│  │   - Domain model, no network                                  │  │
│  └────────────────┬──────────────────────────────────────────────┘  │
│                   │ ParseResult (sealed types)                      │
│                   ▼                                                 │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  passes-storage (Android)                                     │  │
│  │   - SQLCipher DB (key in Keystore)                            │  │
│  │   - Backup-excluded                                           │  │
│  │   - Irreversible delete + cache wipe                          │  │
│  └────────────────┬──────────────────────────────────────────────┘  │
│                   │                                                 │
│                   ▼                                                 │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  passes-ui (Android + Compose)                                │  │
│  │   - Bounded image decoding (ImageDecoder)                     │  │
│  │   - ZXing barcode rendering (encode-only path)                │  │
│  │   - B3 URL confirmation sheet                                 │  │
│  │   - "Expired" badge                                           │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Same process: payment subsystem                              │  │
│  │   - DPAN keys in Keystore (non-exportable)            HW edge │  │
│  │   - SQLCipher transaction DB (key in Keystore)                │  │
│  └───────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
            Android Keystore / StrongBox (hardware boundary)
            Cryptographic operations cross this boundary;
            raw private key material does not.
```

**Trust transitions:**

1. **External → process boundary** at the Android intent dispatch. Until parsing completes, every byte is attacker-controlled. This is the principal threat surface.
2. **passes-core → passes-storage**: a parsed `Pass` is treated as structurally valid but content-untrusted (text fields may still be hostile to the renderer).
3. **passes-storage → passes-ui**: rendering treats stored content as previously-validated structure but still applies content-level controls (HTML subset, scheme allowlist, bounded decoding).
4. **process → Keystore/StrongBox**: hardware boundary. RCE in the process **cannot** extract keys; it can only request operations that succeed when the device is unlocked.

---

## 4. Adversary model

### Adversaries we defend against

- **A-EXT (external pass author)**: crafts a malicious `.pkpass`. Goals: RCE in Walt, persistent storage corruption, exfiltration of A2 (transaction history) or A3 (other pass data) on subsequent compromise, phishing via deceptive pass content. This is the principal adversary.
- **A-NET (network-positioned)**: TLS-attacker delivering a malicious `.pkpass` over an unencrypted channel. Same primitives as A-EXT; Walt itself does not initiate pass downloads.
- **A-COL (colocated app)**: another installed Android app. Walt is sandboxed; cross-app reads are blocked by Android. A-COL can attempt: (a) intent spoofing for the pkpass MIME, (b) reading exported components, (c) clipboard / share-target abuse. Defenses come from manifest hygiene; not unique to walt-passes.
- **A-SUP (supply chain)**: compromise of an upstream dependency (BouncyCastle, ZXing, kotlinx.serialization, SQLCipher native, Compose). See §8.
- **A-USR (curious user with adb / debug build)**: the user themselves running `adb pull` against their own device. We do not defend against this; the user owns their data. Documented for completeness.

### Adversaries we do **not** defend against

- **Root attacker**, **bootloader-unlocked attacker**, **Verified-Boot-bypassed attacker**: all on-device guarantees collapse. Walt-passes does not attempt to detect these conditions independently of the platform.
- **Hardware extraction attacker** (decapsulation of secure element). Out of practical scope.
- **Coerced-user attacker** (someone with the user's unlocked device). Defense is the user's screen lock, not walt-passes.
- **Compromised Keystore implementation** at the OS level. We trust StrongBox/TEE; if those are broken, Walt cannot be the security backstop.

---

## 5. Attack surface inventory

Each row is a parser/decoder running on attacker-controlled bytes. Every row is a potential pre-auth RCE primitive. The mitigation column is the *enforced* control; "see T-x" links to the threat row that captures residual risk.

| # | Surface | Library / API | Attacker control | Primary mitigation | Threat refs |
|---|---|---|---|---|---|
| S1 | ZIP extraction | java.util.zip / Apache Commons Compress | full byte content, entry names, entry sizes, count | Bound total size (10 MB cap), entry count, per-entry size; canonicalize names, reject path traversal and symlinks; allowlist `.json`, `.png`, `.strings` only; in-memory extraction (no disk paths) | T-1, T-2, T-3 |
| S2 | JSON parsing of `pass.json` | kotlinx.serialization | full JSON content | Strict deserialization, explicit field types, ignore unknown fields safely, recursion depth bound | T-4 |
| S3 | `.strings` localization parser | walt-passes own implementation | full content of every `.lproj/pass.strings` | Hand-written, simple grammar; size bounds; encoding validation | T-5 |
| S4 | PNG decoding (logo, strip, background, thumbnail) | Android `ImageDecoder` | full PNG bytes | Per-image size cap, dimension cap, bytes-per-pixel cap before allocation; rendered into bounded `Bitmap`; `setOnPartialImageListener` rejects partial decodes | T-6 |
| S5 | PKCS#7 / CMS parsing | BouncyCastle | DER bytes inside `signature` entry | BC `CMSSignedData` parsing with bounded inputs; no JCE provider replacement; no certificate-chain plumbing trusted for gating decisions | T-7 |
| S6 | X.509 cert chain parsing | BouncyCastle / java.security | DER bytes inside the PKCS#7 SignerInfo | Parse-only. Chain analysis informational, **not** a gating control (decision wlt-0tn-q1). No WWDR root distribution, no rotation. | T-7 |
| S7 | Manifest hash verification | walt-passes own implementation | manifest.json contents and per-file hashes | SHA-256 over each declared entry; mismatched hash rejects the pass unconditionally; unlisted entries rejected. | T-8 |
| S8 | Barcode payload (string) | ZXing **encoder** path only | full barcode `message` string | Treated as opaque text. **No barcode decoding** of user-supplied images in v1. The only ZXing surface is generation of the rendered image from the embedded payload. | T-9 |
| S9 | URL field handling | walt-passes parser → passes-ui | text content of back-field URLs | Scheme allowlist (`http`, `https`, `mailto`, `tel`); inert text in parser; B3 visible-confirmation sheet at UI before any intent is fired; Custom Tabs (no in-app webview). | T-10 |
| S10 | HTML subset in back fields | walt-passes own renderer | back-field `value` strings | Render only `<b>`, `<i>`, `<br>`, `<a>`. No CSS, no JS, no external image loads, no auto-fetch, no link preview. | T-10 |
| S11 | `.lproj` localization tree | filesystem-like ZIP entries | locale dir names + file names | Locale codes validated against a regex allowlist before use; absent locale falls back to English; first-available fallback. No locale name interpolation into shell, SQL, or paths. | T-11 |
| S12 | `nfc` / `webServiceURL` / `personalization*` fields | parser | full field content | **Parse-and-ignore.** Fields are read for completeness validation but never acted on, never stored in queryable columns, never surfaced to the user. AID never registered. | T-12 |
| S13 | SQLCipher DB | sqlcipher-android (native) | only what walt-passes writes (parsed pass data) | DB key sourced from Keystore; PRAGMA cipher defaults (AES-256-CBC + HMAC-SHA512 in current SQLCipher); backup excluded. | T-13 |
| S14 | Compose rendering of attacker-supplied text | Compose Text composables | front/back field labels, values, header, primary, secondary, auxiliary | Standard Compose text rendering; bidi/unicode handled by the platform; HTML subset rendered through controlled spans only (S10). No `Text(AnnotatedString.fromHtml(...))` shortcut. | T-14 |

---

## 6. Threats and controls

Threats are numbered T-N. Each row states the threat, the affected asset(s), the control(s), and residual risk. Where a control is partial, the residual-risk column is candid.

### T-1: Zip-slip / path traversal

- **Description**: Malicious `.pkpass` contains entry names like `../../foo` or absolute paths. On naive extractors this writes outside the intended directory.
- **Asset**: A6 / A7 integrity, potential RCE if writing into executable paths.
- **Control**: Canonicalize each entry name; reject any name that resolves outside the in-memory extraction root; reject `..`, leading `/`, and Windows drive prefixes; reject ZIP entries marked as symlinks. Prior-art reference: FOSS pass apps surveyed (FossWallet, PassAndroid) do **not** implement zip-slip checks; walt-passes does.
- **Residual risk**: Negligible if the canonicalization is correctly written. Covered by tests including the public `zip-slip` test corpus.

### T-2: Zip bomb / resource exhaustion

- **Description**: 42.zip-class compression bomb expands to gigabytes; or a small archive with a billion zero-length entries exhausts heap.
- **Asset**: device availability.
- **Control**: Hard cap on (a) compressed archive size, (b) total uncompressed size, (c) entry count, (d) per-entry uncompressed size. Streaming extraction checks the running total and aborts mid-stream. Suggested defaults (firmed up in `passes-core` API design): archive ≤ 10 MB, entries ≤ 64, per-entry ≤ 4 MB, total uncompressed ≤ 32 MB.
- **Residual risk**: A pathological allocation pattern below the caps could still degrade UX. Acceptable; impact is local to Walt and recoverable by the user.

### T-3: Unexpected file types in archive

- **Description**: Archive contains an executable, native lib, or `.dex` aimed at confusing host or downstream consumers.
- **Asset**: process integrity if any code path were to load such a file.
- **Control**: Allowlist of file extensions inside the archive: `.json`, `.png`, `.strings`. Anything else rejects the pass with a structured error. No code path inside walt-passes ever loads or executes archive contents as code.
- **Residual risk**: Low. The allowlist is small and the parser never invokes the platform's content-type sniffer on archive bytes.

### T-4: Malicious JSON

- **Description**: `pass.json` exploits a lax deserializer: deeply nested objects (stack overflow), giant string fields (heap), polymorphic fields, hostile unicode, BOM tricks.
- **Asset**: parser availability, A2/A3 if RCE results.
- **Control**: kotlinx.serialization in strict mode (`ignoreUnknownKeys = true`, `coerceInputValues = false`, explicit field types, no polymorphic open types). Recursion-depth bound via custom `JsonElement` walk before `decodeFromJsonElement`. Field-size caps in the schema layer (e.g., string fields capped at 4 KB for label/value, longer for `description`, declared per field). Reject NaN/Infinity. Strict UTF-8.
- **Residual risk**: Logic bugs in walt-passes' own schema validation could allow oversized fields. Mitigated by exhaustive schema-bound tests.

### T-5: Malformed `.strings`

- **Description**: Hostile `.strings` file abuses the format (Apple's loose plist-derived KV format) to crash or smuggle binary content.
- **Asset**: parser availability.
- **Control**: Hand-written parser with explicit grammar; size cap per file; UTF-8/UTF-16 encoding validation; no shell/exec invocation; no path interpolation. Tested against a fuzz corpus.
- **Residual risk**: Low. The format is small enough that a hand-written parser is auditable.

### T-6: Image decoder exploitation

- **Description**: PNG with crafted IDAT/iTXt/iCCP chunks targeting the platform decoder. Historically, image decoders are a high-value RCE surface.
- **Asset**: A1, A2, A3, process integrity.
- **Control**: Pre-decode header inspection (read PNG IHDR chunk) to reject excessive width/height/depth before allocating. `ImageDecoder.setOnPartialImageListener` rejects partial decodes. Bitmap dimension cap (e.g., 2048×2048). Rejection of animated PNG / APNG (only supported chunks; reject `acTL`). Run in same process (no isolated process in v1; see §10).
- **Residual risk**: **High** in absolute terms because we still call into a complex native decoder. Walt depends on the platform decoder being patched. v2 candidate: isolated-process or `:isolated_image_decoder` service. v1 acceptable on the basis that (a) the Android platform decoder is widely audited, (b) the blast radius is documented in §2 and disclosed.

### T-7: Malicious PKCS#7 / X.509

- **Description**: Hostile DER inside the `signature` entry triggers ASN.1 parsing bugs in BouncyCastle (classic vector).
- **Asset**: process integrity.
- **Control**: Bounded input (size cap on `signature` entry). Pinned BouncyCastle version; CVE scanning in CI. No JCE provider replacement at runtime (we install BC only as a non-default provider, scoped to walt-passes use). **Importantly**: chain validation is informational, not gating, so we do not rely on path-validation correctness as a security control. The mathematical signature verification *is* gating but is a narrower API surface.
- **Residual risk**: Medium. ASN.1 parsers have a long history of memory-corruption bugs. Mitigations are version pinning + supply-chain monitoring (§8). v2 candidate: explore minimal BC subset or alternative ASN.1 parser if footprint becomes a concern.

### T-8: Tampered manifest / hash bypass

- **Description**: Attacker modifies a payload file (e.g., changes `pass.json`) but leaves `manifest.json` intact, hoping verification compares the wrong thing. Or includes files not declared in the manifest. Or duplicates entries.
- **Asset**: A3 integrity; user trust.
- **Control**: For every declared entry in `manifest.json`, recompute SHA-256 of the extracted bytes and compare. Reject if any computed hash differs. Reject if any extracted entry is not declared in the manifest. Reject duplicate names within the archive. Reject manifest entries pointing outside the archive.
- **Residual risk**: Negligible if implemented carefully. Covered by deterministic test fixtures.

### T-9: Hostile barcode payload

- **Description**: `barcode.message` is interpreted by a downstream scanner. While Walt does not interpret it, a scanner might treat it as a URL, a control sequence, etc.
- **Asset**: A8 (user attention) only at the rendering side.
- **Control**: Treat the payload as opaque text. Render via ZXing **encoder** with no decode of user-supplied images. The encoder takes a string and produces a bitmap; it does not parse the string semantically. We do not normalize, prefix, or transform the payload; we display it on the *back* fields if the issuer included it as a back-field, but never auto-action it.
- **Residual risk**: Low for walt-passes. The terminal side is out of scope.

### T-10: Phishing via back-field URL or HTML subset

- **Description**: A `coupon` pass embeds `<a href="https://attacker.example/">Click to claim</a>` or a misleading `tel:` link (e.g., `tel:+1-800-FAKE-BANK`).
- **Asset**: A8, downstream A2/A3 if user is induced to leak.
- **Control**: B3 confirmation sheet (decision wlt-0tn-q3-2): the **full**, untruncated URL or phone number is shown in a modal sheet before any external app is invoked. Long URLs render with truncated middle in the link label but the confirmation sheet shows the full target. Scheme allowlist. Custom Tabs for `http`/`https` (no in-app webview). HTML subset is rendered through controlled spans, never via WebView. No external image loading from back-field HTML.
- **Residual risk**: Medium. Determined phishing can still trick a user who clicks through the confirmation. Mitigation here is *informed* user consent, not prevention. Documented openly to the user via the confirmation sheet copy.

### T-11: Locale-name injection

- **Description**: Archive contains a `.lproj` whose name is `../../etc/passwd.lproj` or `;rm -rf;`. If the name is interpolated into a path, shell, or SQL, it triggers injection.
- **Asset**: process integrity.
- **Control**: Locale dir names are validated against a regex allowlist (BCP-47-ish: `[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*`). Locale resolution maps the validated name to an enum-like internal identifier; the raw archive name never reaches a path API or SQL parameter. Path traversal in entry names is already rejected by T-1.
- **Residual risk**: Negligible.

### T-12: NFC / WebService / personalization activation

- **Description**: Pass declares `nfc { message, encryptionPublicKey }` or `webServiceURL` in an attempt to make Walt register an AID, contact a server, or solicit personal data.
- **Asset**: A1 (HCE conflict with payment), A8.
- **Control**: These fields are parsed for structural-validity completeness but are never written to storage in actionable form, never surfaced in the UI, and never trigger any side effect. The AID registration code path does not exist in walt-passes (decision wlt-0tn-q1). `webServiceURL` is not even reachable from a code path that constructs URLs.
- **Residual risk**: Negligible. The defense is the absence of code, not the presence of a check. The completeness check is to keep the parser from rejecting valid Apple Wallet passes that include these fields.

### T-13: At-rest pass-data exfiltration

- **Description**: Attacker with file-system access (e.g., another user account on a multi-user device, an `adb pull` on a debuggable build, a cloud backup) wants to read pass data.
- **Asset**: A3, A4.
- **Control**: SQLCipher database with key wrapped by Keystore. Android Auto Backup explicitly excluded for the passes DB (decision wlt-0tn-q3-4). FBE protects the DB while the device is locked. App sandbox protects against other normal user apps.
- **Residual risk**: An attacker who can get the DB file plus run code as Walt's UID with the device unlocked can decrypt it (because SQLCipher needs the derived key in process memory). This is the same posture as walt-android transactions and is documented openly.

### T-14: Bidi / unicode rendering attacks

- **Description**: Hostile unicode (e.g., RLO/LRO override, zero-width characters, mixed-script homoglyphs) used to make `attacker.com` look like `bank.com`, or to truncate the displayed value misleadingly.
- **Asset**: A8.
- **Control**: B3 confirmation sheet for URLs uses `TextView`/Compose's standard rendering; we additionally normalize URL display in the sheet to show the eTLD+1 separately and to highlight non-ASCII characters in the host portion. (Specific normalization API is firmed up in passes-ui design.) For non-URL text, we render as-is and accept the platform behavior.
- **Residual risk**: Medium. Bidi/homoglyph defense is hard; we rely on B3's full-URL display to give the user the unambiguous target, with non-ASCII host highlighting as a tripwire.

---

## 7. Cross-cutting controls

These controls apply across multiple threats above and across the lifetime of the pass.

### 7.1 Coroutine timeouts and cancellation

All parsing is wrapped in coroutine timeouts. A pass that takes longer than the configured budget (default: a few seconds) is cancelled with a structured error. This bounds attacker-controlled CPU.

### 7.2 In-memory extraction only

`passes-core` never writes attacker-controlled bytes to disk. The only on-disk persistence path is in `passes-storage`, which writes already-validated structured data (and the original blob, encrypted, for audit/replay).

### 7.3 Result types over exceptions

Every parser entry point returns a sealed `ParseResult<T>` (matches walt-android's `Result<T>` convention). This prevents control-flow surprises, makes error handling exhaustive in `when`, and avoids accidental information leakage through stack traces in logs.

### 7.4 TelemetryGuard

`passes-core` defines a `TelemetryGuard` interface whose method signature is structurally PII-forbidden (the API accepts only enum event types and bounded numeric/categorical fields, never `String` user-content). The Walt consumer wires its `core/telemetry` implementation to this; the contract is enforced by interface shape, not by reviewer discipline.

### 7.5 No webviews

Walt-passes never renders pass content in a `WebView`. The HTML subset rendering is implemented as an explicit AST-to-AnnotatedString conversion. This eliminates an entire class of XSS-adjacent issues.

### 7.6 No PII in logs

Walt-android's `core/telemetry` rule (no PII in logs) is inherited and enforced by the structural TelemetryGuard above. Decoded pass content is **never** logged at any level. Parser failures log only structured error codes.

### 7.7 Secure defaults / opt-in laxity

`ParserConfig` exposes the bounds (size caps, count caps, depth cap) but defaults are chosen for security. Consumers must opt in to looser bounds if they need them. Walt-android does not loosen any default in v1.

### 7.8 Locked dependency versions + CI scanning

See §8.

### 7.9 No reflection on attacker-controlled types

kotlinx.serialization is used in code-generated mode (KSP/compiler plugin), not reflective mode. No runtime reflection over fields the attacker can name.

### 7.10 Backup exclusion

`AndroidManifest` rules and `data_extraction_rules.xml` exclude the passes DB from Android Auto Backup, Device-to-Device Transfer, and Backup-to-Cloud. Decision wlt-0tn-q3-4.

---

## 8. Supply-chain risk

### 8.1 Direct dependencies and their risk profile

| Dependency | Where used | Risk profile | Mitigation |
|---|---|---|---|
| **BouncyCastle** (`bcpkix-jdk18on`, `bcprov-jdk18on`) | `passes-core` PKCS#7 + X.509 parsing | High. ASN.1 parser; long CVE history; pinned-version regression risk. | Pin to a specific version, not a range. CVE scanning via OSS-Index / OWASP Dependency-Check in CI. Subscribe to BC release notes for security advisories. Use BC as a *non-default* JCE provider, scoped to passes-core; do not call `Security.insertProviderAt(...)`. Avoid optional BC modules (TLS, OpenPGP) we do not need. |
| **kotlinx.serialization** | `passes-core` JSON parsing | Low-medium. Code-generated; well-maintained by JetBrains. | Pin version, monitor JetBrains advisories. Use KSP (compile-time), not reflection. |
| **Apache Commons Compress** *(if used)* | `passes-core` ZIP extraction | Medium. Has had zip-slip-class CVEs. | Pin version. Rely primarily on our own bounds-checking wrapper, not the library's defaults. Re-evaluate vs `java.util.zip` with a thin wrapper at architecture phase. |
| **ZXing core** | `passes-ui` barcode rendering | Low for our usage (encode-only). Decode path is the historically risky one and we do not use it. | Pin version. Use only the encoder API (`MultiFormatWriter`). |
| **SQLCipher (sqlcipher-android)** | `passes-storage` DB | Medium. Native code; smaller attack surface than general SQL. | Pin version. Rely on Zetetic's release cadence and security advisories. Use the official artifact, not a fork. |
| **AndroidX Compose** | `passes-ui` | Low. Trusted platform component. | Inherit walt-android's BOM. |
| **Android `ImageDecoder`** | `passes-ui` (and pre-decode path in `passes-core`) | Medium. Native; broad attack surface; OS-version-dependent. | Pre-validate dimensions/depth from PNG header in `passes-core` before invoking the decoder. Document the platform-decoder dependency as a residual risk. |

### 8.2 Build-time integrity

- `gradle/libs.versions.toml` pins exact versions, not ranges.
- `gradle.lockfile` (or equivalent) committed to lock transitive dependencies.
- CI verifies dependency lockfile consistency on every PR.
- CI runs vulnerability scanning (OSS-Index, OWASP Dependency-Check, or GitHub Dependabot Security alerts) and fails the build on critical findings.

### 8.3 Transitive risk

A high-fanout transitive (e.g., `kotlinx-serialization-core` pulling in stdlib) is itself low-risk, but the discipline is: prefer dependencies with small, well-known transitive trees. Architecture-phase work in `wlt-3j8` and `wlt-ajj` will minimize the dependency footprint where reasonable.

### 8.4 Source-side integrity

- All commits to `walt-passes` `main` are signed (GPG or sigstore).
- Releases are tagged; tags are signed.
- Release artifacts are reproducible from a tagged commit.
- Two-person review required for changes touching `passes-core` parser or `passes-storage` key handling.

### 8.5 Disclosure

- `SECURITY.md` documents the disclosure channel.
- A security advisory channel publishes CVEs against walt-passes if any are filed.

---

## 9. Side channels and observability

### 9.1 In-process observability

An attacker who has RCE in Walt's process can already read everything in process memory; side channels are not the dominant concern at that point. The model assumes RCE is the worst case (§2).

### 9.2 Cross-process side channels

- **CPU timing** (e.g., observing parse duration of a pkpass): could in principle leak structural information about the pass. Not a concern in v1; the user-perceived latency is bounded by §7.1 and the information leaked is shape, not content.
- **Memory pressure**: cap on bitmap allocation reduces OOM-driven side channels.
- **Disk I/O patterns**: the Android sandbox prevents other apps from observing Walt's I/O. We do not write attacker-controlled bytes to disk in `passes-core` (§7.2).
- **Battery / thermal side channels**: not a meaningful threat vector for pass content.

### 9.3 Network observability

`passes-core` makes no network calls. `passes-storage` makes no network calls. `passes-ui` opens user-confirmed URLs via Custom Tabs; the consumer (walt-android) controls that flow. No covert outbound traffic is possible from walt-passes itself.

### 9.4 Logging side channels

Logs are structured-error-only (§7.6). Stack traces are not emitted at INFO/DEBUG; even at ERROR they exclude exception messages that might quote attacker-controlled bytes. Decoded content is never logged.

### 9.5 Crash reporters

If walt-android wires a crash reporter, walt-passes' `TelemetryGuard` interface does **not** include a hook for crash reporting that carries content. Crash reporters that capture full process state are an exfiltration risk; that is a walt-android decision, documented in walt-android's threat model. Walt-android currently does not wire a third-party crash reporter for release builds.

---

## 10. Residual risk and v2 candidates

### 10.1 Acknowledged residual risks

1. **Image decoder is in-process** (T-6). A v2 candidate is to run the decoder in a separate `:image_decoder` process or an isolated `Service` so that decoder RCE does not pivot to A2/A3.
2. **ASN.1 parser is BouncyCastle** (T-7). v2 candidate: evaluate a minimal ASN.1 subset specific to PKCS#7 SignedData if the BouncyCastle attack surface becomes a maintenance concern.
3. **Bidi/homoglyph defense is partial** (T-14). v2 candidate: run the URL through a Punycode/IDN normalizer and surface the eTLD+1 separately in the confirmation sheet with explicit warnings on mixed-script hosts.
4. **No isolated parsing process**. v1 ships parser in the main process for simplicity. v2 candidate: a `:passes_parser` process with restricted permissions, IPC-only return surface.
5. **No HSM-bound DB key**. The DB key is wrapped by Keystore but used as raw bytes by SQLCipher in process memory. The ceiling here is SQLCipher's architecture; an HSM-only mode would require a different storage stack.

### 10.2 Items deliberately not mitigated

- **Coerced unlocking** (someone forces the user to unlock). Out of scope.
- **Screen recording / accessibility-service exfiltration** by another app. Walt does not enumerate accessibility services; users granting an a11y service to a hostile app is a user-trust failure. Documented for transparency.
- **Notification listener exfiltration**. Walt-passes does not post pass content to notifications in v1.

### 10.3 Open questions for the architecture phase

These will be resolved by `wlt-3j8` (passes-storage interfaces) and `wlt-ajj` (passes-core public API):

- Final size caps for ZIP / image / JSON / total archive — current numbers in §6 are starting defaults.
- Exact PNG pre-decode validator and its API in `passes-core`.
- Whether `passes-storage` keeps the original `.pkpass` blob for re-render or only the decoded representation. (Affects T-13 blast radius and re-localization correctness; tradeoff captured separately.)
- HTML subset renderer: whether to ship a hand-written AST or a vetted minimal Markdown subset.

---

## 11. Audit map

For each trust claim made by Walt, the code path that implements it and the threat-model section that constrains it.

| Walt trust claim | Module | Implementation (planned location) | Threats addressed |
|---|---|---|---|
| Parser is hardened against malicious input | `passes-core` | `PassParser`, `ParserConfig`, ZIP/JSON/image bounds | T-1, T-2, T-3, T-4, T-5, T-6, T-11 |
| Signature verification rejects tampering, shows provenance | `passes-core` | `SignatureVerifier`, `SignatureStatus` (sealed) | T-7, T-8 |
| Storage at rest is encrypted with hardware-backed keys | `passes-storage` | SQLCipher DB, Keystore-sourced key | T-13, A4 |
| Pass data is excluded from Android Auto Backup | `passes-storage` | manifest `dataExtractionRules`, `data_extraction_rules.xml` | T-13 |
| Pass content never appears in logs or telemetry | `passes-core` + `passes-storage` | `TelemetryGuard` interface (PII-forbidden by signature) | §7.4, §7.6 |
| Back-field URLs require visible confirmation | `passes-ui` | `UrlConfirmationSheet`, scheme allowlist, Custom Tabs | T-9, T-10, T-14 |
| Pass deletion is irreversible; caches are wiped | `passes-storage` | `PassRepository.delete`, cache wipe, no soft-delete | T-13, decision wlt-0tn-q3-4 |
| Expired passes show a badge but are not auto-deleted | `passes-ui` + `passes-storage` | `Pass.isExpired`, `ExpiredBadge` composable | privacy semantics |
| NFC payloads are never activated | `passes-core` (absence of code path) | parse-and-ignore for `nfc`, `webServiceURL`, `personalization*` | T-12 |
| HTML subset cannot escape to a webview | `passes-ui` | AST-to-AnnotatedString renderer; no `WebView` import | T-10, §7.5 |
| Bidi/homoglyph defense in URL surface | `passes-ui` | URL normalization in confirmation sheet | T-14 |

---

## Decision references

- `decision-wlt-0tn-q1-2026-05-03-pass`: signature policy = lenient + mathematical verify + provenance display + parser hardening. (Position A.)
- `decision-wlt-0tn-q2-2026-05-03-naming`: feature umbrella, repo, module names.
- `decision-wlt-0tn-q3-2-2026-05-03`: B3 visible-URL confirmation, scheme allowlist, HTML subset, no webview.
- `decision-wlt-0tn-q3-3-2026-05-03`: localization handling.
- `decision-wlt-0tn-q3-4-2026-05-03`: deletion semantics, no VACUUM.
- `decision-wlt-0tn-q3-v1-scope-summary-2026`: v1 in/out scope.
- `decision-wlt-0tn-q4-2026-05-03-repo`: repo, license, distribution.
- `decision-wlt-0tn-q5-2026-05-03-library`: three-module shape; trust-claim-bearing logic lives only here.
- `foss-pkpass-signature-policy-field-survey-2026-05`: empirical FOSS comparison.

## Change log

- **2026-05-03**: Initial v1 architecture-phase draft (wlt-5nl).
