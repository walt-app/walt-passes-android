package `is`.walt.passes.pdf.android.internal

import android.graphics.Bitmap
import android.os.SharedMemory
import `is`.walt.passes.pdf.android.RenderResult
import java.io.ByteArrayOutputStream

/**
 * Reconstructs a [Bitmap] from a [RenderResult.Ok]'s [SharedMemory] buffer and encodes
 * it as PNG bytes for the storage thumbnail BLOB.
 *
 * Lifted to an internal seam so unit tests can plug in a deterministic fake. The
 * production [PngThumbnailEncoder] does the real `mapReadOnly` -> `copyPixelsFromBuffer`
 * -> `Bitmap.compress(PNG)` dance; tests don't exercise that path because Robolectric
 * shadows for the SharedMemory + Bitmap pipeline are not load-bearing for the
 * orchestration assertions.
 */
internal interface ThumbnailEncoder {
    fun encode(render: RenderResult.Ok): ByteArray
}

internal object PngThumbnailEncoder : ThumbnailEncoder {
    override fun encode(render: RenderResult.Ok): ByteArray {
        // Outer try/finally ensures `sharedMemory.close()` runs even if `mapReadOnly()`
        // itself throws — otherwise the SharedMemory handle leaks across the IPC
        // ownership boundary, which is exactly the surface this module spends most of
        // its energy locking down.
        try {
            val buffer = render.sharedMemory.mapReadOnly()
            try {
                val bitmap = Bitmap.createBitmap(render.widthPx, render.heightPx, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(buffer)
                    val baos = ByteArrayOutputStream()
                    // PNG is lossless; the quality argument is ignored. Pinning at 100 is
                    // documentation for the call-site reader.
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    return baos.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            render.sharedMemory.close()
        }
    }
}
