package `is`.walt.passes.image.android

import android.os.ParcelFileDescriptor
import android.os.SharedMemory

/**
 * The binder contract for the isolated-process image-decode service (wpass-6yp, step 3 of
 * wpass-i9x) — THE security gate for turning an untrusted user image into something Walt
 * displays. One method: [decode] takes the original image as a [ParcelFileDescriptor],
 * bounds-decodes it inside the sandbox, and returns a Walt-produced raster over
 * [SharedMemory] — never the source bytes, never a `Bitmap` the caller hands in, never image
 * metadata. The hostile image content never crosses back into the caller's address space;
 * only a bounded, freshly-rasterised pixel buffer does.
 *
 * Mirrors `passes-pdf`'s `PdfRendererBinder.render` (raster-over-SharedMemory), simplified to
 * a single "page": an image has no page count, so there is no `probe` arm. The
 * [ImageDecodeBinderSurfaceTest] reflection lock asserts the surface is exactly this one
 * method, so adding an extraction backdoor (a `decodeToBitmap`, a raw-bytes accessor, an EXIF
 * getter) is a structural compile-and-test failure rather than a code-review judgement call.
 *
 * Not AIDL: AIDL generates a Stub that exposes its own reflectable surface and a parser of
 * arbitrary remote-supplied data. A hand-rolled [android.os.Binder] subclass
 * ([ImageDecodeBinderProxy]) keeps the IPC surface to exactly the one transaction here,
 * mirroring the `PdfRendererBinder` / `BarcodeDecodeBinder` discipline.
 *
 * `public` (unlike `passes-barcode`'s module-private binder): the wpass-i9x image-document
 * display surface consumes this contract the way `passes-document-ui`'s `DocumentView` consumes
 * `PdfRendererBinder`. Hosting code takes the [ImageDecodeBinder] interface — never the
 * concrete [ImageDecodeClient] — so test fakes substitute cleanly.
 *
 * PFD ownership: the caller retains ownership of the [ParcelFileDescriptor] it passes. The
 * binder duplicates the underlying fd on the way across; the decode service owns and closes
 * its received copy. The returned [ImageDecodeResult.Ok.sharedMemory] is owned by the caller,
 * which must [SharedMemory.close] it once the raster has been consumed.
 */
public interface ImageDecodeBinder {
    /**
     * Decode the image behind [image] into an ARGB_8888 raster bounded to fit within
     * [maxWidthPx] x [maxHeightPx] (aspect-preserving; never upscaled beyond source), and
     * returned read-only over [SharedMemory]. The output dimension product is additionally
     * capped by the service ([ImageDecodeConfig.maxOutputPixels]) against a caller asking for
     * an arbitrarily large raster. Returns [ImageDecodeResult.Rejected] with a bucketed
     * [ImageDecodeRejectedKind] on any cap violation, malformed input, or decode failure.
     */
    public suspend fun decode(
        image: ParcelFileDescriptor,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): ImageDecodeResult
}

/**
 * Outcome of a single image decode. [Ok.sharedMemory] is read-only after construction; the
 * pixel layout is ARGB_8888 packed row-major with no padding, exactly the shape
 * `passes-pdf`'s `RenderResult.Ok` uses, so the host's existing SharedMemory-to-Bitmap unwrap
 * is reused unchanged.
 */
public sealed interface ImageDecodeResult {
    public data class Ok(
        public val sharedMemory: SharedMemory,
        public val widthPx: Int,
        public val heightPx: Int,
        // source width / height, so the host can reason about the original aspect ratio
        // (e.g. for a fixed slot) without re-reading the image. Mirrors RenderResult.pageAspect.
        public val sourceAspect: Float,
    ) : ImageDecodeResult

    public data class Rejected(public val kind: ImageDecodeRejectedKind) : ImageDecodeResult
}

/**
 * The reasons an image decode can be rejected, as its OWN closed taxonomy — deliberately NOT
 * flattened into `passes-document-core`'s `DocumentRejectedKind`. The two document kinds fail in
 * different ways (a PDF is encrypted or has too many pages; an image is not-an-image or a
 * decompression bomb), and folding them into one enum would force each consumer to branch on
 * arms that cannot occur for its kind. The wpass-i9x ImageDocument arm (step 4) maps these to
 * its own surface; this step owns only the decode taxonomy.
 *
 * Telemetry-safe by construction: payload-free arms, no string ever attached, matching the
 * `DocumentRejectedKind` / `DecodeFailureReason` discipline. A sealed interface (not an enum)
 * per the repo's stated style; [ImageDecodeRejectedKindWire] maps each arm to a stable wire
 * code so a reorder here cannot silently mis-decode downstream.
 *
 *  - [NotAnImage] — the container MIME is outside the still-image allowlist, or the bytes did
 *    not decode as an image at all (the MIME-spoof / wrong-file case).
 *  - [OversizedAtImport] — the compressed bytes exceeded the file-size cap before any decode
 *    (the large-file bomb shape); the oversized buffer is never fully read.
 *  - [DimensionsTooLarge] — the decoded-but-not-yet-allocated header advertised dimensions or
 *    an area over the caps (the small-file-huge-canvas decompression bomb); also the bucket a
 *    contained `OutOfMemoryError` folds to.
 *  - [DecodeFailed] — the platform decode threw, the raster scale/SharedMemory pack failed
 *    after a successful decode, or the caller requested an out-of-bounds output size. The
 *    underlying decoder error string is never reported.
 *  - [DecoderUnavailable] — the isolated decode process could not be bound, or went away
 *    (e.g. killed by the watchdog) before returning a result. Surfaced by the facade /
 *    client, not the in-sandbox decode.
 */
public sealed interface ImageDecodeRejectedKind {
    public data object NotAnImage : ImageDecodeRejectedKind

    public data object OversizedAtImport : ImageDecodeRejectedKind

    public data object DimensionsTooLarge : ImageDecodeRejectedKind

    public data object DecodeFailed : ImageDecodeRejectedKind

    public data object DecoderUnavailable : ImageDecodeRejectedKind
}
