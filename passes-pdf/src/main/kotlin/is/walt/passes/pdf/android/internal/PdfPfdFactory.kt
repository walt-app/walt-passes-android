package `is`.walt.passes.pdf.android.internal

import android.annotation.TargetApi
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/**
 * Creates a [ParcelFileDescriptor] backed by an anonymous in-RAM file (`memfd_create`).
 * The renderer service mmaps the PFD; no plaintext bytes ever touch disk (ADR 0005 F.1).
 *
 * The factory is an internal seam: production code wires [MemfdPfdFactory] (the real
 * `Os.memfd_create` path), unit tests wire a fake that hands back a pipe-backed PFD so
 * the orchestrator code path can be exercised without `Os.memfd_create` being reachable
 * from the JVM-side test runtime. The seam is *not* a public API — adding a third
 * implementation in walt-android would re-open the parallel-implementation gap that the
 * importer exists to close.
 */
internal interface PdfPfdFactory {
    /**
     * Allocate an in-memory PFD, write [bytes] into it, then rewind so the renderer
     * reads from offset 0.
     */
    fun fromBytes(bytes: ByteArray): ParcelFileDescriptor
}

// memfd_create lands in android.system.Os at API 30. The wider importer is gated on
// SDK_INT >= 34 (ADR 0005 G.1) before this factory is ever asked for a PFD; the
// @TargetApi narrows lint's view to that gate and the inline `check` is defence in
// depth: a future caller that wires the factory outside the importer's gate fails
// loudly here rather than crashing inside the libcore call.
@TargetApi(Build.VERSION_CODES.R)
internal class MemfdPfdFactory : PdfPfdFactory {
    override fun fromBytes(bytes: ByteArray): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "memfd_create requires Android 11 (API 30) or newer"
        }
        val fd = Os.memfd_create("walt-pdf-import", 0)
        try {
            // Os.write returns the count actually written; loop to drain the buffer in
            // case the kernel decides to short-write. memfd_create-backed fds in
            // practice accept the whole buffer in one go, but the loop is the
            // defensive shape that doesn't depend on that promise.
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(fd, bytes, offset, bytes.size - offset)
                if (written <= 0) {
                    error("memfd write stalled at offset=$offset")
                }
                offset += written
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            // ParcelFileDescriptor.dup duplicates the underlying fd; close ours so the
            // memfd is owned exclusively by the PFD we just handed back to the caller.
            // Os.close throws ErrnoException if the fd was already closed; runCatching
            // covers the case where dup() failed and dropped our ownership claim.
            runCatching { Os.close(fd) }
        }
    }
}
