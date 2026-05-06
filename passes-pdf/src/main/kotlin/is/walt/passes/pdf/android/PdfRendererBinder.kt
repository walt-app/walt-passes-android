package `is`.walt.passes.pdf.android

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import `is`.walt.passes.pdf.DocumentRejectedKind

/**
 * The internal binder contract for the isolated-process PDF renderer service. Two
 * methods, both load-bearing: [probe] returns the page count for a candidate PDF (or a
 * rejection enum), and [render] rasterises a single page into a SharedMemory-backed
 * pixel buffer. The deliberate absence of getText, getMetadata, getAnnotations,
 * getAttachments, and getFormFields is the trust claim of ADR 0005 D4 (no extraction
 * from PDF content); [PublicApiSurfaceTest] enforces the surface by reflection so adding
 * one of those methods is a structural compile-and-test failure rather than a code-review
 * judgement call.
 *
 * Not AIDL: AIDL adds a generated Stub class that exposes its own reflectable surface
 * and a parser of arbitrary remote-supplied data. A hand-rolled [android.os.Binder]
 * subclass keeps the IPC surface to exactly the two transactions defined here.
 *
 * Both methods take a [ParcelFileDescriptor] rather than a path. The renderer never sees
 * a file path or a `String` payload; the file descriptor is duplicated by the binder and
 * the main process closes its copy. This is the second half of the D3 control: the
 * renderer cannot wander the filesystem because it has no path to wander to.
 */
public interface PdfRendererBinder {
    public suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult

    public suspend fun render(
        pdf: ParcelFileDescriptor,
        page: Int,
        widthPx: Int,
        heightPx: Int,
    ): RenderResult
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
    public data class Ok(
        public val sharedMemory: SharedMemory,
        public val widthPx: Int,
        public val heightPx: Int,
    ) : RenderResult

    public data class Rejected(public val kind: DocumentRejectedKind) : RenderResult
}
