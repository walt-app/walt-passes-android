package `is`.walt.passes.image.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * The two shapes an original user image can enter the decoder in. Deliberately the same closed
 * pair as `passes-barcode`'s `BarcodeImageSource` and `passes-pdf`'s `PdfImportSource`: every
 * byte that reaches the isolated decode process is sourced from either an Android
 * `ContentResolver` URI (the `ACTION_OPEN_DOCUMENT` / photo-picker path the walt-android
 * consumer wires) or a [ParcelFileDescriptor] the consumer has already opened.
 *
 * There is intentionally no `Path` / `File` / `ByteArray` arm. A path arm would let a future
 * contributor route bytes from disk-cached locations the consumer never intended; a `ByteArray`
 * arm would mean the hostile image had already been pulled into the caller's main-process heap
 * before reaching the sandbox, defeating the whole isolation point. The image crosses to the
 * decode process only as a file descriptor.
 *
 * Ownership: the caller retains ownership of the [ContentResolver], the [Uri], and any
 * [ParcelFileDescriptor] passed in. The decoder reads from these but does not close them;
 * closing remains the caller's responsibility once `decode` returns.
 */
public sealed interface ImageSource {
    public class ContentUri(
        public val uri: Uri,
        public val resolver: ContentResolver,
    ) : ImageSource

    public class FileDescriptor(
        public val pfd: ParcelFileDescriptor,
    ) : ImageSource
}
