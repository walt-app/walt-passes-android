# ADR 0008: live-camera barcode/QR decode runs in-process, not in the isolated sandbox

- Status: Accepted
- Date: 2026-06-05
- Tracks: `wpass-7xo.4` (this decision), `wpass-7xo` (live-camera kernel
  epic), `wpass-7xo.1` (the completed security analysis this ADR ratifies),
  `wpass-7xo.2` (extract pure-JVM `passes-barcode-core`),
  `wpass-7xo.5` (pure-JVM YUV-frame adapter). Consumer-side capture pipeline
  is walt-android `wlt-mwj`.
- Decision context: extends ADR 0005 D3 (the isolated-process renderer is a
  mechanism that contains a native codec surface, not a blanket requirement)
  and the `passes-barcode` / `passes-barcode-core` split recorded in the repo
  charter. The static-image decode path (epic `wpass-zrt`, consumer
  `wlt-58a`) stays isolated; this ADR scopes why the live path does not.

## Context

`wpass-zrt` shipped barcode/QR decode from a static image file inside the
`:barcodeDecoder` isolated-process sandbox (`passes-barcode`,
`BarcodeImageDecoder`). The sandbox exists because the static path parses
attacker-controlled compressed containers: a hostile `.webp` / `.png` /
`.jpg` / `.gif` is handed to a native still-image codec
(libwebp / libjpeg-turbo / libpng / Skia) before ZXing ever sees pixels. That
native-codec step is a live, weaponized remote-code-execution surface
(libwebp CVE-2023-4863 on CISA KEV; Skia/PNG CVE-2019-1987; Samsung Quram DNG
CVE-2025-21042 used in the wild to deploy LANDFALL spyware). Isolating the
codec means a use-after-free there compromises an empty permissionless
sandbox that returns to the OS on crash, never the main process that holds
the SQLCipher key, the Keystore session, and decoded bytes from other passes.
This is the exact load-bearing argument of ADR 0005 D3 for PDF, applied to
the still-image codec.

The live-camera epic (`wpass-7xo`) asks: a user points the camera at a
bar/QR code and Walt imports it as a new pass. The natural question is
whether the live path must also run in the `:barcodeDecoder` sandbox, since
it shares the ZXing decode core. The completed security analysis
(`wpass-7xo.1`, deep-research harness: 25 claims adversarially verified,
3-vote majority-refute kills, 22 confirmed; plus a code map of
`passes-barcode` / `passes-isolation`) answers no. This ADR records that
decision and the boundary conditions that keep it true.

## Decisions

### D1. Live decode runs in-process; the codec-RCE vector is structurally absent

The live-camera decode path runs **in the main process**, not in the
`:barcodeDecoder` isolated sandbox.

The sandbox's reason to exist is to contain a native still-image codec
operating on attacker-chosen encoded bytes. CameraX `ImageAnalysis` emits
**already-decoded raw sensor pixels** (`YUV_420_888` by default,
`RGBA_8888` opt-in). ZXing's `PlanarYUVLuminanceSource` consumes only the
planar Y-luminance `byte[]` with crop / window / rotate array ops and
performs **zero** JPEG / PNG / WebP / GIF / Skia container parsing — a
verifier read the master-branch source (`final byte[] yuvData`, pure
`System.arraycopy` and indexing, no codec imports). There is therefore no
native still-image codec on the raw-YUV path. The single worst vector that
justified isolating the static path is not merely mitigated on the live
path; it is **structurally not invoked**.

What the live path adds is `com.google.zxing:core`, which is Apache-2.0 and
100% JVM: it contributes only pure-JVM logic-bug / DoS risk (GC pressure on
continuous scan), never native memory-safety / RCE. Every documented ZXing
memory-corruption CVE (CVE-2021-28021 / -42715 / -42716) lives in the C++
`zxing-cpp` / `stb_image.h` static-container path, not the JVM YUV class.
The live decode's risk is therefore identical *in kind* to the static
path's pure decode layer, while the static path *additionally* carries the
native codec surface. Isolating an in-kind-identical, native-free
computation buys nothing.

Per ADR 0005 D3 and the repo charter, isolation is **mechanism for the
codec surface, not a blanket rule**. With no codec to contain, in-process
is the correct placement.

### D2. The `passes-isolation` transport cannot stream frames anyway

Independent of the security argument, the shared isolation plumbing is the
wrong tool for live capture. `passes-isolation` is a one-shot
`fromBytes(ByteArray)` materialized over a synchronous, blocking
`transact()` (ADR 0005 D3, F.1 memfd handoff). It is built to ferry a single
self-contained document into the sandbox and return one result. It cannot
stream ~30 fps camera frames. So the sandbox is both **unnecessary** (no
codec to contain, D1) and **unsuitable** (cannot stream) for live decode.
Forcing the live path through it would degrade the feature without buying
any containment.

### D3. The static-image path stays isolated — no change

This ADR narrows nothing about `wpass-zrt`. Any path that parses an
attacker-controlled compressed container still routes through the existing
isolated `BarcodeImageDecoder`. In particular, a future "scan from a saved
photo" affordance MUST use `ImageCapture`'s sibling — the isolated
still-image decode — and MUST NOT add a new in-process codec call.
`ImageCapture` yields a compressed JPEG; decoding any saved photo routes
bytes back through a still-image codec and reintroduces exactly the
libwebp / Skia / Quram surface D1 avoids. The in-process placement is a
property of the **raw-YUV `ImageAnalysis`** path specifically, not of
"barcode decode" generally.

### D4. The kernel decode core stays pure-JVM and declares no CAMERA permission

Both paths call **one** decode implementation: the pure-JVM
`decodeLuminance(LuminanceSource): BarcodeDecodeResult` in
`passes-barcode-core` (extracted in `wpass-7xo.2`), plus the symbology
allowlist (`ROSTER_BY_ZXING_FORMAT`, `DECODE_HINTS`). The static path feeds
it an `RGBLuminanceSource` built from a decoded `Bitmap`; the live path feeds
it a `PlanarYUVLuminanceSource` built from the camera Y-plane (the adapter in
`wpass-7xo.5`). The kernel takes **no** CameraX dependency and declares
**no** `android.permission.CAMERA`: the consumer extracts the Y plane and
passes the `byte[]` + width / height / rowStride in. This keeps the
trust-claim-bearing decode in this repository and pure-JVM, consistent with
the charter's "`passes-barcode-core` has no Android framework dependencies"
rule.

The CONSUMER (walt-android `wlt-mwj`) owns the CameraX `ImageAnalysis`
pipeline, the preview UI, the `CAMERA` runtime-permission request, lifecycle,
foreground-only discipline, the user-confirmation sheet, and routing the
decoded payload through `QrPayloadClassifier` + `ScannableCardInputValidator`.

## Consequences

- The live-camera kernel work (`wpass-7xo.5` adapter, `wpass-7xo.6` tests)
  proceeds without binding the `:barcodeDecoder` service. The
  `BarcodeImageDecoder` facade, the memfd transport, and the sandbox do not
  extend to live frames; this is a new in-process seam, not a tweak to the
  isolated facade.
- The genuine new cost of live scanning is the consumer-declared `CAMERA`
  permission — a privacy / capability / blast-radius consideration and a
  product decision, **not** an RCE vector. Card material (DPAN / LUK) stays
  Keystore-protected and is unreachable from the camera / ZXing path by any
  memory-corruption route. Platform controls bound the permission (Android
  11+ one-time grants, Android 12+ status-bar privacy indicator); these are
  weaker than "the OS structurally bars background camera for a compromised
  app" — that stronger claim was **refuted** in the analysis and must not be
  relied on. Foreground-only camera use is a self-imposed design discipline,
  not an OS guarantee.
- Net trust posture: enabling live scanning does **not** meaningfully enlarge
  the exploitable surface and is **safer** than the static path on the
  catastrophic-RCE axis, because it omits the native codec the static path
  must contain. This ADR is the canonical reference when a future contributor
  asks "why isn't the camera decode sandboxed like the image decode?"

## Load-bearing constraints (contractual)

1. **Raw-YUV `ImageAnalysis` ONLY.** Never `ImageCapture` / JPEG on the
   in-process path. Any "scan from saved photo" affordance routes through the
   existing isolated `BarcodeImageDecoder` (D3), not a new in-process codec
   call. This constraint is what keeps D1 true; violating it silently
   reintroduces the native codec surface in the unsandboxed process.
2. **Faithful payload return; no auto-act.** The decode layer emits only
   `{payload, ScannableFormat}` (`BarcodeDecodeResult`) with no
   interpretation. The decoded payload (a malicious URL / crafted string) is
   the same logical threat regardless of static-vs-live capture and is
   mitigated DOWNSTREAM by classification / validation + explicit user
   confirmation, never in the capture layer.
3. **Foreground-only camera discipline** (self-imposed; the OS does not
   structurally bar background camera for a compromised app).
4. **Pure-JVM ZXing, not ML Kit**, and the kernel decode core declares no
   `CAMERA` permission and stays pure-JVM (decision unchanged from
   `wpass-zrt`; even bundled ML Kit is closed-source and fails the degoogled
   walt.is auditability posture).

## Open follow-ups

- `wpass-7xo.3` (RESOLVED 2026-06-05): OEM HAL `ImageAnalysis` output-format
  spike. Outcome: D1's "no codec" claim holds **structurally** across every
  walt.is target class (Pixel/GrapheneOS `FULL`/`LEVEL_3` → degoogled
  `LIMITED`+ → stock OEM → `LEGACY` tail). CameraX `ImageAnalysis` delivers raw
  demosaiced `YUV_420_888` by default; the still-image codec is a *separate*
  camera2 output format (`ImageFormat.JPEG`/HEIC) consumed only by
  `ImageCapture`, which the live path does not bind, so no OEM HAL turns the
  analysis stream into a codec-decoded one. `IMPLEMENTATION_DEFINED`/`PRIV` is
  the opaque GPU/preview format, never delivered as readable bytes to an
  `Analyzer`. The only cross-HAL variability is plane geometry (`rowStride`
  padding, `pixelStride` interleave, ZXing #1387), which the kernel
  `decodeYPlane(byte[] + width/height/rowStride/pixelStride/reverseHorizontal)`
  signature already absorbs — **no contract change**. Lone residual: a
  `LEGACY`-only 1-pixel luminance-shift in CameraX's RGB→YUV conversion
  (Play-channel old-hardware tail only; Pixels are never `LEGACY`) — a quality
  artifact, not a codec, within binarizer tolerance. Consumer usage constraint:
  keep `ImageAnalysis` on the default `YUV_420_888` and feed `getPlanes()[0]`
  (Y); never feed an `RGBA_8888` plane into `decodeYPlane`. Full device ×
  output-format matrix in the `wpass-7xo.3` design field; on-device per-model
  confirmation is the consumer's `wlt-mwj.6`.
- The downstream classification / validation + user-confirmation contract for
  payloads (URL allowlisting, scheme restrictions, B3-style confirmation
  sheet) is a CONSUMER (`wlt-mwj`) responsibility, reusing `QrPayloadClassifier`
  + `ScannableCardInputValidator`. It carries equal risk across static and
  live capture and is out of scope for this placement decision.
