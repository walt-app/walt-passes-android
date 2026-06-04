package `is`.walt.passes.isolation

import android.annotation.TargetApi
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/**
 * Materializes a byte payload into a [ParcelFileDescriptor] suitable for handing across a
 * bind to an isolated worker service. The shared seam both [is.walt.passes.isolation]
 * consumers reach for when they need to put *bytes already in the main process* into an
 * fd the sandbox can mmap — no plaintext touches disk (ADR 0005 F.1).
 *
 * Not every consumer needs this: a worker whose hostile input must never enter the main
 * process at all (e.g. image decode) hands the *source* fd across directly instead of
 * reading the bytes here. Those consumers use only the bind-session facade and skip this
 * factory. The factory exists for the PDF path, which header-sniffs and size-bounds the
 * bytes in the main process before binding and therefore already holds them in RAM.
 *
 * Kept behind an interface so unit tests can inject a pipe-backed fake (the real
 * [MemfdPfdFactory] calls `Os.memfd_create`, which is not reachable from the JVM-side test
 * runtime). The same single-implementation discipline applies as before consolidation:
 * adding a third production implementation in walt-android would re-open the
 * parallel-implementation gap this shared module exists to close.
 */
public interface PfdFactory {
    /**
     * Allocate an in-memory PFD, write [bytes] into it, then rewind so the worker reads
     * from offset 0.
     */
    public fun fromBytes(bytes: ByteArray): ParcelFileDescriptor
}

/**
 * The production [PfdFactory]: an anonymous in-RAM file via `memfd_create`. [debugName] is
 * the kernel's purely-cosmetic label for the fd (visible in `/proc/<pid>/fd`); consumers
 * pass a per-worker name so a leaked fd is attributable.
 *
 * memfd_create lands in `android.system.Os` at API 30. Both consumers gate their import
 * paths on a higher floor before this factory is asked for a PFD; the [TargetApi] narrows
 * lint's view to API 30 and the inline `check` is defence in depth — a future caller that
 * wires the factory below that gate fails loudly here rather than crashing inside libcore.
 */
@TargetApi(Build.VERSION_CODES.R)
public class MemfdPfdFactory(
    private val debugName: String,
) : PfdFactory {
    override fun fromBytes(bytes: ByteArray): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "memfd_create requires Android 11 (API 30) or newer"
        }
        val fd = Os.memfd_create(debugName, 0)
        try {
            // Os.write returns the count actually written; loop to drain the buffer in
            // case the kernel short-writes. memfd-backed fds accept the whole buffer in
            // one go in practice, but the loop is the defensive shape.
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
            // memfd is owned exclusively by the PFD handed back to the caller. runCatching
            // covers the case where dup() failed and already dropped our ownership claim.
            runCatching { Os.close(fd) }
        }
    }
}
