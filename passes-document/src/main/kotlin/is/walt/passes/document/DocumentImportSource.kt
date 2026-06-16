package `is`.walt.passes.document

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * The two shapes a document (PDF or image) can enter [DocumentImporter] in. Deliberately the
 * same closed pair as `passes-pdf`'s `PdfImportSource` and `passes-image`'s `ImageSource`:
 * every byte that reaches an isolated backend is sourced from either an Android
 * `ContentResolver` URI (the SAF / photo-picker path walt-android wires) or a
 * [ParcelFileDescriptor] the consumer has already opened.
 *
 * There is intentionally no `Path` / `File` / `ByteArray` arm, for the same reason the
 * per-backend sources omit one: a path arm would let a future contributor route bytes from
 * disk-cached locations the consumer never intended, and a `ByteArray` arm would defeat the
 * point of sourcing untrusted bytes through a descriptor.
 *
 * Ownership: the caller retains ownership of the [ContentResolver], the [Uri], and any
 * [ParcelFileDescriptor] passed in. The importer reads from these but does not close them;
 * closing remains the caller's responsibility once the suspend `import` returns.
 */
public sealed interface DocumentImportSource {
    public class ContentUri(
        public val uri: Uri,
        public val resolver: ContentResolver,
    ) : DocumentImportSource

    public class FileDescriptor(
        public val pfd: ParcelFileDescriptor,
    ) : DocumentImportSource
}
