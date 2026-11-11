# Walt Passes (Android)

Open-source pass-handling kernel for the Walt Android wallet app. PKPASS parsing, signature verification, encrypted storage, and security-critical UI flows.

## Repository Purpose

This repository exists for **transparency-for-trust**, not library-for-reuse. Walt-android (closed source) consumes this code directly so that users and security researchers can verify what Walt does with their pass data. Reuse by other applications is welcome as a side effect; the primary commitment is the audit trail.

The trust claim: every security-and-privacy-critical behavior Walt makes about pass handling is implemented in code that lives in this repository. Walt-android does not parallel-implement any trust-claim-bearing logic; it wraps and themes what is here.

## Modules

Thirteen Gradle modules. `docs/module-map.md` holds the full contract of each (what it owns, what it must not do, why each edge exists). Read the entries for the modules you touch before changing a public type or a dependency.

| Module | Kind | Owns |
|---|---|---|
| `passes-core` | pure JVM | Parser, model, signature verifier, `.strings` parser, `TelemetryGuard`, `PassBundleReader`, `PassEncoder` |
| `passes-document-core` | pure JVM | Sealed `Document` model (PDF, image), `DocumentRejectedKind`, PDF header sniff, import config, `DocumentTelemetryGuard` |
| `passes-barcode-core` | pure JVM | The one ZXing symbol decode and the symbology allowlist |
| `passes-isolation` | Android, mechanism only | memfd `PfdFactory`, isolated-worker bind/teardown session, bounded fd read, `rasterReplyFits` |
| `passes-image-decode` | Android, mechanism only | The single bounded `ImageDecoder` primitive |
| `passes-pdf` | Android | Isolated-process PDF renderer service |
| `passes-barcode` | Android | Isolated-process barcode-from-image decode; returns only `{payload, format}` |
| `passes-image` | Android | Isolated-process image decode; its own reject taxonomy `ImageDecodeRejectedKind` |
| `passes-document` | Android | Import orchestrator: sniffs PDF vs image, branches to the backend, unified `DocumentImportResult` |
| `passes-storage` | Android | SQLCipher store, Keystore key, backup exclusion, irreversible deletion, `pass_archives` sidecar |
| `passes-ui-core` | Compose | Shared substrate: `ArgbColor`, `isolated()` BiDi helper, `faceIsTinted` |
| `passes-ui` | Compose | PKPASS surfaces, B3 URL confirmation sheet, `PassesTheme` / `PassesSemantics` |
| `passes-document-ui` | Compose | Document surfaces (`DocumentView`, tile, lane, trust caption), `DocumentTheme` / `DocumentSemantics` |

Dependency rules:

- `passes-core`, `passes-document-core`, and `passes-barcode-core` have NO Android framework dependencies (KMP-friendly).
- `passes-storage`, `passes-pdf`, `passes-barcode`, `passes-image`, `passes-ui`, and `passes-document-ui` are independent peers. The only edges among them are `passes-document-ui → passes-pdf` and `passes-document-ui → passes-image`.
- `passes-pdf` and `passes-image` never depend on each other. They meet only in `passes-document`, which sits above both.
- `passes-isolation` and `passes-image-decode` depend on nothing in this repo and sit below their consumers. They are mechanism: caps, allowlists and reject taxonomies stay with each consumer.
- `passes-barcode → passes-barcode-core → passes-core`. `passes-ui` and `passes-document-ui` both depend on `passes-ui-core`, never on each other.
- `passes-storage → passes-document-core` is a test-only edge.
- DECISIVE CONSTRAINT: walt-android consumes this code directly; trust-claim-bearing logic lives ONLY here, never reimplemented in walt-android.

## Code Style

- **100% Kotlin** (no Java)
- Sealed interfaces (not sealed classes)
- `Result<T>` over exceptions (matches walt-android conventions)
- StateFlow (not LiveData)
- JUnit 4 + Google Truth for testing
- `kotlinx.serialization` for JSON
- BouncyCastle (JVM) for PKCS#7 / X.509

## Beads

Use `bd` for task tracking (prefix `wpass`). The bead database is local-only: `.beads/issues.jsonl` is gitignored and never committed. Keep bead text out of commits, PRs and GitHub issues.

## Decisions

Brainstorming-phase decisions (`decision-wlt-0tn-q1`..`q5`) and the FOSS signature-policy field survey live in [`docs/brainstorm-decisions.md`](docs/brainstorm-decisions.md). Later ADRs in `docs/adr/` supersede them where they differ.
