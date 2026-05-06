# PDF document import: threat model

The trust claim that this repository carries is "every security-and-privacy-critical
behavior Walt makes about pass handling lives in code you can read." For PDF
import, that decomposes into a per-threat enumeration plus three load-bearing
controls. Each threat is listed below with: where it lives in the format, what
control mitigates it, and (for accepted-risk items) the rationale for accepting
the residual exposure.

This document is the security-side companion to ADR 0005 ("PDF document
support"). Where ADR 0005 records *what* the architecture is, this document
records *why each piece of it has to exist*.

## Three load-bearing controls

The mitigations below all reduce to combinations of three structural controls.
Each is named here so individual threats can reference them by short label
rather than re-stating the rationale.

**C1. System renderer only (`android.graphics.pdf.PdfRenderer`).** Walt does
not bundle a PDF parser. PDFium ships in Android's MediaProvider Mainline
module on Android 14+ (API 34) and updates via Play System Updates, so PDFium CVE
response is decoupled from Walt's release cadence. The API surface is narrow
(`ParcelFileDescriptor` in, `Bitmap` out) and the API explicitly does not
execute JavaScript, does not process `URI` / `Launch` / `SubmitForm` actions
during page render, and does not perform network I/O. ADR 0005 D2.

**C2. Isolated-process renderer service.** All PDF parsing and rasterization
runs in a `Service` annotated `android:isolatedProcess="true"`. The service has
no app-data access, no inherited network permission, no Keystore session, and
OS-level crash containment. A use-after-free or out-of-bounds read inside
PDFium brings down the service, not the wallet process. ADR 0005 D3.

**C3. No-extraction discipline.** Walt performs exactly one operation on a
PDF: `PdfRenderer.openPage(N).render(bitmap, ...)`. Walt does not extract
text, metadata, form-field values, annotations, links, or attachments. The
renderer service's binder API exposes exactly two methods, `probe(fd)` and
`render(fd, page, w, h)`. ADR 0005 D4.

## Format-level threats

### 1. Embedded JavaScript (`/JavaScript`, `/JS` actions)

**What.** PDF supports an embedded JavaScript runtime, with hooks for
`/OpenAction`, document open, page open, form-field events, and bookmark
navigation. Adobe Acrobat executes these. Reader-side JS has been a CVE source
for two decades.

**Mitigation.** C1: `PdfRenderer` does not implement the JavaScript runtime;
script bytes are inert. C3: Walt never enumerates `/Names` or
`/AcroForm/Fields`, so even the codepath that *discovers* JS-bearing nodes
is never invoked. `wpass-6m9` empirically verifies non-execution against a
PDF carrying a JS `/OpenAction`.

**Status.** Mitigated.

### 2. Action triggers (`/Launch`, `/SubmitForm`, `/URI`, `/GoToR`, `/RemoteGoTo`)

**What.** Annotations and bookmarks can carry actions that fire intents
(`/Launch`), navigate to remote PDFs (`/GoToR`), submit form data over the
network (`/SubmitForm`), or open URLs (`/URI`). A hostile PDF that the
renderer "follows" actions for can attempt local-app attack, phone-home, or
URL-hijack flows.

**Mitigation.** C1: `PdfRenderer.render()` does not interpret action
dictionaries; rasterization ignores them. C3: Walt never extracts annotations
or bookmarks, so the action dictionaries never reach Walt-controlled code.

**Status.** Mitigated.

### 3. AcroForm and XFA active content

**What.** AcroForm fields can carry calculation scripts, validation scripts,
and submit actions. XFA (XML Forms Architecture) is an embedded XML form
runtime with its own scripting (FormCalc, JavaScript). XFA in particular has
been a recurring CVE source.

**Mitigation.** C1: `PdfRenderer` does not run XFA; `PdfRenderer` rasterizes
AcroForm fields as static visuals without invoking calc/validate/submit
hooks. C3: Walt never reads form-field values, so even widget-text leakage
is not surfaced.

**Status.** Mitigated.

### 4. Embedded file attachments (`/EmbeddedFiles`)

**What.** PDFs may carry arbitrary file payloads in the `/Names` →
`/EmbeddedFiles` tree. Historic exploits use this for malware delivery via
Adobe Reader's "save attachment" UI.

**Mitigation.** C3: Walt never traverses `/Names` and never offers a "save
attachment" affordance. The bytes sit unreferenced inside the PDF blob and
never reach a code path that interprets them. C2 ensures that even a
hypothetical bug in the renderer that *did* materialize an attachment would
do so inside the isolated process with no DB or filesystem write capability.

**Status.** Mitigated.

### 5. PDF digital signatures (PAdES / CMS)

**What.** PDFs can carry PKCS#7-CMS signatures (legacy and PAdES profiles).
Signature semantics in the wild are inconsistent: whole-document vs.
partial-document, certification vs. approval, MDP transforms with varying
permission levels.

**Mitigation.** Not verified. ADR 0005 D5. Every PDF imported into Walt
carries `Provenance.UserProvided`, structurally distinct from PKPASS's
`SignatureStatus.AppleVerified` / `SelfSigned` ladder. The
`DocumentTrustCaption` ("User-provided document. Walt has not verified the
source.") is non-suppressible, in the same visual register as the existing
`SignatureStatus.SelfSigned` pill.

**Rationale for not verifying.** A "Verified" UI signal that Walt cannot
honestly back would dilute the PKPASS signature trust signal. There is no
analogue to Apple's WWDR root for ticket issuance. Implementing PAdES
correctly (across MDP transforms, certification chains, revocation) is its
own project, and a half-correct implementation is worse than none.

**Status.** Accepted, with structural UI mitigation (D5, D8 non-suppressible
caption).

### 6. Network fetches at render time

**What.** PDFs can reference remote resources (color profiles, forms,
external images, `/RemoteGoTo`). A reader that resolves these on render
phones home, leaks IPs, and turns into a probe vector.

**Mitigation.** C1: `PdfRenderer.render()` does not resolve external
references. C2: the isolated-process service's manifest declares zero
`uses-permission`; `INTERNET` is not inherited. Even if a future renderer
update added a remote-resolution path, the OS would deny network access.

**Status.** Mitigated, with defense-in-depth at the permission layer.

### 7. Decoder-CVE-heavy subformats (JBIG2, JPEG2000, CCITT)

**What.** Image subformats inside PDF have a long CVE history. JBIG2
specifically has shipped at least two memory-corruption issues with public
exploit chains in the past five years (FORCEDENTRY, etc.). JPEG2000 (JPX)
and CCITT Fax have similar histories at lower volume.

**Mitigation.** C2: a use-after-free or out-of-bounds write inside the
PDFium image-codec path is contained to the isolated renderer process. The
process has no DB, no Keystore session, no filesystem write outside its
bitmap output, and no network. The blast radius of a successful exploit is
"the user re-taps to view the document; the rendering service rebinds."

C1: PDFium ships in MediaProvider Mainline on Android 14+ (API 34), so
codec fixes flow via Play System Updates without a Walt release.

**Status.** Mitigated against persistence and lateral movement; CVE-window
exposure in the renderer process itself is the residual risk and is
quantified in `wpass-6m9`.

### 8. Object-graph attacks (deep nesting, malformed xref, oversized streams)

**What.** PDFs are graphs of indirect objects with `/Parent` references and
xref tables. Hostile inputs include xref-table cycles, deeply recursive
page-tree structures, billion-laughs-style stream expansion, and integer
overflow in object-number fields.

**Mitigation.** C2 contains decoder bugs to the isolated process. ADR 0005
D7 caps page count at 10 (the renderer rejects a `pageCount > 10` PDF
before any further traversal). The 5-second render watchdog kills the
service on unbounded loops in stream decoding. The 25 MB import cap stops
the worst-case input volume.

**Status.** Mitigated, with hard caps as second line.

### 9. Encrypted PDFs

**What.** PDFs may be encrypted with a user password. The reader normally
prompts for the password to decrypt and render.

**Mitigation.** Refused at import. ADR 0005 D6. The renderer service
detects the encryption flag at `probe()` and returns
`Rejected(Encrypted)`; Walt does not surface a password prompt.

**Rationale.** A password prompt invoked from PDF-encryption metadata is
a UI vector controlled by an untrusted file. A user habituated to typing
passwords into Walt is one prompt-spoof exploit away from a real
credential leak (the spoofed prompt could phrase itself as a Walt /
Google / device password request). The cost is that users with
password-protected files must decrypt elsewhere first.

**Status.** Accepted by refusal.

### 10. Render bombs

**What.** Hostile PDFs that declare enormous page sizes, request very
large bitmap allocations, or carry deeply recursive page trees. Goal:
OOM-crash the host or exhaust the device.

**Mitigation.** ADR 0005 D7 hard caps:
- 25 MB import-size ceiling
- 10-page document ceiling
- 4 MP per-page rasterized-bitmap ceiling
- 5 s render-call watchdog with `Process.killProcess` on timeout

The ceiling values are enforced both in the renderer service (first line)
and at the storage-layer insert (defense in depth, so a future caller bug
cannot land an oversized blob).

**Status.** Mitigated.

### 11. Cross-pass exfiltration via renderer compromise

**What.** A renderer-process exploit that survives long enough to read
memory or filesystem could, in theory, exfiltrate the SQLCipher key,
decoded PKPASS bytes, or other user data co-resident in the wallet
process.

**Mitigation.** C2 places the renderer in a process that has none of the
above in its address space or filesystem view. Isolated-process services
on Android are forbidden from reading `/data/data/<app>/`, cannot bind the
Keystore, and cannot bind network sockets. A successful PDFium exploit
inside the isolated process can corrupt the rendered bitmap; it cannot
read SQLCipher pages because they are not in that process's memory.

**Status.** Mitigated structurally (the data is not reachable).

### 12. Phishing-barcode at the gate

**What.** A hostile "ticket" PDF whose visible barcode/QR resolves to a
credential-phishing URL when scanned. The user scans it at the gate or
elsewhere, trusts the scanner's intent, and lands on an attacker page.

**Mitigation.** None applicable from Walt. The barcode is rasterized
content the user is responsible for sourcing, and Walt makes no claim
about the contents of an unverified document. The non-suppressible
`DocumentTrustCaption` is the user-facing signal; the structural absence
of a "Verified" pill prevents false-confidence escalation.

**Status.** Accepted-risk for v1; the trust-caption discipline is the
user-facing mitigation.

### 13. Bidi/control-character spoofing in display label

**What.** The PDF's filename (or fallback "PDF, added <date>" string) is
user-controlled and rendered alongside the trust caption. A filename
containing U+202E or other Cf/Cc characters could rearrange visible glyphs
to spoof Walt UI text.

**Mitigation.** `DocumentTrustCaption` and the Documents-lane tile wrap
the display label in U+2068 / U+2069 (FSI / PDI) bidi isolates, mirroring
the existing `B3UrlConfirmSheet` discipline (`passes-ui/TRUST_CLAIMS.md`
section 2). `wpass-7wn`'s instrumentation tests verify the wrap.

**Status.** Mitigated.

## Inventory: PKPASS controls and PDF equivalents

| PKPASS control                                  | PDF equivalent                                                                |
|-------------------------------------------------|-------------------------------------------------------------------------------|
| `ParserConfig.maxArchiveBytes` (10 MB)          | `PdfImportConfig.maxBytes` (25 MB); enforced at import and at storage         |
| Manifest hash + PKCS#7 signature                | Not applicable; provenance is `UserProvided` (D5)                             |
| `ImageRenderBounds` (1920 x 1920, 4 MP)         | Per-page rendered-bitmap cap of 4 MP                                          |
| `SignatureStatus` four-band badge               | Single-band "User-provided document" caption, non-suppressible                |
| `FieldLinkScanner` Cf/Cc rejection              | Not applicable (no link extraction)                                           |
| `B3UrlConfirmSheet`                             | Not applicable (no URL surfaced)                                              |
| `ExpiredOverlayState`                           | Not applicable (no expiration metadata; sort by import date)                  |
| `TelemetryGuard` PII discipline                 | `DocumentTelemetryGuard` mirrors the discipline (enums / counts / durations)  |
| `application/vnd.apple.pkpass` intent filter    | `application/pdf` intent filter, registered in walt-android                  |
| Encrypted-at-rest (SQLCipher) + Auto Backup off | Same database, same XML rules apply automatically                             |
| Irreversible delete with cache wipe             | Same `ON DELETE CASCADE` and unwind contract                                  |
| (n/a)                                           | New: isolated-process renderer (C2)                                           |

## Explicit non-features

The list below is load-bearing: a future contributor proposing any of these
items must amend ADR 0005, not just file a PR. The non-features below are not
"deferred to v2"; they are deliberately absent because each one re-opens a
threat row above.

- **No PDF digital-signature verification.** Re-opens row 5.
- **No text, form-field, or annotation extraction.** Re-opens rows 1, 2, 3.
- **No URL surfaced from PDF.** Re-opens row 2.
- **No PDF-metadata-driven labels.** Re-opens parsing surface for `/Title`,
  `/Author`, XMP; re-introduces a parser-side string handling path that the
  current design avoids entirely.
- **No DB / Keystore / network / file-system access from inside the renderer
  process.** Re-opens row 11.
- **No "share" / "export" of PDF bytes out of Walt.** ADR 0005 D8: every
  egress codepath is a potential exfiltration path if a future bug routes
  content through it implicitly.

## How each control is tested

The implementation beads (`wpass-jsb`, `wpass-pti`, `wpass-5v9`, `wpass-7wn`)
each carry tests pinning the controls used here. The mapping is recorded in
ADR 0005 ("Tests pinning the controls above") and is not duplicated here.

The renderer-validation bead `wpass-6m9` is the empirical complement to this
document: where this document asserts "C1 does not execute JavaScript," that
bead's deliverable is a recorded reproduction with a JS-laden test PDF on at
least one Pixel device and one OEM device.

## Out of scope for this document

- The wallet's payment / HCE surface is unchanged by PDF import; documented
  in the parent epic `wpass-i2r` and in the existing payment-side trust
  documentation.
- The Walt-side onboarding, Hilt wiring, and intent-filter manifest are
  consumer-side concerns tracked in walt-android's beads database.
- Performance tuning of the renderer service (binder-call latency, LRU cache
  sizing) is implementation detail for `wpass-5v9` and `wpass-7wn`; the
  numbers in ADR 0005's Consequences section are targets, not security
  controls.
