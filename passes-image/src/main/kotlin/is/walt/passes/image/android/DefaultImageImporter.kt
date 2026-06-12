package `is`.walt.passes.image.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ImageDocumentId
import `is`.walt.passes.image.ImageFormat
import `is`.walt.passes.image.ImageImportConfig
import `is`.walt.passes.image.ImageImportFailedEvent
import `is`.walt.passes.image.ImageImportResult
import `is`.walt.passes.image.ImageImportSucceededEvent
import `is`.walt.passes.image.ImageRejectedKind
import `is`.walt.passes.image.sniffImageFormat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The orchestration sequence is the load-bearing contract:
 *
 *  1. Materialize the source to a bounded byte buffer (fail-fast size cap).
 *  2. Header-sniff the first bytes — before any decode work.
 *  3. Bounds-only decode to get dimensions; reject if either exceeds [ImageImportConfig.maxDimensionPx].
 *  4. Full decode at reduced sample size → PNG-compress the thumbnail.
 *  5. Hand off to the consumer's `persist` lambda.
 *
 * `@Suppress("ReturnCount")` matches the precedent set by `DefaultPdfImporter`: each
 * stage is its own short-circuit, and collapsing returns into monadic plumbing adds
 * indirection without payoff at this size.
 */
internal class DefaultImageImporter(
    private val config: ImageImportConfig,
    private val deps: Deps = Deps(),
) : ImageImporter {

    internal data class Deps(
        val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
        val idGenerator: () -> String = { UUID.randomUUID().toString() },
    )

    @Suppress("ReturnCount")
    override suspend fun import(
        source: ImageImportSource,
        displayLabel: String,
        persist: suspend (String, ByteArray, ImageFormat, Int, Int, ByteArray) -> Unit,
    ): ImageImportResult {
        val startedAt = deps.now()
        config.telemetryGuard.onImportStarted()

        val bytes = when (val r = readBounded(source, config.maxBytes)) {
            is BoundedRead.Bytes -> r.bytes
            BoundedRead.Oversized -> return rejectAndReport(ImageRejectedKind.OversizedAtImport, startedAt)
            BoundedRead.SourceUnavailable -> return rejectAndReport(ImageRejectedKind.NotAnImage, startedAt)
        }

        val format = sniffImageFormat(bytes)
            ?: return rejectAndReport(ImageRejectedKind.NotAnImage, startedAt)

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val width = boundsOpts.outWidth
        val height = boundsOpts.outHeight
        if (width <= 0 || height <= 0) return rejectAndReport(ImageRejectedKind.DecodeFailed, startedAt)
        if (maxOf(width, height) > config.maxDimensionPx) {
            return rejectAndReport(ImageRejectedKind.TooLargeToImport, startedAt)
        }

        val thumbnailBytes = decodeAndEncodeThumbnail(bytes)
            ?: return rejectAndReport(ImageRejectedKind.DecodeFailed, startedAt)

        runCatching { persist(displayLabel, bytes, format, width, height, thumbnailBytes) }
            .getOrElse { t ->
                if (t is CancellationException) throw t
                return rejectAndReport(ImageRejectedKind.StorageHandoffFailed, startedAt)
            }

        val doc = ImageDocument(
            id = ImageDocumentId(deps.idGenerator()),
            displayLabel = displayLabel,
            byteCount = bytes.size.toLong(),
            format = format,
            widthPx = width,
            heightPx = height,
            importedAtEpochMs = System.currentTimeMillis(),
        )
        config.telemetryGuard.onImportSucceeded(
            ImageImportSucceededEvent(
                byteCount = doc.byteCount,
                format = doc.format,
                widthPx = doc.widthPx,
                heightPx = doc.heightPx,
                durationMillis = deps.now() - startedAt,
            ),
        )
        return ImageImportResult.Imported(doc)
    }

    private fun decodeAndEncodeThumbnail(bytes: ByteArray): ByteArray? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (maxSide / sampleSize > THUMB_MAX_PX) sampleSize *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return null
        return try {
            ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun readBounded(source: ImageImportSource, maxBytes: Long): BoundedRead {
        val stream = openSource(source) ?: return BoundedRead.SourceUnavailable
        return stream.use { drainBounded(it, maxBytes) }
    }

    private fun openSource(source: ImageImportSource): InputStream? =
        when (source) {
            is ImageImportSource.ContentUri -> {
                // Scheme allowlist: ContentResolver.openInputStream will happily resolve a
                // `file://` URI to an arbitrary filesystem path, which is exactly the
                // path-arm escape hatch the sealed ImageImportSource shape was supposed to
                // close. Refusing non-`content://` schemes here keeps the implementation
                // aligned with the documented threat model on ImageImportSource.
                if (source.uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    null
                } else {
                    runCatching { source.resolver.openInputStream(source.uri) }.getOrNull()
                }
            }
            is ImageImportSource.FileDescriptor -> {
                val dup = runCatching { source.pfd.dup() }.getOrNull()
                dup?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }
        }

    @Suppress("ReturnCount")
    private fun drainBounded(input: InputStream, maxBytes: Long): BoundedRead {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        val ceiling = maxBytes + 1
        while (total < ceiling) {
            val want = minOf(buf.size.toLong(), ceiling - total).toInt()
            val n = runCatching { input.read(buf, 0, want) }
                .getOrElse { return BoundedRead.SourceUnavailable }
            if (n < 0) break
            baos.write(buf, 0, n)
            total += n
        }
        return if (total > maxBytes) BoundedRead.Oversized else BoundedRead.Bytes(baos.toByteArray())
    }

    private fun rejectAndReport(kind: ImageRejectedKind, startedAt: Long): ImageImportResult.Rejected {
        config.telemetryGuard.onImportFailed(
            ImageImportFailedEvent(outcome = kind, durationMillis = deps.now() - startedAt),
        )
        return ImageImportResult.Rejected(kind)
    }

    private sealed interface BoundedRead {
        data class Bytes(val bytes: ByteArray) : BoundedRead
        data object Oversized : BoundedRead
        data object SourceUnavailable : BoundedRead
    }

    internal companion object {
        const val COPY_BUFFER_SIZE: Int = 64 * 1024
        const val THUMB_MAX_PX: Int = 512
    }
}
