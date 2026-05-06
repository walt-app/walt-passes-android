# ADR 0005: PDF document support

- Status: Accepted
- Date: 2026-05-06
- Tracks: parent epic `wpass-i2r`; threat-model bead `wpass-0ri`; renderer-validation bead `wpass-6m9`; child beads `wpass-jsb` (core model), `wpass-pti` (storage schema), `wpass-5v9` (renderer service), `wpass-7wn` (UI integration)
- Decision context: 2026-05-06 brainstorming on add-flow coverage on Android; user-confirmed defaults on 2026-05-06 with two overrides (max pages = 10, no share-out).

## Context

Walt v1 imports PKPASS files exclusively. Many concerts, events, transit operators, and airlines distribute tickets only as PDFs, especially on Android where the "Add to Google Wallet" pathway is structurally unreachable to a third-party wallet (Google Wallet passes are server-hosted objects under an authenticated Google API, not a portable file format Walt can ingest). To close that user-visible gap without converting Walt into a Google Wallet client, PDF support is added as a sibling concept to PKPASS passes.

PDFs are an order of magnitude worse than PKPASS as a parser-side attack surface. The format permits embedded JavaScript, `Launch`/`SubmitForm`/`URI`/`GoToR` actions, AcroForm and XFA active content, embedded file attachments, attached digital signatures, network fetches at render time, decoder-CVE-heavy subformats (JBIG2, JPEG2000, CCITT), object-graph attacks, encrypted documents, and render bombs.

This ADR codifies the security posture, the renderer architecture, and the deliberate non-features that prevent a future contributor from re-introducing the surface that the v1 design strips out.

## Decisions

### D1. Sibling concept; PDFs are not Passes

A new `PassDocument` data class lives alongside `Pass`. Documents have no `PassType`, no `SignatureStatus`, no structured fields, no expiration. They carry: opaque PDF bytes, a thumbnail bitmap, page count, original filename, import timestamp, and `Provenance.UserProvided` (single-arm, by design).

A separate `documents` table is added to the existing `walt_passes.db` SQLCipher database (schema v1 to v2). Same database key, same backup-exclusion rules, same irreversible-delete contract.

A separate "Documents" lane appears below the passes list in `passes-ui`. Users see passes and documents as related-but-distinct concepts.

Rationale: the trust contract differs structurally. Mixing PDFs into the `Pass` model would put a "Verified" pill API path within reach of code that should never offer it. Sibling separation moves that risk from "policed at every call site forever" to "physically impossible."

### D2. System renderer only: `android.graphics.pdf.PdfRenderer`

Rendering uses the platform `android.graphics.pdf.PdfRenderer` API. No third-party PDF library is bundled.

PDFium ships in the MediaProvider Mainline module on Android 13+ and updates via Play System Updates, decoupling PDFium CVE response from Walt release cadence. The API is structurally narrow: open `ParcelFileDescriptor`, render page N to `Bitmap`. No JavaScript execution path. No `URI` / `Launch` / `SubmitForm` action processing during page render. No network. The PDF cannot paint outside the bitmap Walt provides.

Alternatives considered:

- **PdfBox-Android (Apache PDFBox port)**. Rejected because (a) larger code surface in-process, (b) parses content Walt does not need (forms, annotations, signatures), opening codepaths the no-extraction rule wants closed, (c) Walt would own the CVE response.
- **MuPDF**. Rejected on license grounds (AGPL/commercial), which conflicts with the open-source-for-trust positioning of this repository.

### D3. Renderer runs in an isolated process

A `Service` annotated `android:isolatedProcess="true"` hosts the renderer. The main wallet process binds the service, sends a `ParcelFileDescriptor` to the PDF bytes, receives a `Bitmap` over shared memory, and unbinds. The service has no app-data access, no inherited network permission, and OS-level crash containment.

This is the load-bearing control. A PDFium use-after-free or out-of-bounds read in the main process would share an address space with the SQLCipher key, the Keystore session, and decoded bytes from other passes. The same exploit in an isolated process compromises an empty sandbox that returns to the OS on crash. Walt observes a `RemoteException`, surfaces "could not render," and the wallet process keeps running.

`wpass-6m9` empirically verifies this isolation on at least one Pixel device and one OEM device before the service ships.

### D4. No extraction

The PDF is opaque from import to deletion. Walt does not extract text, metadata (`/Title`, `/Author`, XMP), form-field values, annotations, links, or attached files. The only operation Walt performs on a PDF is `PdfRenderer.openPage(N).render(bitmap, ...)`.

Every extraction codepath is an attack surface (`Launch`/`URI`/`SubmitForm` actions hide in annotations and bookmarks; XFA forms are arbitrary XML execution; embedded files are arbitrary payloads). Refusing to invoke those codepaths collapses the attack surface to "what the rasterizer touches," which is what the system renderer is hardened against and what the isolated process contains.

The renderer service's binder API exposes exactly two methods, `probe(fd)` and `render(fd, page, w, h)`. Adding any extraction method (`getText`, `getMetadata`, `getAnnotations`) is a security-policy change requiring an ADR amendment.

### D5. PDF digital signatures are not verified

Walt does not parse `/AcroForm/SigFlags`, does not validate PAdES/CMS signatures, does not surface a "signed" indicator.

PDF signature semantics are inconsistent in the wild (whole-document vs. partial-document signatures, certification vs. approval signatures, MDP transforms). There is no equivalent to Apple's WWDR root for ticket issuance. A "verified" UI signal that Walt cannot honestly back would dilute the PKPASS signature trust signal. Accepting all PDFs as `Provenance.UserProvided` is the honest position.

### D6. Encrypted PDFs are rejected at import

Password-protected PDFs are detected at the encryption-probe step in the renderer service and refused. Walt does not surface a password prompt.

A password prompt invoked from PDF-encryption metadata is a UI vector controlled by an untrusted file. A user habituated to typing passwords into Walt is one prompt-spoof exploit away from a real credential leak. The cost is that users with password-protected files must decrypt elsewhere first; this is a deliberate trade.

### D7. Hard caps: 25 MB, 10 pages, 4 MP per rendered bitmap, 5 s render timeout

Import-time validation rejects:

| Reject reason          | Threshold                                                  |
|------------------------|------------------------------------------------------------|
| `OversizedAtImport`    | size > 25 MB                                               |
| `NotAPdf`              | first 8 bytes not `%PDF-` with version in 1.0..2.0         |
| `Encrypted`            | renderer reports the encrypted flag                        |
| `TooManyPages`         | pageCount > 10                                             |
| `RendererFailed`       | page-0 smoke render fails or exceeds 5 s timeout           |

Per-page rendered bitmaps are bounded at 4 MP, mirroring `ImageRenderBounds.Default` for PKPASS images.

The 10-page ceiling sits well above legitimate ticket density (1-3 pages typical) and well below page-tree-bomb territory. The 5 s timeout caps resource exhaustion via deeply pathological PDFs; on timeout, the service is killed via `Process.killProcess(Process.myPid())` and the binder surfaces `RemoteException`.

The caps are defense-in-depth: storage layer re-checks `byteCount <= 25 MB` and `pageCount <= 10` on insert so a future caller bug cannot land an oversized blob.

### D8. No share-out

Documents in Walt have no "share" or "export" affordance. The bytes are one-way: file picker in, no UI path back to a sharing intent.

Every egress codepath is a potential exfiltration path if a future bug routes content through it implicitly. The user can re-obtain the original PDF from the source they imported it from. The forward-only flow simplifies the trust audit: "PDF bytes leave this app" is structurally false.

A `PublicApiSurfaceTest` enforces this by classpath-scanning `passes-ui` and `passes-pdf` for any construction of `Intent.ACTION_SEND` with a PDF MIME type.

## Module changes

A new `passes-pdf` Android Gradle module hosts the renderer service, the `PassDocument` model, and import-time validation. The module is self-contained for audit: a reviewer reads one module to see every byte of code that touches the PDF format.

`passes-storage` schema migrates v1 to v2: new tables `documents` and `document_thumbnails`, new `StorageTelemetryGuard` events `onDocumentImported` / `onDocumentRejected(kind)` / `onDocumentDeleted` mirroring the existing pass-event PII discipline (no `String` parameters, no labels in events). Migration is forward-only per ADR 0002.

`passes-ui` adds a `DocumentView` composable bound to the `passes-pdf` service over an internal `Binder`, plus the "Documents" list lane and a non-suppressible `DocumentTrustCaption` ("User-provided document. Walt has not verified the source.") in the same visual register as the existing "Self-signed" pill.

`passes-core` is unchanged.

walt-android (consumer-side, closed source) registers the `application/pdf` intent filter, widens the in-app file picker MIME filter to include PDF, and wires the `passes-pdf` and `passes-storage` modules through Hilt. Tracked separately as a cross-repo task in walt-android's beads database.

## Telemetry discipline

`DocumentTelemetryGuard` (in `passes-pdf`) and the new `StorageTelemetryGuard` events all accept exclusively enums, counts, and durations. No `String`, `CharSequence`, `ByteArray`, or `Map` parameters. This mirrors the load-bearing rule from the existing `passes-core/TelemetryGuard`: a future addition of a `String` parameter is a security-policy change requiring re-review.

A reflection-based `DocumentTelemetryGuardSurfaceTest` enforces this structurally.

## Consequences

- PDFs imported into Walt are strongly contained but minimally featured: no search, no auto-expiration, no labels beyond filename, no sharing. This matches v1 product intent. Future work that wants any of those features re-opens the threat model and amends this ADR.
- The isolated-process service adds approximately 200-400 ms of binder-call latency per first-render. Subsequent pages within a session are LRU-cached in main memory and avoid the round-trip.
- The 10-page ceiling will reject some legitimate documents (multi-leg flight itineraries, event-ticket booklets with vouchers). Rejection telemetry will be monitored; raising the ceiling is a future ADR amendment, not a code-only change.
- Users running Android versions older than 13 do not benefit from PDFium Mainline updates; PDFium fixes ship via full-OS updates only on those versions. The exposure window is documented in `wpass-6m9`. If the window is too wide, a feature-gate raising minSdk for PDF import (without raising it for the rest of Walt) is the fallback, recorded as an addendum to this ADR if invoked.

## Tests pinning the controls above

Each decision maps to at least one test:

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| D1       | `PublicApiSurfaceTest`: `PassDocument` is not assignable to `Pass` and shares no superclass.          |
| D2       | classpath-scan test: `passes-pdf` declares no dependency on `pdfbox-android`, `mupdf`, or similar.    |
| D3       | instrumented test: malformed PDF crashes the renderer service; main process survives; rebind succeeds.|
| D4       | reflection test: renderer binder exposes exactly `probe` and `render`; no extraction methods.         |
| D5       | reflection test: no public symbol named `verifySignature` / `signatureStatus` in `passes-pdf`.        |
| D6       | unit test: encrypted PDF probe returns `Rejected(Encrypted)`.                                         |
| D7       | unit tests at each cap boundary; watchdog test covers timeout-then-kill.                              |
| D8       | classpath-scan test: no `Intent.ACTION_SEND` construction in `passes-ui` or `passes-pdf`.             |

## Out of scope for this ADR

- PDF metadata-driven labels, OCR, or barcode extraction. Re-considering any of these requires re-opening the threat model.
- PDF digital signature display ("this PDF is signed by Foo Inc."). The format's signature semantics do not justify the chrome.
- Sharing PDFs out of Walt. See D8.
- Inter-document linking, search, or tagging. Documents are sorted by import date only.
