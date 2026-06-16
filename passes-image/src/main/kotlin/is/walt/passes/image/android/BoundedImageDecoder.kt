package `is`.walt.passes.image.android

import android.content.Context

/**
 * One-shot facade over the isolated image-decode service: bind the `:imageDecoder` sandbox
 * through `passes-isolation`, decode one [ImageSource] to a bounded raster, and tear the
 * session down before returning. The wpass-i9x import path uses this directly (decode once,
 * persist the raster); the display path instead takes the [ImageDecodeBinder] interface and
 * holds a session open across re-renders, the way `passes-pdf-ui`'s `DocumentView` takes
 * `PdfRendererBinder`.
 *
 * Mirrors `passes-barcode`'s `BarcodeImageDecoder`: the facade is the convenient bind-per-call
 * entry point; the binder contract is the lower-level seam. The returned
 * [ImageDecodeResult.Ok.sharedMemory] outlives the torn-down session (its fd was duplicated
 * into this process across the binder) and is owned by the caller, which must close it.
 */
public interface BoundedImageDecoder {
    /**
     * Decode the image behind [source] to an ARGB_8888 raster bounded to fit within
     * [maxWidthPx] x [maxHeightPx], returned read-only over `SharedMemory`. The isolated
     * decode process is torn down before this method returns, regardless of outcome. The
     * caller retains ownership of [source] and is responsible for closing any
     * [android.os.ParcelFileDescriptor] it holds.
     */
    public suspend fun decode(
        source: ImageSource,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): ImageDecodeResult

    public companion object {
        public fun create(context: Context): BoundedImageDecoder =
            DefaultBoundedImageDecoder(context.applicationContext)
    }
}
