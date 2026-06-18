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

PDFium ships in the MediaProvider Mainline module on Android 14+ (API 34) and updates via Play System Updates, decoupling PDFium CVE response from Walt release cadence. (See [Addendum 2026-05-06](#addendum-2026-05-06-fd-transport--mainline-floor-refinement) for the threshold rationale and the resulting `Build.VERSION.SDK_INT >= 34` runtime gate.) The API is structurally narrow: open `ParcelFileDescriptor`, render page N to `Bitmap`. No JavaScript execution path. No `URI` / `Launch` / `SubmitForm` action processing during page render. No network. The PDF cannot paint outside the bitmap Walt provides.

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

The `passes-pdf-ui` module exposes a Compose-level facade, `rememberPdfThumbnail(...) -> PdfThumbnailState` (with sibling `PdfThumbnailCache`), so consumers that need an asynchronously-rendered page bitmap in a list row do not have to reach for the binder primitives directly. The facade preserves D3 — it does not bind its own service connection, it consumes a [PdfRendererBinder] supplied by the caller — and preserves D4 — its body invokes only `render(...)` and its return type [PdfThumbnailState] exposes a single `ImageBitmap` + aspect float (no metadata accessor, no annotation list, no field through which a future contributor could quietly thread an extraction surface). `PdfThumbnailSurfaceTest` reflection-locks the arm list, the per-arm field shape, and the absence of any `getText` / `getMetadata` / `getAnnotations` / `getAttachments` accessor on every public symbol the facade introduces. Adding a fourth arm to [PdfThumbnailState] or a non-trivial accessor to [PdfThumbnailCache] is a security-policy change in the same audit register as adding a binder method.

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

## Addendum 2026-05-06: FD transport + Mainline-floor refinement

Tracks: `wpass-0dq` (this addendum) under parent epic `wpass-i2r`. Source:
empirical findings F and G in `docs/research/pdf-renderer-validation.md`
(`wpass-6m9`).

The renderer-validation pass surfaced two architectural details that the
original ADR text under-specified. Both refinements are recorded here as
amendments to D3 and to the Consequences section, respectively. The rest
of the ADR stands as written.

### Amendment to D3: FD transport is `memfd_create` with write-seal

D3 mandates that the renderer runs in an isolated process and receives the
PDF bytes via `ParcelFileDescriptor` over binder. The original text did
not specify the *backing* of that FD. Empirical analysis showed the
obvious candidates fail Walt's invariants:

- `MemoryFile` exposes its FD only via reflection on a hidden API; the
  hidden-api list closes that path on API 28+.
- `SharedMemory.fileDescriptor` returns an ashmem FD that does not satisfy
  PDFium's `lseek` + `pread64` access pattern.
- `File.createTempFile` in `cacheDir` works but writes plaintext PDF
  bytes to disk for the lifetime of the render call, defeating the
  at-rest encryption guarantee that SQLCipher provides.
- `ParcelFileDescriptor.fromSocket` returns a non-seekable FD; PDFium
  rejects it.

D3 is therefore amended to specify **F.1: `memfd_create`-backed
`ParcelFileDescriptor`**. The main process allocates a memfd via
`syscall(__NR_memfd_create, name, MFD_CLOEXEC | MFD_ALLOW_SEALING)`,
populates it via `write(2)` of the SQLCipher-decrypted PDF bytes (no
writable `mmap` is ever taken in the main process), then issues
`fcntl(fd, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW |
F_SEAL_SHRINK)` before adopting the int FD into a
`ParcelFileDescriptor` and transferring it to the renderer service over
binder.

The seal sequence is load-bearing and the order matters:

- `F_SEAL_WRITE` is what makes the buffer read-only across **future**
  mappings. A `mmap(PROT_WRITE)` against the FD from the renderer side
  fails with `EPERM`; a `write(2)` against the FD fails with `EPERM`.
  This is what protects against a hypothetical PDFium write-via-mmap
  primitive in the renderer mutating bytes the main process still
  holds.
- `F_SEAL_GROW` / `F_SEAL_SHRINK` pin the size, so a renderer compromise
  cannot resize the backing buffer underneath the main process.
- `F_SEAL_SEAL` prevents future code from removing or weakening any of
  the above. Without it, a contributor could land a patch that drops
  the write-seal and the existing surface-area pin tests would not
  catch the regression.

The reason `write(2)` rather than mmap-then-write-then-munmap is that
`F_ADD_SEALS` does **not** invalidate writable mappings that already
exist; it only blocks new ones. Using `write(2)` means no writable
mapping ever exists in either process, so the "buffer is read-only by
the time the FD crosses binder" invariant holds without depending on
a `munmap` ordering between two processes.

The implementation lives in `passes-pdf` as a small `MemFdAllocator`
Kotlin class plus one JNI source file
(`passes-pdf/src/main/cpp/memfd.cpp`, ~30 lines). `passes-pdf` thereby
gains its first JNI surface; the audit story stays contained because the
surface is a single function with documented arguments and no buffer
handling. No plaintext PDF reaches the filesystem at any point.

The renderer service binder API stays at exactly two methods (`probe`,
`render`). The transport choice does not widen the binder surface that D4
locks down.

A `MemFdAllocatorJniSurfaceTest` pins the constraint that `passes-pdf`
declares exactly one native method. Adding a second is a security-policy
change requiring a follow-up addendum.

### Amendment to Consequences: Mainline floor is API 34, not API 13

The original Consequences text stated: "Users running Android versions
older than 13 do not benefit from PDFium Mainline updates; PDFium fixes
ship via full-OS updates only on those versions." That threshold is
wrong.

Source-level analysis of `packages/providers/MediaProvider/pdf/`
established that the Mainline-backed PDFium binary is reachable on
**API 34 and above** via the legacy `android.graphics.pdf.PdfRenderer`
class. On API 35 the platform additionally exposes `PdfRendererPreV`,
which is also Mainline-backed but raises the call-site requirement to
API 35; using it does not buy back user reach. On **API 30-33 there is
no path that exposes a Mainline-backed PDFium**: those releases predate
the MediaProvider apex, so `libpdfium.so` is the platform-bundled copy
and updates only via full-OS security patches.

The Consequences section is therefore amended by replacing "older than
13" with "older than 14 (API 34)" and by adopting **G.1: PDF import is
feature-gated on `Build.VERSION.SDK_INT >= 34`**.

The Walt app's overall `minSdk` does not change. The PDF feature gate is
a runtime check at the single import entry point in `passes-pdf`. Users
on API 26-33 see the existing PKPASS import path and an explicit "Android
14 or newer required" message when attempting PDF import. This guarantees
that every PDF Walt renders goes through a Mainline-updated PDFium with
current CVE coverage, which was the security argument for system-renderer-only
in the first place. Accepting a stale-PDFium tail on older devices would
have weakened that argument; G.1 closes the exposure window instead.

`passes-pdf`'s `minSdk` follows the project default. The API 34
requirement is enforced as a runtime gate, not a manifest floor. F.1's
`memfd_create` is reachable below API 34 either through the bionic
libc wrapper (API 30+) or, on devices below API 30 that nevertheless
ship a Linux kernel ≥3.17, through a raw `syscall(__NR_memfd_create,
…)` call. Walt's implementation calls `syscall()` directly so the
floor is the kernel, not bionic; vendor kernels are not strictly bound
to API level, but the project's `minSdk` is high enough that no
shipping device fails the kernel check in practice. F.1 and G.1 are
therefore independent invariants that do not interact on `minSdk`.

### Tests pinning the amendments

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| D3 (F.1) | `MemFdAllocatorJniSurfaceTest`: `passes-pdf` declares exactly one native method (`memfd_create` shim).|
| D3 (F.1) | classpath-scan test: no `java.io.File` / `java.io.FileOutputStream` / `java.io.RandomAccessFile` / `java.nio.file.Files` write APIs inside `passes-pdf`'s renderer-handoff code path. The handoff path is structurally forbidden from touching the filesystem. |
| Consequences (G.1) | unit test: import entry point on `Build.VERSION.SDK_INT < 34` returns the "Android 14 or newer required" rejection without invoking the renderer service. |

## Addendum 2026-05-06: model name, version-range wording, module split

Editorial reconciliation, not a substantive policy change. PR #34 review
flagged three drift points between this ADR and the implementation in
`wpass-jsb`. The implementation names are the correct ones; the ADR text
is amended here.

### Model name: `PassDocument` -> `PdfDocument`

D1, the Module changes section, and the D1 row of the test table refer to
the model class as `PassDocument`. The implementation calls it
`PdfDocument`. The implementation name is preferred because it removes
naming collision with `Pass` (which would be a hazard the moment a
reviewer skim-reads `PassDocument` and assumes a subclass relationship)
and because it is the audit-readable name for "the PDF model."

Read every occurrence of `PassDocument` in this ADR as `PdfDocument`. The
sibling-of-`Pass` argument under D1 stands unchanged: `PdfDocument` and
`Pass` share no superclass and are not assignable to each other. The
trust-shape distinction is the same.

### Header version range: `1.0..2.0` -> `1.x or 2.x`

The D7 reject-reason table specifies `NotAPdf` as "first 8 bytes not
`%PDF-` with version in 1.0..2.0". The implementation accepts any minor
digit in the major-1 and major-2 ranges (`%PDF-1.0` through `%PDF-1.9`
and `%PDF-2.0` through `%PDF-2.9`). The implementation behavior is
correct: clamping the minor to a closed list would fail-stop on
legitimate future PDF specifications without buying any security
property, since the renderer is hardened for the entire 1.x/2.x lineage
the system PDFium supports.

Read the D7 row as: "first 8 bytes not `%PDF-X.Y` where `X` is `1` or `2`
and `Y` is any ASCII digit."

### Module split: `passes-pdf-core` (pure Kotlin) and `passes-pdf` (Android)

The "Module changes" section described "a new `passes-pdf` Android Gradle
module" hosting the renderer service, the `PdfDocument` model, and
import-time validation. PR #34 split this single module into two, matching
the existing `passes-core` / `passes-storage` precedent in the project:

- **`passes-pdf-core`** — pure Kotlin/JVM. Hosts `PdfDocument`,
  `PdfImportConfig`, `PdfImportResult`, `DocumentRejectedKind`,
  `Provenance`, `DocumentTelemetryGuard`, and the `isPdfHeader` magic-byte
  gate. No Android framework dependencies. Audit-readable in isolation.
- **`passes-pdf`** — Android. Hosts the `android:isolatedProcess`
  renderer service, the `MemFdAllocator` plus `memfd.cpp` JNI shim, the
  binder API, and the import-time validation that needs Android types.

The split moves the single-source-of-truth contract types out of an
Android module so they can be reasoned about, tested, and audited without
the Android Gradle Plugin in the loop, the same way `passes-core` is.

Read the references in this ADR as follows:

- "`PdfDocument` model" / "`DocumentTelemetryGuard`" / "import-time
  validation" / "`PublicApiSurfaceTest`" → **`passes-pdf-core`**.
- "renderer service" / "`MemFdAllocator`" / "`memfd.cpp`" / "binder API"
  / "`MemFdAllocatorJniSurfaceTest`" / "G.1 runtime API-34 gate" →
  **`passes-pdf`**.

Tests that scan a module's classpath or symbols (D2's third-party-PDF-lib
exclusion, D5's no-`verifySignature` symbol, D8's no-`ACTION_SEND`
construction) apply to **both** modules. The exclusions only hold if
neither module hosts the forbidden surface.

The Android-side renderer module remains tracked under `wpass-5v9`. Its
manifest, JNI source, and binder code land there.

## Addendum 2026-05-15: Pinch-zoom + pan in `DocumentView`

**Superseded by the 2026-05-15 follow-on addendum below ("Zoom moves to a
dedicated full-screen surface"). The follow-on retains Z.2, Z.3, and Z.5
verbatim and replaces Z.1, Z.4, and Z.6.** The text below is retained for
audit continuity; consult the follow-on for current behaviour.

Tracks: `wpass-6ag` (parent), `wpass-1wq` (UI gesture surface), `wpass-f4b`
(renderer zoom-aware path), `wpass-0nn` (this addendum). Cross-repo
consumer-side tracker: walt-android `wlt-o72.4`. Storage precondition
verified by walt-android `wlt-o72.3` (imported PDF bytes are persisted at
full resolution, no re-encode or downscale).

### Context

The original Consequences section framed `DocumentView` as "minimally
featured", and `DocumentView` shipped as a `HorizontalPager` of
`ContentScale.Fit`-letterboxed pages with no zoom and no pan. In
practice, the #1 reason a user imports a PDF into the wallet is to
scan an embedded barcode (PDF417 / QR / EAN) at a venue or transit
gate. A fit-resolution rasterised page does not enlarge a typical
barcode enough for a scanner to read it, so the feature as originally
shipped fails its primary use case.

This addendum records pinch-to-zoom and single-touch pan-when-zoomed as
explicit features of `DocumentView`, and reconciles them with the trust
contracts in D4, D5, D7, and D8.

### Z.1 Pinch-zoom and pan are features; gesture priority interlocks with the pager

Pinch-to-zoom (two-finger) and pan-when-zoomed (single-finger, only when
`scale > 1`) are part of `DocumentView`'s gesture surface. Implementation
constants live in `passes-pdf-ui/.../DocumentView.kt`:

- `MIN_SCALE = 1f` (zooming out below fit is meaningless on a
  single-page surface).
- `MAX_SCALE = 3f` (interim ceiling against the current fit-resolution
  bitmap — at 5x, each source pixel becomes a 5x5 bilinear block and
  the smearing crosses into "bigger but less scannable" territory for
  phone-camera barcode decoders. Moves to 5f once `wpass-f4b` lands the
  viewport-aware renderer so the upsampling cost disappears).
- `DOUBLE_TAP_SCALE = 2f` (double-tap toggle target).

Gesture priority with the enclosing `HorizontalPager` is the
load-bearing UX interlock:

- Two-finger pinch is always consumed by the page-scoped zoom surface.
  It never advances the pager.
- Single-touch drag is consumed by the zoom surface only when
  `scale > MIN_SCALE`. At fit, single-touch horizontal drag passes
  through to the pager — page-swipe behaviour is unchanged from before
  the addendum.
- Translation is clamped so the scaled page cannot be panned entirely
  off the slot. The user cannot lose the barcode by overshooting.

Zoom state is per-page and resets on page change (the
`remember(document.id, pageIndex)` frame in the rendering composable
owns it). Paging away from a zoomed-in barcode and back returns the
page to fit — no cross-page zoom persistence, which keeps the swipe
gesture's mental model clean.

### Z.2 The 4 MP per-bitmap cap (D7) stands unchanged

`PdfRendererService.MAX_PIXELS = 4 MP` is **not** raised by this
addendum. The cap is defense-in-depth against
decompression-bomb-style memory abuse; raising it would be a
separate security-policy change requiring its own addendum.

Both candidate renderer designs under evaluation in `wpass-f4b` (tiled
rendering; viewport re-render) fit within the cap:

- Tiled rendering caps **per-tile** at <= 4 MP. Total tile RAM is
  bounded by how many tiles are visible in the zoomed viewport, not
  by the zoom factor.
- Viewport re-render caps the **single** bitmap at <= 4 MP. One
  bitmap per zoom level; the visible document-rect is what gets
  rasterised at viewport resolution.

The gesture surface (Z.1) is correct against the existing
fit-resolution bitmap — pixelated when zoomed past about 1.3-1.5x, but
functionally correct. The renderer change in `wpass-f4b` is what
delivers sharp-at-zoom; it can land independently as a follow-up.

### Z.3 The binder API surface does not widen

`PdfRendererBinder` continues to expose exactly two methods: `probe`
and `render`. The renderer zoom-aware path (`wpass-f4b`) extends
`render`'s parameter shape with a sub-rect / viewport indicator (see
that bead for the exact wire shape), but does not add a third
transaction. D4's no-extraction guarantee is therefore preserved:
`PublicApiSurfaceTest`'s reflection check still passes after
`wpass-f4b` lands.

### Z.4 The non-suppressible trust caption (D5) stays docked above the pager

This is the explicit decision the addendum is being filed to record:
**the trust caption ("User-provided document. Walt has not verified
the source.") stays docked above the pager and is NOT subject to the
zoom transform.** It cannot be panned off-screen, and it remains
visible at every zoom level.

Two implementation options were considered:

- (a) Caption stays in its row in `DocumentView`'s `Column`, above the
  pager. The zoom transform applies inside the pager slot only.
- (b) Caption pans / scales with the page. Could be scrolled off
  screen if the user pans up while zoomed.

Option (a) is adopted. The reasoning:

- D5's "non-suppressible" promise reads naturally as "the user can
  always see it." Option (b) makes the caption *suppressible by pan*
  in practice — a user zoomed in on the lower half of a page would
  not see the caption, which dilutes the trust signal.
- Option (a) is what the current `DocumentView` layout already does:
  the caption is a sibling of the pager in a `Column`, and the zoom
  transform is structurally scoped to the page slot inside the
  pager. No layout change is needed for the addendum.
- The cost of option (a) is zero: caption never interferes with the
  gesture surface, and the gesture surface never interferes with the
  caption.

### Z.5 No share-out path is introduced (D8 unchanged)

Zoom is a view-side affordance. No new code path moves PDF bytes off
device or into a sharing intent. D8's classpath-scan test
(`Intent.ACTION_SEND` is forbidden in `passes-ui`, `passes-pdf-ui`,
and `passes-pdf`) continues to pass.

### Z.6 Consumer-side integration is the host's responsibility

walt-android embeds `DocumentView` inside a vertical scroll container
on the document-detail screen. Now that `DocumentView` consumes
single-touch drags when zoomed, the host screen will need to defer to
the embedded gesture (nested-scroll or zoom-state-gated suppression).
Tracked on the consumer side as walt-android `wlt-o72.4`. This
addendum does not commit `passes-pdf-ui` to a nested-scroll API; the
gesture surface is self-contained at scale > 1 and the integration
contract is documented in the bead.

### Tests pinning the addendum

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| Z.1      | `DocumentViewInstrumentedTest.pinchToZoomDoesNotAdvanceThePagerAndKeepsTheTrustCaptionVisible`        |
| Z.1      | `DocumentViewInstrumentedTest.singleTouchHorizontalDragAtFitScaleStillAdvancesThePager`               |
| Z.1      | `DocumentViewInstrumentedTest.singleTouchHorizontalDragWhileZoomedDoesNotAdvanceThePager`             |
| Z.1      | `DocumentViewInstrumentedTest.doubleTapOnThePageDoesNotAdvanceThePager`                               |
| Z.2      | existing `PdfRendererService.MAX_PIXELS` pin in renderer service code — value unchanged.              |
| Z.3      | existing `PublicApiSurfaceTest` reflection check on `PdfRendererBinder` — still exactly two methods.  |
| Z.4      | `DocumentViewInstrumentedTest.pinchToZoomDoesNotAdvanceThePagerAndKeepsTheTrustCaptionVisible` asserts caption visibility post-pinch; existing `trustCaptionDoesNotOverlapThePageWhenTheConsumerGivesAShortSlot` pins the structural layout boundary. |
| Z.5      | existing classpath-scan test for `Intent.ACTION_SEND` — surface unchanged.                            |

## Addendum 2026-05-15: Zoom moves to a dedicated full-screen surface

Tracks: `wpass-6ag` (parent), `wpass-jil` (full-screen surface + banner),
`wpass-ny4` (inline pinch removal), `wpass-f4b` (renderer sub-rect path),
`wpass-5n1` (this addendum). Supersedes the immediately-preceding
`wpass-0nn` addendum.

### Context

The `wpass-0nn` addendum landed pinch-zoom and pan inside the inline
`DocumentView`. Practical use surfaced two problems:

- Mounting zoom in the same surface that the host app embeds inside a
  vertical scroll forces a nested-gesture interlock at every embedding
  site, with `scale > 1` as the toggle. The contract is correct but
  fragile — a host that forgets to consume vertical drags appropriately
  produces a pager that fights the parent scroll.
- The inline surface is sized for browsing (a tile-shaped 360x480dp
  slot). The zoom-to-scan use case wants the page to take the whole
  screen; inline zoom against a small slot still leaves the barcode
  smaller than the user wants even at `MAX_SCALE`.

The pivot: inline `DocumentView` returns to a fixed 1x display. A
dedicated full-screen detail surface in `passes-pdf-ui` hosts pinch-zoom
and pan, entered from a "Tap for full screen" banner docked inside the
inline `DocumentView`.

This addendum records the resulting decisions and reconciles them with
D4, D5, D7, and D8. Z.2, Z.3, and Z.5 from the prior addendum are
retained verbatim; Z.1, Z.4, and Z.6 are replaced.

### Z.7 Zoom lives in a full-screen detail surface only; inline `DocumentView` is fixed 1x

The inline `DocumentView` carries no zoom, no pan, and no double-tap
toggle. The pager-swipe gesture inside it is unchanged from the
pre-`wpass-0nn` baseline: single-touch horizontal drag advances the
pager, two-finger gestures are ignored by the surface, and the page is
drawn at fit resolution into the slot the host gives it.

Pinch-zoom (two-finger) and pan-when-zoomed (single-finger when
`scale > 1`) live in a new full-screen detail surface in
`passes-pdf-ui`. The surface is entered from a "Tap for full screen"
banner docked at the bottom of the inline `DocumentView`, above the
trust caption. The banner is themed via `DocumentSemantics`
/ `DocumentTheme` — walt-android supplies the label string and the
visual tokens; `passes-pdf-ui` ships the structural slot.

The full-screen surface owns the same gesture-priority interlock the
prior addendum specified, now inside its own root rather than inside an
embedded pager:

- Two-finger pinch always drives zoom.
- Single-touch drag drives pan only when `scale > MIN_SCALE`. At fit,
  single-touch horizontal drag advances the in-surface pager (if more
  than one page) or is a no-op.
- Translation is clamped so the scaled page cannot be panned entirely
  off the slot.

Zoom state is per-page and resets on page change inside the full-screen
surface. Closing the full-screen surface and reopening it returns the
page to fit — no cross-session zoom persistence. Exiting the surface
(back gesture or close affordance) returns the user to the inline
`DocumentView` in the document-detail screen.

The implementation reuses the gesture surface from `wpass-1wq`,
relocated to the full-screen composable per `wpass-ny4`. No duplicate
gesture implementation exists across surfaces.

### Z.8 Trust caption (D5) remains non-suppressible in full-screen and docks to a screen edge

The trust caption ("User-provided document. Walt has not verified the
source.") is rendered on the full-screen surface and is NOT subject to
the zoom transform. It docks to a screen edge — concretely, an edge of
the full-screen root, outside the pannable / zoomable page region — and
stays visible regardless of pan position or zoom factor.

This is the decisive choice between the two options the bead flagged:

- (a) Caption docks to a screen edge and stays visible regardless of
  pan/zoom. Adopted.
- (b) Caption pans/zooms with the page and a duplicate docked
  indicator stays visible. Rejected.

Option (a) is adopted because D5's "non-suppressible" reads naturally
as "the user can always see it." Option (b) would make the in-page
caption suppressible by pan in practice, and adds a second copy that
itself becomes a new audit surface for the trust signal. The cost of
(a) is zero: caption never interferes with the gesture surface and
vice versa. This is the same structural argument the prior addendum
used for Z.4 in the inline case, now applied to the full-screen root.

Inline `DocumentView` continues to render the trust caption in its
existing layout slot above the pager (now with the full-screen banner
between caption and pager region). Inline behaviour is unchanged
from before `wpass-0nn`.

### Z.9 Consumer-side integration is simpler than under `wpass-0nn`

Because inline `DocumentView` no longer consumes single-touch drags at
any zoom level, the nested-scroll interlock that `wpass-0nn`'s Z.6
made the host responsible for goes away on the inline surface. The
host embeds `DocumentView` inside a vertical scroll exactly as before
the gesture work landed.

The full-screen surface takes the whole screen and is outside any host
scroll container, so the gesture surface there is self-contained by
construction. No nested-gesture API is committed to `passes-pdf-ui` by
this addendum.

Cross-repo: walt-android `wlt-o72.4` covers the consumer-side wiring
(launching the full-screen surface from `DocumentDetailScreen.kt`,
back-navigation handling). The wiring is a navigation hop only, not a
gesture contract.

### Z.2, Z.3, Z.5 retained from the prior addendum

The 4 MP per-bitmap cap (Z.2), the no-binder-widening guarantee (Z.3),
and the no-share-out rule (Z.5) are unchanged. The sub-rect / viewport
re-render path in `wpass-f4b` extends `render`'s parameter shape in
place; it remains a single transaction. The full-screen surface
consumes the same `PdfRendererBinder.render` call site shape that the
inline surface used.

### Tests pinning the addendum

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| Z.7      | Compose test: inline `DocumentView` does not respond to pinch gestures (regression after `wpass-ny4`).|
| Z.7      | Compose test: tapping the full-screen banner inside `DocumentView` navigates to the full-screen surface. |
| Z.7      | Compose test: the full-screen surface drives a zoom-aware re-render call on pinch (mock binder).      |
| Z.8      | Compose test: trust caption is visible on the full-screen surface at fit, after zoom, and after pan.  |
| Z.8      | Existing inline `DocumentTrustSurfaceTest` continues to pin caption visibility on the inline surface. |
| Z.2      | existing `PdfRendererService.MAX_PIXELS` pin — value unchanged.                                       |
| Z.3      | existing `PublicApiSurfaceTest` reflection check on `PdfRendererBinder` — still exactly two methods.  |
| Z.5      | existing classpath-scan test for `Intent.ACTION_SEND` — surface unchanged.                            |

## Addendum 2026-06-16: Documents generalize to PDF + image (`Document = PDF | image`)

Tracks: parent epic `wpass-i9x` (generalize Document to cover images); steps
`wpass-gnp` (bounded `decodeBounded` primitive), `wpass-gyn` (sealed `Document` +
`DocumentView` dispatcher, PDF-only arm), `wpass-6yp` (isolated image-decode
service), `wpass-bsf` (this addendum — the image arm end-to-end). Cross-repo
consumer wiring: walt-android `wlt-4pg`.

### Context

The original ADR scoped "documents" to PDFs. The same trust posture — a
user-supplied file Walt renders but never signature-verifies, behind a
non-suppressible caption, in an isolated process, with no share-out — applies
verbatim to still images (PNG / JPEG / WebP). Users receive event tickets and
transit passes as screenshots and photos at least as often as PDFs. Rather than
stand up a parallel "image" concept, the document model is generalized so an
image is a second kind of `Document`, reusing the storage, trust-caption, and
display machinery the PDF arm already established.

This addendum blesses that generalization. D1's "sibling of `Pass`" framing, the
isolation posture (D2/D3), no-extraction (D4), no-signature-verification (D5),
the hard caps as defense-in-depth (D7), and no-share-out (D8) all extend to the
image arm unchanged. The decisions below record only what is **new or different**
for images.

### I1. `Document` is a sealed type with two arms: `PdfDocument | ImageDocument`

`passes-pdf-core` promotes `Document` to the sealed supertype (it already held the
common fields). `PdfDocument` keeps `pageCount`; the new `ImageDocument` carries
`widthPx` / `heightPx` instead — the kind-specific field lives on its arm, never on
the supertype. `ImageDocument` shares no superclass with `Pass` (D1 unchanged) and
has no `SignatureStatus` (D5 unchanged). The dimensions are the bounded raster Walt
decoded **inside the sandbox**, never derived from an in-process decode of the
untrusted source bytes, and never upscaled beyond source. `ImageDocument` carries
no container format — that is a persistence detail, kept off the model.

### I2. The image reject taxonomy is NOT merged into `DocumentRejectedKind`

Images fail in different ways than PDFs (a PDF is `Encrypted` or has `TooManyPages`;
an image is `NotAnImage` or a `DimensionsTooLarge` decompression bomb). Folding both
into one enum would force every consumer to branch on arms that cannot occur for its
kind. The image taxonomy is `passes-image`'s existing `ImageDecodeRejectedKind`
(`NotAnImage` / `OversizedAtImport` / `DimensionsTooLarge` / `DecodeFailed` /
`DecoderUnavailable`), reused verbatim. There is **no new "document reject" enum**:
the importer-facing result (`DocumentImportResult`, in `passes-document`) carries the
PDF taxonomy on its `PdfRejected` arm and the image taxonomy on its `ImageRejected`
arm, plus two kind-agnostic arms (`Unrecognized`, `StorageHandoffFailed`).

### I3. A new `passes-document` module owns the sniff-and-branch orchestration

`DocumentImporter` magic-byte-sniffs PDF vs image and branches to the right isolated
backend: `passes-pdf`'s renderer service for PDFs, `passes-image`'s decode sandbox
for images. It is a new Android module that sits ABOVE both backends (the way
`passes-pdf-ui` sits above `passes-pdf`) and is the **single** place the two
otherwise-independent peers meet — a peer-to-peer `passes-pdf → passes-image` edge is
deliberately avoided. The module declares no service of its own; every byte of
decode/render work still happens inside the isolated backends. Per the DECISIVE
CONSTRAINT, this orchestration lives here, not reassembled in walt-android.

The importer reads the source ONCE into a bounded buffer, sniffs, then materializes
the bytes into a single sealed `memfd` PFD (via `passes-isolation`) handed to the
winning backend — so a one-shot fd source is never read twice (no offset corruption),
and the original bytes are what get persisted ("persist original").

### I4. Storage: `documents` schema v5 → v6 (forward-only)

The `documents` table gains a `format` discriminator column
(`'pdf' / 'png' / 'jpeg' / 'webp'`, `NOT NULL DEFAULT 'pdf'` so existing PDF rows
migrate untouched) and nullable `width_px` / `height_px`. The `pdf_bytes` BLOB column
is reused verbatim to hold the original bytes of either kind — renaming it would force
a table rewrite for no audit gain, and `loadDocumentBytes` / `loadDocumentThumbnail`
are already kind-agnostic. `insertDocument` takes a sealed `DocumentInsert`
(`Pdf(pageCount)` | `Image(format, widthPx, heightPx)`). The D7 size and label caps
apply to both kinds; the page cap is PDF-only (an image is a single page). Migration is
forward-only per ADR 0002.

### I5. The image arm reuses the trust caption and `DocumentView` verbatim; full-screen is out of scope

`DocumentView` dispatches on the sealed `Document`: the PDF arm is the existing
swipeable pager; the image arm is a single, fixed-fit, no-pager image decoded once in
the sandbox. The non-suppressible `DocumentTrustCaption` (D5) is composed inside BOTH
arms from the same verbatim composable — there is no parameter on `DocumentView` that
can hide it. The dispatcher gains the kind-specific `imageFile` / `imageDecoder`
backend pair alongside the existing `pdfFile` / `renderer` pair (its user-visible
parameter count moves 7 → 9; `DocumentSurfaceLockTest` is updated deliberately, as it
was for the wpass-emn slot). `passes-pdf-ui → passes-image` is the reserved edge this
step lands. Zoom / full-screen for images is intentionally NOT in this step: the
PDF-only `FullScreenDocumentView` is unchanged; the inline fixed-1x image is the
presentation shipped here. D8 (no share-out) holds — the `passes-pdf-ui` bytecode scan
for `Intent.ACTION_SEND` / `application/pdf` stays green across both arms.

### Tests pinning this addendum

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| I1       | `passes-pdf-core` `PublicApiSurfaceTest`: `Document` has exactly the `PdfDocument` + `ImageDocument` arms; `ImageDocument` shape is exercised. |
| I2       | `DocumentImportResult` reuses `ImageDecodeRejectedKind` verbatim; `passes-document` `DocumentImporterTest` maps each backend outcome onto the right arm. |
| I3       | `DocumentImporterTest`: PDF magic routes to the PDF backend, image magic to the image backend, unrecognized bytes touch neither; original bytes reach `persist`. |
| I4       | `passes-storage` `SchemaMigrationTest.migrationFromV5AddsDocumentFormatAndDimensionColumns` + `...DefaultsExistingDocumentsToPdfFormat...`; `DocumentRepositoryTest` image-insert tests. |
| I5       | `DocumentSurfaceLockTest` (9-param lock + `ImageDecodeBinder`-interface lock); `DocumentTrustSurfaceTest.imageDocumentViewRendersTheNonSuppressibleTrustCaption`; existing `application/pdf` / `ACTION_SEND` bytecode scan. |

## Addendum 2026-06-17: Composite artifact — `Document = PDF | image | barcoded-image`

Tracks: `wpass-8lu` (first-class composite artifact: image + extracted/generated
barcode, one wallet item). Consumer-requested by walt-android epic `wlt-yjn5`
(add-to-wallet UX rethink); blocks consumer `wlt-u3tk → wlt-2ub2`. Follow-on to the
2026-06-16 image addendum (`wpass-i9x`). Module names below are the post-`wpass-xmp`
names (`passes-document-core` / `passes-document-ui`, formerly `passes-pdf-core` /
`passes-pdf-ui`).

### Context

A common add-flow input is a photographed or screenshotted membership / loyalty card
that *carries a barcode* — the user wants both the picture (to recognise the card) and
a crisp, re-renderable native barcode (to scan at a terminal). The image-only arm
(`wpass-i9x`) covers the barcode-*less* case; a separate `ScannableCard` (ADR 0001)
covers the barcode-*only* case. The composite is the union: ONE artifact carrying an
image AND a barcode extracted from that image. Rather than a fourth artifact tower or a
host-side join of two rows, it is a **third `Document` arm** that is a strict superset
of `ImageDocument`, so it reuses the image isolation, storage, trust-caption, and
display substrate verbatim and degrades to a plain `ImageDocument` when no code is
found. The decisions below record only what is **new or different** for the composite.

### C1. `Document` gains a third arm: `BarcodedImageDocument`

`passes-document-core` adds `BarcodedImageDocument` (with `BarcodedImageDocumentId`)
alongside `PdfDocument` and `ImageDocument`. It carries every `ImageDocument` field
(`widthPx` / `heightPx`, original bytes persisted) PLUS `barcodePayload: String` and
`barcodeFormat: ScannableFormat`. It shares no superclass with `Pass` (D1 unchanged)
and has no `SignatureStatus` (D5 unchanged — a barcode extracted from a user image is
not a verified credential). The arm exists **only when a code was actually found and
(consumer-side) confirmed**; an image with no barcode stays an `ImageDocument`, never a
`BarcodedImageDocument` with an empty payload. This forces a deliberate, security-
reviewed edit to `passes-document-core`'s `PublicApiSurfaceTest` arm-set lock, in the
same audit register as adding a binder method. `passes-document-core` takes a new
pure-JVM `api` edge to `passes-core` for `ScannableFormat` (mirrors
`passes-barcode-core → passes-core`; adds no Android dependency).

### C2. Barcode extraction runs in the isolated worker — the load-bearing constraint

The user-imported image is the hostile-input surface (the same `CVE-2023-4863`
libwebp / codec-RCE class D3 and `wpass-i9x` exist to contain). Extracting a barcode
from it means decoding the image and running a symbol reader over the pixels — both
MUST happen in the permissionless sandbox, never in the host process. The importer
reuses `passes-barcode`'s existing isolated `BarcodeImageDecoder` (the static-image
posture, `wpass-zrt`): the original bytes are decoded in-sandbox and **only**
`{payload, ScannableFormat}` (the pure `BarcodeDecodeResult` from `passes-core`) crosses
the binder — never a `Bitmap`, never the source bytes. No `BitmapFactory` / `ImageDecoder`
/ ZXing call touches user-image bytes in the host process. This is the same invariant
that gates `wpass-i9x` acceptance, extended to the barcode read.

The composite import runs **two** isolated decodes of the same bytes — the image-decode
sandbox (for the display raster / thumbnail / dimensions) and the barcode-decode sandbox
(for the symbol) — each on its **own** `memfd` PFD materialized from the single bounded
read. The source fd is still read exactly once (no offset corruption); holding the
compressed bytes in a host `ByteArray` is not a decode and is not the RCE surface.

### C3. Confirm-before-persist; first code wins; graceful degradation

A misread barcode that only surfaces at a checkout terminal is a real failure mode.
`DocumentImporter.import` therefore gains a `confirmBarcode: (suspend (payload, format) ->
Boolean)?` hook, invoked with the decoded value **before anything is persisted**, so the
consumer can gate on its `BarcodeCreateConfirmSheet` (the decoded payload is the value
shown for verification). Returning `true` persists a composite; returning `false`
degrades to a plain image. The hook is **`null` by default, and that default means the
composite path is opt-in: with no hook the isolated barcode extraction does NOT run at
all** — the import is a plain image at zero extra cost, identical to the wpass-i9x
behavior, and an incidental barcode in a photo the user only wanted to store is never
silently turned into a composite. A consumer wanting composites without a confirm UX can
pass `{ _, _ -> true }` explicitly. When (with the hook supplied) an image yields no code,
the extraction fails, or confirmation is declined, the import lands on `ImportedImage`
(plain `Document.Image`) — extraction failure NEVER fails the whole import. When an
image contains several codes, the kernel returns the **first** detected code (consumer
decision, `wlt-yjn5`; ZXing's `MultiFormatReader` stops at the first match). No content
beyond the barcode is extracted (D4 holds: no EXIF / XMP / payload-derived label).

### C4. Storage: `documents` schema v6 → v7 (forward-only)

The `documents` table gains nullable `barcode_payload TEXT` / `barcode_format TEXT`
columns. A row is a composite **iff** both are non-null; the `format` column stays the
image container format (`'png' / 'jpeg' / 'webp'`), so a composite reads back as an
image row that additionally carries a barcode — there is **no** new `format` value and
**no** third reject enum. `barcode_format` stores the `ScannableFormat` name, matching
the `scannable_cards.format` vocabulary; an unrecognised name (DB tampering only — this
module is the sole writer) decodes to "no barcode" rather than throwing on a list query.
`insertDocument`'s sealed `DocumentInsert` gains a `BarcodedImage` arm. The D7 size and
label caps apply; the page cap does not (a composite is a single-page image). The barcode
is carried on the SAME row as its image — one artifact, one wallet entry, never a
host-side join. Migration is forward-only per ADR 0002; existing rows read back NULL.

### C5. The composite reuses `DocumentView` for its image half; the barcode UI is consumer-composed

`DocumentView` dispatches `BarcodedImageDocument` to the **same** `ImageDocumentView` as
a plain image, over the **same** `imageFile` / `imageDecoder` backend pair — so the
dispatcher gains NO new parameter and the `DocumentSurfaceLockTest` 9-param shape lock
stays green. The non-suppressible `DocumentTrustCaption` (D5) is therefore present
unchanged. The generated barcode and its format switcher are **not** rendered here: they
are composed by the consumer with `passes-ui`'s `ScannableCardView` + `passes-core`'s
`BarcodeEncoder.encode(payload, format)`, which re-encodes the one stored payload across
symbologies. This keeps the two UI towers (`passes-ui` for PKPASS/scannable surfaces,
`passes-document-ui` for document surfaces) independent — the composite is the one place
they are *combined*, and that combination lives in the consumer, not in a new kernel
cross-tower edge. D8 (no share-out) holds.

### Telemetry discipline (unchanged, reaffirmed)

The composite import reuses the image `onImportSucceeded` event verbatim — byte count /
format / dimensions only. The decoded barcode **payload is never written to telemetry**;
a composite import is indistinguishable from a plain image import in the telemetry stream.

### Tests pinning this addendum

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| C1       | `passes-document-core` `PublicApiSurfaceTest`: `Document` has exactly `PdfDocument` + `ImageDocument` + `BarcodedImageDocument`; the composite shape is exercised. |
| C2       | `passes-document` `DocumentImporterTest.imageWithBarcodeRoutesToCompositeAndPersistsPayloadAndFormat` (the barcode seam sees the same ORIGINAL bytes; only `{payload, format}` cross); on-device `wpass-k9t`. |
| C3       | `DocumentImporterTest`: no-code / extraction-failure / declined-confirmation all degrade to `ImportedImage`; `confirmHookSeesTheDecodedPayloadBeforePersist`; `pdfWithEmbeddedBytesNeverRunsBarcodeExtraction`. |
| C4       | `passes-storage` `SchemaMigrationTest` (v6→v7 barcode columns, version/DDL/migration-keys locks in `PublicApiSurfaceTest`); `DocumentRepositoryTest` composite round-trip + plain-image-null-barcode tests. |
| C5       | `DocumentSurfaceLockTest` (9-param lock holds — composite adds no `DocumentView` param); `DocumentTrustSurfaceTest`; `application/pdf` / `ACTION_SEND` bytecode scan stays green. |

## Addendum 2026-06-18: D5 trust caption is relocatable into a host details row

Tracks: `wpass-gv6` (this addendum). Cross-repo consumer: walt-android
`wlt-3cer` (the "Pass type" details row, held pending this decision).

### Context

The consumer (Walt) wanted to consolidate the document trust signal into a
single row inside its own host-rendered details section, instead of the
kernel's bottom-/top-docked `DocumentTrustCaption`. Read literally, D5 and the
Z.4 / Z.8 addenda forbid this: the caption is non-suppressible and docked. The
question this addendum settles is whether "docked by the kernel" is load-bearing
or whether "always present, verbatim, kernel-owned" is the actual invariant —
and if the latter, how a host may move it without collapsing the defense.

### D5.R The caption is relocatable, never suppressible

The load-bearing invariant is **always-present, verbatim, kernel-owned**, not
*kernel-docked*. The kernel therefore exposes
`DocumentView(trustCaption = TrustCaptionPlacement.Hosted)` (`passes-document-ui`).
The contract:

- The relocated caption is the **kernel-owned `DocumentTrustCaption`
  composable**, mounted by the host in its details section. The verbatim wording
  ("User-provided document. Walt has not verified the source.") and the layout
  live only in that composable, pinned by `DocumentSurfaceLockTest`. The host
  chooses *location*, never *content*.
- A neutral type label is **not** a substitute. A "Pass type: PDF" row does not
  carry the provenance claim; showing such a label *instead of* the caption
  collapses D5. The verbatim caption must be present, relocated, somewhere
  always-visible on the detail surface — it may sit *in the same details
  section as* the type row, but it is not replaced by it.
- `TrustCaptionPlacement` is `Docked | Hosted` — both render the verbatim kernel
  caption; neither hides it. There is no boolean that suppresses it. The shape is
  pinned by `documentViewTrustCaptionParamIsThePlacementType`, and the param
  count lock moves from 9 to 10 (`documentViewHasExactlyTenUserVisibleParameters`),
  superseding the 9-param assertion in the prior (composite) addendum.

This is the same posture as the `SCANNABLE_CARD_THREAT_MODEL.md` wallet-row
concession (trust shifts surface, never disappears) and the parallel
`ScannableCardScreen` change under `wpass-gv6`. The kernel cannot verify at
runtime that the host mounted the relocated caption, so the obligation shifts to
the consumer and is pinned consumer-side by a walt-android test that the details
section renders the kernel `DocumentTrustCaption`.

### Scope: inline `DocumentView` only; full-screen stays docked

Relocation applies to the inline `DocumentView` detail surface only.
`FullScreenDocumentView` is unchanged: its caption stays docked to a screen edge
and non-suppressible (Z.8). A host's details row is an inline-surface affordance;
the full-screen zoom surface has no host details chrome to fold into, so there is
nothing to relocate into and the Z.4 / Z.8 "cannot be panned off, visible at
every zoom level" guarantees stand verbatim.

### Tests pinning this addendum

| Decision | Test                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| D5.R     | `DocumentSurfaceLockTest.documentViewHasExactlyTenUserVisibleParameters` + `documentViewTrustCaptionParamIsThePlacementType` (relocation-not-suppression shape lock) |
| D5.R     | `DocumentTrustSurfaceTest.documentViewHostedPlacementOmitsKernelDockedCaption` (Hosted omits the kernel copy so the host's relocated caption is not duplicated); `documentTrustCaptionRendersTheVerbatimTrustText` (the kernel composable the host mounts still renders verbatim) |
| Scope    | `FullScreenDocumentView` shape lock unchanged (7 params); Z.8 full-screen caption tests stay green |
