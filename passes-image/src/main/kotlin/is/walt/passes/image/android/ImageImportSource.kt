package `is`.walt.passes.image.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * The two shapes an image can enter the importer in.
 *
 * Sealed by design: every byte that reaches the decoder has to be sourced from either an
 * Android [ContentResolver] URI (the SAF / `ACTION_OPEN_DOCUMENT` path the walt-android
 * consumer wires) or from a [ParcelFileDescriptor] the consumer has already opened.
 * There is intentionally no `Path` / `File` arm: trust-claim-bearing import code never
 * opens a filesystem path itself, because once a path-arm exists a future contributor
 * can quietly route bytes from disk-cached locations the consumer never intended
 * (downloads cache, app-private files exposed by a debug FileProvider). The two arms
 * together cover every legitimate user-initiated import without admitting that surface.
 *
 * Ownership: the caller retains ownership of the [ContentResolver], the [Uri], and any
 * [ParcelFileDescriptor] passed in. The importer reads from these but does not close
 * them; closing remains the caller's responsibility once the suspend `import` returns.
 */
public sealed interface ImageImportSource {
    public class ContentUri(
        public val uri: Uri,
        public val resolver: ContentResolver,
    ) : ImageImportSource

    public class FileDescriptor(
        public val pfd: ParcelFileDescriptor,
    ) : ImageImportSource
}
