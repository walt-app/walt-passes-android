# Walt Passes (Android)

Open-source pass-handling kernel for the Walt Android wallet app. PKPASS parsing, signature verification, encrypted storage, and security-critical UI flows.

## Repository Purpose

This repository exists for **transparency-for-trust**, not library-for-reuse. Walt-android (closed source) consumes this code directly so that users and security researchers can verify what Walt does with their pass data. Reuse by other applications is welcome as a side effect; the primary commitment is the audit trail.

The trust claim: every security-and-privacy-critical behavior Walt makes about pass handling is implemented in code that lives in this repository. Walt-android does not parallel-implement any trust-claim-bearing logic; it wraps and themes what is here.

## Architecture Rules

- Thirteen Gradle modules:
  - `passes-core` — pure Kotlin/JVM. Parser, model, signature verifier, `.strings` parser, `TelemetryGuard` interface. NO Android framework dependencies.
  - `passes-document-core` — pure Kotlin/JVM (package `is.walt.passes.document`). The kind-agnostic document model: the sealed `Document` supertype with its `PdfDocument` / `ImageDocument` arms, `DocumentId`, `DocumentRejectedKind`, the PDF header sniffer (`isPdfHeader`), import config, and the `DocumentTelemetryGuard` interface. Named `passes-pdf-core` until wpass-xmp; renamed once wpass-i9x generalized it past PDF-only. NO Android framework dependencies.
  - `passes-barcode-core` — pure Kotlin/JVM. The trust-claim-bearing ZXing symbol decode (`decodeLuminance(LuminanceSource)`) plus the symbology allowlist (`ROSTER_BY_ZXING_FORMAT`, `DECODE_HINTS`). ONE decode implementation shared by both the isolated still-image path (`passes-barcode`) and the future in-process live-camera path (wpass-7xo). Depends on `passes-core` (for `ScannableFormat` / `BarcodeDecodeResult`) and adds `com.google.zxing:core`. NO Android framework dependencies.
  - `passes-isolation` — Android-only. Shared isolated-worker plumbing consumed by both `passes-pdf` and `passes-barcode`: the memfd `PfdFactory` (materialize bytes → in-RAM PFD) and the bind/teardown session facade (`IsolatedWorkerSessionFactory` → `ConnectResult` → `IsolatedWorkerSession`, parameterized over the per-worker service class and typed binder client). Declares NO service of its own; each consumer declares its own `isolatedProcess` service. Exists so the PDF render and barcode decode paths share ONE bind/memfd stack instead of parallel copies (wpass-zrt.6 / walt-android wlt-ygl). NO trust-claim-bearing policy of its own — it is mechanism; the per-worker contract and result shape stay in the consumer.
  - `passes-image-decode` — Android-only (NOT pure-JVM — needs `android.graphics`, so it is not a `-core` module). The single bounded `ImageDecoder` primitive: `decodeBounded(rawBytes, allocator, gate, …)` applies the caller's header gate before allocation, forces a 1x1 decode on reject, and contains every `Throwable` (the `OutOfMemoryError` fold is opt-in via a nullable callback). Consumed by both `passes-barcode` (isolated still-image symbol decode, software allocator, OOM contained) and `passes-ui` (in-process pkpass display, default allocator, OOM propagated), and reused by the wpass-i9x isolated image-document service. NO trust-claim-bearing policy of its own — it is mechanism; the caps, format allowlist, and rejection taxonomy (`R`) stay with each consumer.
  - `passes-pdf` — Android-only. Isolated-process PDF renderer service (ADR 0005 D3). Wraps Android's `PdfRenderer` behind a hand-rolled binder with no extraction surface. Binds its service through `passes-isolation`.
  - `passes-barcode` — Android-only. Isolated-process barcode/QR-from-image decode service (wpass-zrt). `BarcodeImageDecoder` facade takes an image source over a bind, decodes inside a permissionless sandbox, and returns only `{payload, ScannableFormat}` (pure `BarcodeDecodeResult` from `passes-core`); never a Bitmap or source bytes. Holds only the `Bitmap → RGBLuminanceSource` adapter and delegates to `passes-barcode-core` for the symbol decode itself. No classification/validation here.
  - `passes-image` — Android-only. Isolated-process image-decode service (wpass-6yp, step 3 of wpass-i9x). `ImageDecodeService` decodes ORIGINAL user-image bytes inside a permissionless sandbox and returns only a bounded, Walt-produced ARGB_8888 raster to the host over `SharedMemory` — never the source bytes, never a host-process codec run. Mirrors `passes-pdf`'s `PdfRendererService` (hand-rolled binder, raster-over-SharedMemory) but simpler: one "page", no PDFium, no SDK-34 floor (rests on `ImageDecoder` at the repo-wide minSdk 28). Binds its service through `passes-isolation` and delegates the bounded decode to `passes-image-decode`. Owns its OWN reject taxonomy (`ImageDecodeRejectedKind`: `NotAnImage` / `OversizedAtImport` / `DimensionsTooLarge` / `DecodeFailed` / `DecoderUnavailable`) — deliberately NOT flattened into the PDF `DocumentRejectedKind`. Exposes the `ImageDecodeBinder` contract (consumed by the wpass-i9x display surface the way `passes-document-ui` consumes `PdfRendererBinder`) and a `BoundedImageDecoder` bind-per-call facade (for the import path).
  - `passes-document` — Android-only. The document-import orchestrator (wpass-i9x step 4 / wpass-bsf). `DocumentImporter` magic-byte-sniffs PDF vs image (`isPdfHeader` from `passes-document-core` + the salvaged `sniffImageFormat` here) and branches to the right isolated backend: PDF → `passes-pdf`'s `PdfImporter`/renderer service; image → `passes-image`'s `BoundedImageDecoder` sandbox. The single bounded read materializes the bytes ONCE into a sealed memfd PFD (via `passes-isolation`) and hands it to whichever backend wins, so a one-shot fd source is read exactly once. Folds the per-backend outcome onto a unified `DocumentImportResult` whose reject arms REUSE each backend's taxonomy verbatim — `PdfRejected(DocumentRejectedKind)` and `ImageRejected(ImageDecodeRejectedKind)` — with no third "document reject" enum and NO merge into the PDF taxonomy; `Unrecognized` and `StorageHandoffFailed` are the two kind-agnostic arms. This is the ONE place `passes-pdf` and `passes-image` meet (see the peer rule below); it holds NO decode/render code of its own and declares no service, so the isolated surface stays auditable in the two backends. Storage is wired through a `persist(DocumentPersist)` callback (not a `passes-storage` dependency), mirroring `PdfImporter`.
  - `passes-storage` — Android-only. SQLCipher with Keystore-sourced key, Android Auto Backup exclusion, irreversible deletion logic. The `documents` table is generalized to PDF + image (schema v6): a `format` discriminator column ('pdf'/'png'/'jpeg'/'webp') plus nullable `width_px`/`height_px`, and `insertDocument` takes a sealed `DocumentInsert` (`Pdf(pageCount)` | `Image(format, widthPx, heightPx)`). `loadDocumentBytes`/`loadDocumentThumbnail` stay kind-agnostic.
  - `passes-ui-core` — Android + Compose. Tiny shared substrate for both UI modules: `ArgbColor`, `Color` conversion, the `isolated()` BiDi (FSI/PDI) helper. NO trust-claim-bearing surfaces of its own; existence is to keep `passes-ui` and `passes-document-ui` from depending on each other for these primitives.
  - `passes-ui` — Android + Compose. PKPASS surfaces only — pass front/back composables, B3 URL confirmation sheet, expired badge, bounded image rendering, `PassesTheme` / `PassesSemantics`. Themable via tokens passed in by the consumer.
  - `passes-document-ui` — Android + Compose (package `is.walt.passes.document.ui`; named `passes-pdf-ui` until wpass-xmp). Document surfaces (PDF and image) — `DocumentView` (a dispatcher over sealed `Document`: PDF → swipeable pager, image → single no-pager image), `DocumentTile`, `DocumentsLane`, `DocumentTrustCaption`, `DocumentTheme` / `DocumentSemantics`. Depends on `passes-pdf` (for `PdfRendererBinder`), `passes-image` (for `ImageDecodeBinder` — the reserved edge, see below), and `passes-document-core` (for `Document`); themed via its own sibling `LocalDocumentSemantics`, NOT through `PassesSemantics`. The trust caption is non-suppressible in BOTH arms (one verbatim `DocumentTrustCaption`).
- `passes-core`, `passes-document-core`, and `passes-barcode-core` have NO Android framework dependencies (KMP-friendly).
- `passes-storage`, `passes-pdf`, `passes-barcode`, `passes-image`, `passes-ui`, and `passes-document-ui` are independent peers (no edges among them at this level). The permitted exceptions are `passes-document-ui → passes-pdf` and `passes-document-ui → passes-image`, since the document-display surface needs `PdfRendererBinder` (PDF arm) and `ImageDecodeBinder` (image arm, landed with wpass-bsf) to render from. `passes-pdf` and `passes-image` remain independent of EACH OTHER: nothing makes them depend on one another. The one place they meet is `passes-document`, which sits ABOVE both (the way `passes-document-ui` sits above `passes-pdf`) and depends on each — it is the sole module with an edge to both, by design, so the sniff-and-branch orchestration is a single audited surface rather than a peer-to-peer edge.
- `passes-barcode → passes-barcode-core → passes-core`, mirroring the `passes-pdf → passes-document-core` split: the Android module keeps only the platform `Bitmap` adapter, while the pure-JVM decode and its roster allowlist live in `passes-barcode-core` so the still-image and live-camera paths share one implementation. `passes-barcode-core` adds no edge between the peers — it sits below `passes-barcode` alongside `passes-core`.
- `passes-pdf`, `passes-barcode`, and `passes-image` all depend on `passes-isolation` for the shared bind/memfd plumbing; `passes-document` also depends on it (to materialize the sniffed bytes into one memfd PFD before handing them to a backend). `passes-isolation` depends on nothing in this repo (only the Android framework + coroutines), so it adds no edge among those peers — it sits below all of them.
- `passes-barcode`, `passes-ui`, and `passes-image` all depend on `passes-image-decode` for the shared bounded `ImageDecoder` mechanism. `passes-image-decode` depends on nothing in this repo (only `android.graphics`), so it adds no edge among them — it sits below all three, like `passes-isolation`. `passes-image` is the wpass-i9x isolated image-document service that consumer was reserved for.
- `passes-ui` and `passes-document-ui` both depend on `passes-ui-core`. Neither depends on the other.
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
