# Walt Passes (Android)

Open-source pass-handling kernel for the Walt Android wallet app. PKPASS parsing, signature verification, encrypted storage, and security-critical UI flows.

## Repository Purpose

This repository exists for **transparency-for-trust**, not library-for-reuse. Walt-android (closed source) consumes this code directly so that users and security researchers can verify what Walt does with their pass data. Reuse by other applications is welcome as a side effect; the primary commitment is the audit trail.

The trust claim: every security-and-privacy-critical behavior Walt makes about pass handling is implemented in code that lives in this repository. Walt-android does not parallel-implement any trust-claim-bearing logic; it wraps and themes what is here.

## Architecture Rules

- Ten Gradle modules:
  - `passes-core` — pure Kotlin/JVM. Parser, model, signature verifier, `.strings` parser, `TelemetryGuard` interface. NO Android framework dependencies.
  - `passes-pdf-core` — pure Kotlin/JVM. PdfDocument model, header sniffer, import config, `DocumentTelemetryGuard` interface. NO Android framework dependencies.
  - `passes-barcode-core` — pure Kotlin/JVM. The trust-claim-bearing ZXing symbol decode (`decodeLuminance(LuminanceSource)`) plus the symbology allowlist (`ROSTER_BY_ZXING_FORMAT`, `DECODE_HINTS`). ONE decode implementation shared by both the isolated still-image path (`passes-barcode`) and the future in-process live-camera path (wpass-7xo). Depends on `passes-core` (for `ScannableFormat` / `BarcodeDecodeResult`) and adds `com.google.zxing:core`. NO Android framework dependencies.
  - `passes-isolation` — Android-only. Shared isolated-worker plumbing consumed by both `passes-pdf` and `passes-barcode`: the memfd `PfdFactory` (materialize bytes → in-RAM PFD) and the bind/teardown session facade (`IsolatedWorkerSessionFactory` → `ConnectResult` → `IsolatedWorkerSession`, parameterized over the per-worker service class and typed binder client). Declares NO service of its own; each consumer declares its own `isolatedProcess` service. Exists so the PDF render and barcode decode paths share ONE bind/memfd stack instead of parallel copies (wpass-zrt.6 / walt-android wlt-ygl). NO trust-claim-bearing policy of its own — it is mechanism; the per-worker contract and result shape stay in the consumer.
  - `passes-pdf` — Android-only. Isolated-process PDF renderer service (ADR 0005 D3). Wraps Android's `PdfRenderer` behind a hand-rolled binder with no extraction surface. Binds its service through `passes-isolation`.
  - `passes-barcode` — Android-only. Isolated-process barcode/QR-from-image decode service (wpass-zrt). `BarcodeImageDecoder` facade takes an image source over a bind, decodes inside a permissionless sandbox, and returns only `{payload, ScannableFormat}` (pure `BarcodeDecodeResult` from `passes-core`); never a Bitmap or source bytes. Holds only the `Bitmap → RGBLuminanceSource` adapter and delegates to `passes-barcode-core` for the symbol decode itself. No classification/validation here.
  - `passes-storage` — Android-only. SQLCipher with Keystore-sourced key, Android Auto Backup exclusion, irreversible deletion logic.
  - `passes-ui-core` — Android + Compose. Tiny shared substrate for both UI modules: `ArgbColor`, `Color` conversion, the `isolated()` BiDi (FSI/PDI) helper. NO trust-claim-bearing surfaces of its own; existence is to keep `passes-ui` and `passes-pdf-ui` from depending on each other for these primitives.
  - `passes-ui` — Android + Compose. PKPASS surfaces only — pass front/back composables, B3 URL confirmation sheet, expired badge, bounded image rendering, `PassesTheme` / `PassesSemantics`. Themable via tokens passed in by the consumer.
  - `passes-pdf-ui` — Android + Compose. PDF-document surfaces only — `DocumentView`, `DocumentTile`, `DocumentsLane`, `DocumentTrustCaption`, `DocumentTheme` / `DocumentSemantics`. Depends on `passes-pdf` (for `PdfRendererBinder`) and `passes-pdf-core` (for `PdfDocument`); themed via its own sibling `LocalDocumentSemantics`, NOT through `PassesSemantics`.
- `passes-core`, `passes-pdf-core`, and `passes-barcode-core` have NO Android framework dependencies (KMP-friendly).
- `passes-storage`, `passes-pdf`, `passes-barcode`, `passes-ui`, and `passes-pdf-ui` are independent peers (no edges among them at this level). The single permitted exception is `passes-pdf-ui → passes-pdf`, since the document-rendering surface needs `PdfRendererBinder` to take pages from.
- `passes-barcode → passes-barcode-core → passes-core`, mirroring the `passes-pdf → passes-pdf-core` split: the Android module keeps only the platform `Bitmap` adapter, while the pure-JVM decode and its roster allowlist live in `passes-barcode-core` so the still-image and live-camera paths share one implementation. `passes-barcode-core` adds no edge between the peers — it sits below `passes-barcode` alongside `passes-core`.
- `passes-pdf` and `passes-barcode` both depend on `passes-isolation` for the shared bind/memfd plumbing. `passes-isolation` depends on nothing in this repo (only the Android framework + coroutines), so it adds no edge between the two peers — it sits below both. No other module depends on it.
- `passes-ui` and `passes-pdf-ui` both depend on `passes-ui-core`. Neither depends on the other.
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

```bash
bd ready                    # What to work on
bd update wpass-xxx --claim  # Start work
bd close wpass-xxx --reason "Completed and tested"
```

## Decisions and Memories

Brainstorming-phase decisions are captured as `bd remember` entries in this repo's beads database (mirrored from walt-android on 2026-05-04):

- `decision-wlt-0tn-q1` through `decision-wlt-0tn-q5`
- `foss-pkpass-signature-policy-field-survey` (empirical FOSS signature-policy survey)

Search with `bd memories <keyword>`.


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
