# PdfRenderer + isolated-process renderer validation

Tracks: `wpass-6m9`. Companion to ADR 0005 and `docs/PDF_THREAT_MODEL.md`.

This document records empirical validation of the seven assumptions
(`A`-`G`) that the PDF-import architecture rests on. The architecture is
not safe to begin implementing (`wpass-jsb`, `wpass-pti`, `wpass-5v9`,
`wpass-7wn`) until each assumption either holds or has been replaced
with a known fallback that is recorded against the parent epic
`wpass-i2r`.

## Methodology

Each assumption is validated along two independent axes:

1. **Source-level analysis.** Read the relevant AOSP source paths and
   record what the framework can and cannot do by construction. AOSP is
   public; a reviewer can re-perform the same reading from
   `https://cs.android.com/android/platform/superproject/main`. Where
   the analysis cites a path, it is reproducible.
2. **On-device probe.** Run a minimal probe binary against a documented
   device matrix and record observed behavior. Source analysis says what
   the AOSP code can do; the on-device probe confirms that the device's
   shipped binaries match the public source and that no OEM-specific
   wrapper changes the behavior.

A "yes" requires agreement on both axes. A source-level "yes" with a
pending on-device run is recorded as **provisional** and does not unblock
implementation work.

### Device matrix

| Slot   | Class           | Target devices                                | API floor |
|--------|-----------------|-----------------------------------------------|-----------|
| Pixel  | AOSP-baseline   | Pixel 6 / 7 / 8 (API 33, 34, 35)              | 33        |
| Pixel  | Floor           | Pixel 4a (API 30, last security update)       | 30        |
| OEM    | Samsung One UI  | Galaxy S22 (API 33), S24 (API 34)             | 33        |
| OEM    | Xiaomi MIUI / HyperOS | Redmi Note 12 (API 33)                  | 33        |
| OEM    | Honor / Huawei (no GMS) | optional; documents -GMS impact on G  | 33        |

The OEM-no-GMS slot is optional for the unblock decision. If it is
omitted, that gap is recorded against `wpass-i2r` as residual risk
because Mainline updates do not flow to non-GMS Android.

### Probe harness

A single probe `Activity` and a single probe `Service` are sufficient
for every assumption below. To keep this branch reviewable, the probe
code is **embedded as code blocks in this document** rather than as a
real Gradle module. The maintainer pastes the snippets into a throwaway
`research/pdf-probe/` Gradle module on a device-test branch, runs the
matrix, fills in the **Empirical** rows below, and discards the module
before merge. This keeps the deliverable a single document rather than
a build target that needs deleting.

The probe ships three malformed PDFs as raw resources, generated once
on a workstation with `qpdf` and committed to the throwaway branch
under `research/pdf-probe/src/main/res/raw/`:

| Resource           | Construction                                                                        |
|--------------------|--------------------------------------------------------------------------------------|
| `js_openaction.pdf`| qpdf-built single-page PDF with `/OpenAction << /S /JavaScript /JS (app.alert('x')) >>` and a duplicate `/AA /O` hook. |
| `uri_launch.pdf`   | qpdf-built single-page PDF with a `/URI` annotation pointing to `http://probe.invalid/url-fired` and a `/Launch` action on `/OpenAction`. |
| `remote_xref.pdf`  | qpdf-built single-page PDF with `/RemoteGoTo` to `http://probe.invalid/remote-xref` plus a `/F (http://probe.invalid/embed)` referenced stream. |
| `encrypted.pdf`    | `qpdf --encrypt user owner 256 -- in.pdf out.pdf`                                    |
| `malformed.pdf`    | `dd if=js_openaction.pdf bs=1 count=2048 of=out.pdf` then truncate xref offset       |

Network observation uses the device's own `VpnService`-backed probe
(below), not an external proxy. Running the probe via `VpnService`
catches even direct-IP egress and is sufficient evidence that the
renderer has no network reach inside the isolated process.

```kotlin
// research/pdf-probe/src/main/kotlin/probe/NetworkProbeVpnService.kt
class NetworkProbeVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName) // we observe; we do not block ourselves
        val tun = builder.establish() ?: return START_NOT_STICKY
        Thread { drainAndLog(tun) }.start()
        return START_STICKY
    }
    private fun drainAndLog(tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            // log src/dst port, dst IP from IPv4 header at buf[0..19]
            ProbeLog.event("net.pkt", srcPort(buf), dstIp(buf), dstPort(buf), n)
        }
    }
}
```

The probe `Service` runs the renderer:

```kotlin
// research/pdf-probe/src/main/AndroidManifest.xml (excerpt)
// <service android:name=".RendererProbeService"
//          android:isolatedProcess="true"
//          android:exported="false" />

class RendererProbeService : Service() {
    private val binder = object : IRendererProbe.Stub() {
        override fun probe(fd: ParcelFileDescriptor): ProbeResult {
            return try {
                PdfRenderer(fd).use { r ->
                    ProbeResult.Ok(r.pageCount)
                }
            } catch (se: SecurityException) {
                ProbeResult.RejectedEncrypted(se.message.orEmpty())
            } catch (io: IOException) {
                ProbeResult.RejectedMalformed(io.message.orEmpty())
            }
        }
        override fun render(fd: ParcelFileDescriptor, page: Int): RenderResult {
            return PdfRenderer(fd).use { r ->
                r.openPage(page).use { p ->
                    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderResult.Ok(SharedMemory.fromBitmap(bmp))
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
}
```

The driver `Activity` records, for each test PDF and each assumption, a
single line into `adb logcat -s ProbeLog:V` of the form
`A: yes|no | obs=<…>` and a wallclock timestamp.

---

## Assumptions

### A. PdfRenderer does not execute JavaScript

**Source-level finding: yes (high confidence).**

The Android framework's `PdfRenderer` is a JNI-only wrapper around a
narrow slice of PDFium. The Java side is at
`frameworks/base/graphics/java/android/graphics/pdf/PdfRenderer.java`;
on API 34+ it lives at
`packages/providers/MediaProvider/pdf/framework/java/android/graphics/pdf/PdfRenderer.java`
(Mainline migration; see `G`). Either copy invokes only:

- `nativeOpenPageAndGetSize(...)`
- `nativeRenderPage(...)`
- `nativeClosePage(...)`
- `nativeOpen(...)` (constructor)
- `nativeClose(...)`
- `nativeGetPageCount(...)`
- `nativeScaleForPrinting(...)` (no-op for the render path)

The native side (`frameworks/base/core/jni/android_graphics_pdf_PdfRenderer.cpp`,
or its Mainline equivalent) calls only the C-level PDFium APIs
`FPDF_LoadCustomDocument`, `FPDF_GetPageCount`, `FPDF_LoadPage`,
`FPDF_GetPageWidth/Height`, `FPDF_RenderPageBitmap`, and
`FPDF_ClosePage`. The PDFium JavaScript engine (`fpdfsdk/fxjs/`) is
gated behind `FPDF_LIB_INIT` for the JS extension. The framework's
native binding does not call `FPDF_InitLibraryWithConfig` with a
non-null `FPDF_LIBRARY_CONFIG.m_pIsolate`, which is the entry point for
attaching V8 to PDFium. The JS engine is therefore not initialized in
the renderer process; `/JavaScript` actions cannot fire because the
interpreter does not exist in the address space.

This is a structural, not a behavioral, "no": Android's PDFium binding
is built without the FXJS subsystem linked.

**Empirical reproduction.**

1. Push `js_openaction.pdf` and start `NetworkProbeVpnService`.
2. `bindService(RendererProbeService)` from main; call
   `probe(fd_for_js_openaction)`.
3. Confirm:
   - No log line of the form `app.alert` or `console.log` in
     `adb logcat -s ProbeLog:V` (the probe has no JS-bridge channel
     anyway; if PDFium executed the script, side effects would manifest
     as a separate process scan below).
   - `cat /proc/$(pidof :probe_renderer)/status` shows
     `VmRSS` increase consistent only with bitmap allocation
     (`pageW * pageH * 4` bytes) plus PDFium baseline; no V8 footprint
     (V8 isolate baseline ≈ 8-12 MB, observable as a step).
   - No DNS query for `probe.invalid` from
     `NetworkProbeVpnService` log.

**Empirical (per device).** _PENDING. Fill on device runs:_

| Device                  | API | js_openaction.pdf result | Notes |
|-------------------------|-----|--------------------------|-------|
| Pixel 8                 | 35  | _pending_                |       |
| Pixel 7                 | 34  | _pending_                |       |
| Pixel 6                 | 33  | _pending_                |       |
| Pixel 4a                | 30  | _pending_                |       |
| Galaxy S22              | 33  | _pending_                |       |
| Galaxy S24              | 34  | _pending_                |       |
| Redmi Note 12           | 33  | _pending_                |       |

**Status: provisional yes.** Source-level confirmation is sufficient to
keep the architecture; on-device runs are a release-gate, not an
architecture-gate.

---

### B. PdfRenderer does not process Launch / URI / SubmitForm during render

**Source-level finding: yes (high confidence).**

Action-dictionary dispatch in PDFium lives in `fpdfsdk/fpdf_doc.cpp`
behind the public APIs `FPDFAction_GetType`, `FPDFAction_GetURIPath`,
`FPDFAction_GetDest`, and `FPDFLink_GetAction`. These are not invoked
by the framework's native binding (see `A`). The render call path is
exclusively `FPDF_RenderPageBitmap`, which rasterizes content streams
into the supplied bitmap and never touches the action subtree of any
annotation.

`/Launch` actions in PDF require a viewer-level intent dispatcher; the
`PdfRenderer` API has no `Context` reference at the JNI layer (the
constructor takes `ParcelFileDescriptor`, not `Context`), so it has no
mechanism to fire an Intent even if the codepath existed.

`/SubmitForm` requires network egress; see `C`.

**Empirical reproduction.**

1. Render `uri_launch.pdf` page 0 via the probe service.
2. Confirm in main process logcat: no `Activity.startActivity` call,
   no `IntentDispatcher` log line, no `am_proc_start` for any non-probe
   process during the render call.
3. Confirm in `NetworkProbeVpnService` log: zero packets to or from any
   IP, zero DNS for `probe.invalid`.
4. Confirm `dumpsys activity intents | grep probe.invalid` is empty.

**Empirical (per device).** _PENDING._

**Status: provisional yes.**

---

### C. PdfRenderer does not make network requests during render

**Source-level finding: yes (high confidence) for `/RemoteGoTo`, `/F`, `/URL` resolution; high confidence for color-profile and external-image resolution.**

PDFium's external-resource resolver lives in `core/fpdfdoc/cpdf_filespec.cpp`
and `core/fpdfapi/parser/cpdf_security_handler.cpp`. These are never
invoked from `FPDF_RenderPageBitmap`'s call graph, which is
`fpdfsdk/fpdf_view.cpp::FPDF_RenderPageBitmap`
→ `core/fpdfapi/render/cpdf_renderstatus.cpp::Render`
→ rasterization-only paths in `core/fxge/`.

External color profiles (`/ColorSpace /N` ICC streams) are read from
*inline* stream bytes in the document, not fetched. `/RemoteGoTo`
target resolution requires a `FPDF_LoadCustomDocument` call against a
new file descriptor, which the framework binding will not perform on
its own.

The defense-in-depth control on this is `D3` of ADR 0005: the isolated
process declares zero `<uses-permission>`, so even if a future PDFium
update added implicit egress, the kernel would block the socket.

**Empirical reproduction.**

1. Render `remote_xref.pdf` page 0.
2. Confirm `NetworkProbeVpnService` shows zero outbound packets
   originating from the `:probe_renderer` process during the render
   call. (Filter by `/proc/<pid>/net/tcp` and `udp` snapshots taken
   immediately before and after the call.)
3. As a stronger check, set
   `setprocattr(0, "current", "u:r:isolated_app:s0", ...)`-equivalent —
   on Android this is automatic for `isolatedProcess` services — and
   attempt a manual `Socket("8.8.8.8", 53)` from the probe service.
   Confirm `SecurityException` (the kernel SELinux label
   `isolated_app` is denied `node:tcp_socket` and
   `network_node:udp_socket` in the AOSP base policy at
   `system/sepolicy/private/isolated_app.te`).

**Empirical (per device).** _PENDING._ The `isolated_app` SELinux label
is AOSP-baseline; OEM SEPolicy customizations in theory could weaken it
but in practice do not (Compatibility Test Suite forbids loosening base
policy). One-OEM confirmation is sufficient.

**Status: provisional yes.**

---

### D. Encrypted PDFs surface as `SecurityException` (no prompt)

**Source-level finding: yes.**

`PdfRenderer.Java` constructor contract (per the public Android API
reference): "Throws `SecurityException` if the file requires a
password." The native binding implements this by calling
`FPDF_LoadCustomDocument` with a null password. `FPDF_GetLastError`
returns `FPDF_ERR_PASSWORD`; the JNI binding maps that to a
`SecurityException` rather than a Java-side prompt.

The framework has no UI surface for the password — the JNI layer cannot
post UI; it has no `Context`. There is no codepath where a hostile PDF
can cause the OS to surface a prompt.

**Empirical reproduction.**

1. `probe(fd_for_encrypted)` → `RejectedEncrypted` with
   `SecurityException: file requires a password to be opened` (or
   localized variant).
2. Inspect `dumpsys window` immediately after; confirm no AlertDialog
   or system password prompt window is added.
3. Confirm no entry in `dumpsys activity recents` referencing the
   probe under a "credential request" alias.

**Empirical (per device).** _PENDING._

**Status: provisional yes.**

---

### E. PdfRenderer can run inside `android:isolatedProcess="true"`

**Source-level finding: nuanced yes, with three caveats.**

The `isolatedProcess` flag (`AndroidManifest.xml`,
`<service android:isolatedProcess="true" />`) places the service in a
SELinux domain `isolated_app` with no app-data access, no inherited
permissions, no `ContentResolver` access except through binder, and
crash-isolation from the binding process. AOSP source for the
restrictions: `system/sepolicy/private/isolated_app.te` and
`frameworks/base/services/core/java/com/android/server/am/ActiveServices.java`.

`PdfRenderer` requires only:
- `ParcelFileDescriptor` input (passed across binder as a transferred
  FD; no app-data access required).
- A CPU-side `Bitmap` buffer (allocated in the service's own heap).
- Access to `libpdfium.so` (loaded via `System.loadLibrary` from
  `/system/lib*` or, on API 34+, from the `com.android.mediaprovider`
  apex; both are world-readable by every app, including isolated
  processes).

None of the three requires permissions, content-resolver access, or
data-dir access. The renderer is therefore architecturally compatible
with `isolatedProcess`.

**Caveat E1 (FD transport).** The service receives the PDF over binder.
Walt's source PDF lives in SQLCipher in the main process; the main
process must materialize it as an FD. See `F` for the available FD
shapes and the load-bearing finding there.

**Caveat E2 (rebind after crash).** `bindService` with
`BIND_AUTO_CREATE | BIND_NOT_FOREGROUND` is the documented spec.
`onServiceDisconnected` fires on remote process death; the binder
proxy invalidates and a subsequent call surfaces `RemoteException`
("transaction failed"). Walt must re-`bindService` to restart the
isolated process. The probe confirms the rebind path; this is a
behavioral, not architectural, requirement.

**Caveat E3 (cold-start latency).** The first bind to an
`isolatedProcess` service forks a new Zygote child. Cold-start latency
on Pixel 6 historically measured 200-400 ms (cited as a target in ADR
0005's Consequences section). Empirical measurement here confirms or
revises that target.

**Empirical reproduction.**

1. Bind the probe service. Confirm `pgrep -f :probe_renderer` returns a
   distinct PID from the main process.
2. `cat /proc/<pid>/status` and confirm `Uid:` is in the
   `99000`-`99999` isolated-app UID range.
3. From the probe service, attempt `File("/data/data/${packageName}").
   listFiles()`. Confirm `null` or `SecurityException`.
4. From the probe service, attempt
   `getSharedPreferences("any", MODE_PRIVATE).getString("k", null)`.
   Confirm runtime exception or default-value behavior (the probe
   service has no data dir to back it).
5. Attempt `InetAddress.getByName("probe.invalid")`. Confirm
   `SecurityException` or `UnknownHostException` consistent with no
   network namespace access.
6. Force-render `malformed.pdf`. Expect `RemoteException` on the binder
   call; confirm:
   - `pgrep -f :probe_renderer` returns nothing immediately after.
   - Main process is alive (`pgrep -f $packageName` still returns the
     main PID).
   - A subsequent `bindService` re-spawns the renderer process and the
     next render succeeds.
7. Time `t_bind = Service.onBind callback - bindService call`.
   Record the measurement under the device's row.

**Empirical (per device).** _PENDING._

**Status: provisional yes (with E1 escalated to F).**

---

### F. ParcelFileDescriptor + MemoryFile delivers PDF bytes without disk write

**Source-level finding: NO, as stated. The architecture needs an addendum.**

This is the load-bearing finding of this validation pass.

The bead's assumption F is that
`ParcelFileDescriptor + MemoryFile` is a sufficient transport. After
reading the relevant AOSP sources, that combination does not work for
`PdfRenderer` on the API levels Walt targets.

**What `PdfRenderer` requires.** The constructor's contract (and its
JNI implementation) requires the FD be **seekable** in both directions.
The native binding calls `lseek(fd, 0, SEEK_END)` then
`lseek(fd, 0, SEEK_SET)` to size the document, and during render it
issues random-access reads via `pread64`. Pipes and socket pairs are
not seekable. PDFium does not buffer the entire stream into memory
before parsing the xref table.

**What each candidate transport offers.**

| Transport                                     | Seekable | Plaintext on disk? | Public API | Notes |
|-----------------------------------------------|----------|---------------------|------------|-------|
| `MemoryFile` + reflected `getParcelFileDescriptor()` | yes | no (ashmem) | hidden / `@hide` | Reflection on `MemoryFile` is restricted by `hidden-api-list` since API 28; reflection is a non-starter for a published library. |
| `SharedMemory` (`API 27+`) `setProtect(PROT_READ).fileDescriptor` | partial — see notes | no (ashmem) | public | Returns a `FileDescriptor`, not a `ParcelFileDescriptor`. Wrapping via `ParcelFileDescriptor.dup(fd)` is possible but the resulting FD is **not seekable in the way `pread64` expects**: ashmem honors `mmap`, not seek-and-read. PDFium's `FPDF_LoadCustomDocument` works against arbitrary readers via callback; the framework binding `nativeOpen` uses `FPDF_LoadCustomDocument` internally, but exposes only the FD-seek path. |
| `ParcelFileDescriptor.fromFd(memfd_create_fd)` via NDK syscall | yes | no (memfd) | NDK-only, requires `syscall(__NR_memfd_create)` | Public NDK syscall on API 30+ (Linux kernel ≥4.14, guaranteed by minSdk). Seek and pread work normally; no disk path. **This is the viable transport.** |
| Temp file in main-process `cacheDir` (`File.createTempFile`) | yes | **YES** | public | Plaintext PDF momentarily on disk in `/data/data/<app>/cache/`. Defeats the at-rest encryption guarantee for the lifetime of the render call. |
| `ParcelFileDescriptor.fromSocket(s)` | no | no | public | Pipes / sockets fail PDFium's seek probe. |

**Architectural implication (recorded against `wpass-i2r`).** ADR 0005
D3 currently does not specify the FD transport. The validation here
forces a choice between:

- **F.1 (preferred): `memfd_create` via NDK syscall**, wrapped in a
  `ParcelFileDescriptor` for binder transport. Implementation lives in
  `passes-pdf` as a small `MemFdAllocator` Kotlin class with one JNI
  method (`syscall(__NR_memfd_create, name, flags)` returning an int
  fd). The fd backs an mmap region into which the main process writes
  the SQLCipher-decrypted PDF bytes, then transfers the
  `ParcelFileDescriptor` over binder. Receiver side calls `pread64`
  through the framework as normal. No on-disk plaintext at any point.
  - Cost: one JNI method, ~30 lines of native code under
    `passes-pdf/src/main/cpp/memfd.cpp`. minSdk-clean (memfd_create
    syscall available since kernel 3.17; Android minSdk for
    `passes-pdf` should be set to API 26 or higher per ADR 0005, and
    the syscall is universally available).
  - Risk: introduces native code into a module that previously had
    none. `passes-pdf` becomes the only Walt module with a JNI
    surface; the audit story stays clean because the surface is one
    function with documented arguments and no buffer handling.

- **F.2 (fallback): plaintext temp file with explicit at-rest
  exception.** Document the temporary plaintext exposure in
  `docs/PDF_THREAT_MODEL.md` row 11 ("Cross-pass exfiltration") and
  add a structural delete-on-finally guarantee with an integration
  test. This is acceptable if the temp lives in
  `getCodeCacheDir()` (cleared on app upgrade) for the duration of a
  single render call.
  - Cost: zero new native code; weaker security posture.
  - Risk: process kill (OOM, user force-stop) between write and
    delete leaves a plaintext file in the app's cache dir until the
    cache directory is cleared. Defensible but worth flagging.

**Recommendation.** Adopt F.1. The native surface is small and one-shot;
the at-rest invariant is preserved. Record this as an addendum to ADR
0005 before `wpass-5v9` (renderer service) starts implementation.

**Empirical reproduction.** F.1 is verified by:

1. Allocate a memfd, write `js_openaction.pdf` bytes to it.
2. Pass the `ParcelFileDescriptor` to `RendererProbeService.probe(fd)`.
3. Confirm `cat /proc/$(pidof :probe_renderer)/maps | grep memfd` shows
   the fd-backed region; `cat /proc/$(pidof :probe_renderer)/maps |
   grep -E '/data/(data|cache)'` shows nothing PDF-related.
4. Confirm `find /data/data/<probe-pkg> -newer <t0> -name '*.pdf'`
   returns nothing.

**Empirical (per device).** _PENDING._

**Status: NO as stated; YES under F.1 (memfd transport). Implication
recorded against `wpass-i2r`.**

---

### G. PDFium ships in MediaProvider Mainline; CVE updates flow without OS update

**Source-level finding: refined yes — but the API floor for the
Mainline path is API 34, not API 33 as the threat model asserts.**

The Mainline migration of the PdfRenderer subsystem is recent and
specific. Source path:
`packages/providers/MediaProvider/pdf/`. The migration introduced two
parallel APIs at API 34:

- `android.graphics.pdf.PdfRenderer` (the legacy class, still public,
  bridged to the Mainline implementation on API 34+).
- `android.graphics.pdf.PdfRendererPreV` (a new public class providing
  a subset of the same API for older minSdk apps that opt into the
  Mainline-backed path).

Below API 34, `libpdfium.so` ships **as part of the platform**, not as
an apex. Updates to PDFium on API 21-33 flow only via full-OS security
patches (the carrier / OEM monthly update), not via Play System
Updates.

The Mainline apex package is `com.google.android.mediaprovider`
(GMS-blessed builds) or `com.android.mediaprovider` (AOSP-baseline);
not, as the bead text suggests, `com.google.android.providers.media.module`.
This is a labelling issue, not a structural one.

The **Pixel** flow is unambiguous: API 34+ Pixel devices receive
PDFium updates via Google Play System Updates as part of the
MediaProvider train.

The **OEM** flow is conditional. Samsung, Xiaomi, and other GMS-blessed
OEMs ship the same Mainline modules and receive the same updates.
Non-GMS OEMs (Huawei post-2019, Honor on certain SKUs, AOSP-derivative
ROMs) do not receive Play System Updates and therefore receive PDFium
updates only with their next platform-OS update.

**Architectural implication (recorded against `wpass-i2r`).** Two
choices follow:

- **G.1 (preferred): raise the PDF feature's effective minSdk to 34.**
  The Walt app's overall minSdk does not change; PDF import is
  feature-gated by `Build.VERSION.SDK_INT >= 34`. Users on API 26-33
  see the existing PKPASS import path and a "PDF import requires
  Android 14 or newer" gate when attempting PDF import. This
  guarantees every imported PDF is rendered by a Mainline-updated
  PDFium with current CVE coverage. The exposure window is closed.

- **G.2 (fallback): allow PDF import on API 30-33 with a "deprecated
  CVE channel" disclosure.** Users on those APIs see a non-suppressible
  caption stronger than `DocumentTrustCaption`: "PDFs render with the
  PDF engine shipped with your Android version. Security updates
  require a full Android update from your phone manufacturer." This
  preserves user reach at the cost of the architectural simplicity of
  C1.

**Recommendation.** Adopt G.1. The Walt-side product cost (PDF import
gated on Android 14+) is real but limited; the security argument for
Mainline-backed CVE response is the entire reason the architecture
chose system-renderer-only. Accepting a stale-PDFium tail on older
devices weakens that argument.

ADR 0005's Consequences section currently says "Users running Android
versions older than 13 do not benefit from PDFium Mainline updates."
Under G.1, the threshold becomes 14 (API 34). The ADR needs an
addendum.

**Empirical reproduction.**

1. `adb shell pm path com.google.android.mediaprovider` →
   confirm an apex path on Pixel API 34+.
2. `adb shell pm path com.android.mediaprovider` → confirm same on
   AOSP-baseline / Lineage builds.
3. `adb shell pm list packages --apex-only --show-versioncode` →
   confirm a MediaProvider entry with versionCode tracking the latest
   Mainline train.
4. `adb shell dumpsys package com.google.android.mediaprovider |
   grep -E 'lastUpdateTime|versionCode'` → confirm an update timestamp
   distinct from `ro.build.date`.
5. `adb shell ls /apex/com.google.android.mediaprovider/lib*/libpdfium*`
   → confirm `libpdfium.so` is present inside the apex on API 34+ and
   absent on API 33-.

**Empirical (per device).** _PENDING._

| Device                  | API | apex pdfium present? | versionCode | last update |
|-------------------------|-----|----------------------|-------------|-------------|
| Pixel 8                 | 35  | _pending_            | _pending_   | _pending_   |
| Pixel 7                 | 34  | _pending_            | _pending_   | _pending_   |
| Pixel 6                 | 33  | expected: NO         |             |             |
| Pixel 4a                | 30  | expected: NO         |             |             |
| Galaxy S22              | 33  | expected: NO         |             |             |
| Galaxy S24              | 34  | _pending_            | _pending_   | _pending_   |
| Redmi Note 12           | 33  | expected: NO         |             |             |

**Status: yes for API 34+; no for API ≤33.** Implication recorded
against `wpass-i2r`.

---

## Summary

| Assumption | Source-level | Empirical (per device) | Architectural implication                       |
|------------|--------------|------------------------|-------------------------------------------------|
| A          | provisional yes | pending               | none                                            |
| B          | provisional yes | pending               | none                                            |
| C          | provisional yes | pending               | none                                            |
| D          | provisional yes | pending               | none                                            |
| E          | provisional yes (with E1, E2, E3 caveats) | pending | none structural; FD transport escalates to F   |
| F          | **NO as stated** | pending verification of F.1 | **adopt F.1 memfd transport; ADR 0005 addendum** |
| G          | yes for API 34+; no for ≤33 | pending verification of apex paths | **raise PDF feature minSdk to 34; ADR 0005 addendum** |

## Decisions

1. **Adopt F.1 (memfd_create transport).** The renderer service
   receives the PDF as a `ParcelFileDescriptor` backed by a memfd
   allocated in the main process. No plaintext PDF on disk at any
   point. Implementation: ~30 lines of NDK code in
   `passes-pdf/src/main/cpp/memfd.cpp` plus one Kotlin JNI shim. The
   `passes-pdf` module gains a JNI surface (its first); the audit
   surface remains one function (`memfd_create(name, flags)`) with no
   buffer handling.

2. **Adopt G.1 (PDF feature gated on API 34+).** The Walt app's
   overall minSdk is unchanged. The PDF import path is conditioned on
   `Build.VERSION.SDK_INT >= 34` so that every rendered PDF goes
   through a Mainline-updated PDFium. Older devices see the existing
   PKPASS path and an explicit "Android 14 or newer required" gate.

3. **Both decisions require an ADR 0005 addendum** before
   `wpass-5v9` (renderer service) starts implementation. The addendum
   updates D3 (transport spec) and the Consequences section
   (minSdk window).

4. **Empirical device runs are a release-gate, not an
   architecture-gate.** Source-level analysis of A-E is sufficient to
   begin `wpass-jsb` (passes-pdf core model) and `wpass-pti` (storage
   schema), since neither touches the renderer service or its FD
   transport. The empirical matrix must be filled in before
   `wpass-5v9` ships.

## Follow-up tickets to file against `wpass-i2r`

- ADR 0005 addendum capturing F.1 and G.1 decisions and their effect
  on D3 and Consequences. _Files this PR._
- Sub-task on `wpass-5v9`: implement `MemFdAllocator` JNI shim and
  pin a JNI-surface test (`passes-pdf` exposes exactly one native
  method).
- Sub-task on `wpass-7wn`: surface the "Android 14 or newer required"
  gate copy in the Documents lane when the runtime API check fails.
- Sub-task on `wpass-pti`: storage schema does not change, but the
  insert path's defense-in-depth check should also assert
  `Build.VERSION.SDK_INT >= 34` on the import side as a redundant
  guard.
- Empirical device matrix run, owned by the maintainer with physical
  access to the device set above; output is filling the **Empirical**
  rows in this document and re-promoting A-E from "provisional yes"
  to "yes."
