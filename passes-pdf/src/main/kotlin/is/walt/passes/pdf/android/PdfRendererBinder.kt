package `is`.walt.passes.pdf.android

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import `is`.walt.passes.pdf.DocumentRejectedKind

/**
 * The internal binder contract for the isolated-process PDF renderer service. Two
 * methods, both load-bearing: [probe] returns the page count for a candidate PDF (or a
 * rejection enum), and [render] rasterises a single page (optionally a sub-rect of one)
 * into a SharedMemory-backed pixel buffer. The deliberate absence of getText,
 * getMetadata, getAnnotations, getAttachments, and getFormFields is the trust claim of
 * ADR 0005 D4 (no extraction from PDF content); [PublicApiSurfaceTest] enforces the
 * surface by reflection so adding one of those methods is a structural compile-and-test
 * failure rather than a code-review judgement call.
 *
 * Not AIDL: AIDL adds a generated Stub class that exposes its own reflectable surface
 * and a parser of arbitrary remote-supplied data. A hand-rolled [android.os.Binder]
 * subclass keeps the IPC surface to exactly the two transactions defined here.
 *
 * Both methods take a [ParcelFileDescriptor] rather than a path. The renderer never sees
 * a file path or a `String` payload; the file descriptor is duplicated by the binder and
 * the main process closes its copy. This is the second half of the D3 control: the
 * renderer cannot wander the filesystem because it has no path to wander to.
 *
 * PFD ownership: the caller retains ownership of the [ParcelFileDescriptor] passed in
 * and is responsible for closing it after the call returns. The binder duplicates the
 * underlying fd on the way across, so closing on the caller side does not affect the
 * renderer's copy.
 */
public interface PdfRendererBinder {
    public suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult

    /**
     * Rasterise [page] of the PDF behind [pdf] into a [widthPx]x[heightPx] ARGB_8888
     * buffer. [sourceRect] selects a portion of the page in normalised [0, 1] page
     * coordinates; invalid rects are rejected by the renderer (`wpass-f4b`). ADR 0005
     * D7's 4 MP cap applies to the output size and is unchanged by the sub-rect.
     */
    public suspend fun render(
        pdf: ParcelFileDescriptor,
        page: Int,
        widthPx: Int,
        heightPx: Int,
        sourceRect: RenderSourceRect = RenderSourceRect.FullPage,
    ): RenderResult
}

/**
 * Selects what portion of a PDF page is rasterised. Sealed so the proxy, client, and
 * service all fold over the same closed set; growing the surface (e.g. for tiled
 * rendering) is a deliberate change in all three.
 */
public sealed interface RenderSourceRect {
    /** Rasterise the entire page. The pre-`wpass-f4b` behaviour. */
    public data object FullPage : RenderSourceRect

    /**
     * Sub-rectangle in normalised page coordinates: `(0, 0)` is top-left, `(1, 1)` is
     * bottom-right. Invalid rects (outside the unit square, zero area, reversed) are
     * rejected by the renderer with [DocumentRejectedKind.RendererFailed].
     */
    public data class SubRect(
        public val left: Float,
        public val top: Float,
        public val right: Float,
        public val bottom: Float,
    ) : RenderSourceRect
}

/**
 * Outcome of the page-count probe. Modelled with the same enum-based rejection vocabulary
 * as the rest of `passes-pdf-core` so a consumer can fold probe and render rejections
 * into a single `when` over [DocumentRejectedKind] without a translation layer.
 */
public sealed interface ProbeResult {
    public data class Ok(public val pageCount: Int) : ProbeResult

    public data class Rejected(public val kind: DocumentRejectedKind) : ProbeResult
}

/**
 * Outcome of a single-page render. [Ok.sharedMemory] is read-only after construction
 * (the service calls `setProtect(PROT_READ)` before returning). The pixel layout is
 * ARGB_8888 packed row-major with no padding; the receiver is expected to reconstruct
 * the bitmap via [android.graphics.Bitmap.copyPixelsFromBuffer] against a bitmap of the
 * same dimensions.
 */
public sealed interface RenderResult {
    /**
     * [pageAspect] is the page's natural width/height ratio. Lets the UI compute where
     * inside the destination bitmap the actual page content lives so zoom math can
     * normalise against the on-screen page rect rather than the slot (`wpass-6ag` C3).
     */
    public data class Ok(
        public val sharedMemory: SharedMemory,
        public val widthPx: Int,
        public val heightPx: Int,
        public val pageAspect: Float,
    ) : RenderResult

    public data class Rejected(public val kind: DocumentRejectedKind) : RenderResult
}
