package `is`.walt.passes.image.android

import android.content.ContentResolver
import android.content.Context
import android.os.ParcelFileDescriptor
import `is`.walt.passes.isolation.AndroidIsolatedWorkerSessionFactory
import `is`.walt.passes.isolation.ConnectResult
import `is`.walt.passes.isolation.IsolatedWorkerSessionFactory

/**
 * Production [BoundedImageDecoder]. Opens a descriptor for the [ImageSource], binds the
 * `:imageDecoder` sandbox through the shared `passes-isolation` session factory, runs one
 * decode over the typed [ImageDecodeClient], and unbinds in the `use {}` regardless of
 * outcome. Structurally identical to `passes-barcode`'s `DefaultBarcodeImageDecoder`; the only
 * differences are the service class / client it parameterises the factory with and the extra
 * output-bound arguments.
 *
 * [Deps] folds the two injectable seams into one record so the constructor stays small and
 * tests can substitute a fake session factory (no real `bindService`) and a fake PFD opener
 * (no real `ContentResolver`). Production callers never construct [Deps]; the
 * [BoundedImageDecoder.create] factory builds this with the production defaults.
 */
internal class DefaultBoundedImageDecoder(
    private val appContext: Context,
    private val deps: Deps = Deps(),
) : BoundedImageDecoder {
    internal data class Deps(
        val sessionFactoryFor: (Context) -> IsolatedWorkerSessionFactory<ImageDecodeBinder> = { ctx ->
            AndroidIsolatedWorkerSessionFactory(ctx, ImageDecodeService::class.java) { ImageDecodeClient(it) }
        },
        val openPfd: (ImageSource) -> ParcelFileDescriptor? = ::defaultOpenPfd,
    )

    private val sessionFactory: IsolatedWorkerSessionFactory<ImageDecodeBinder> by lazy {
        deps.sessionFactoryFor(appContext)
    }

    override suspend fun decode(
        source: ImageSource,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): ImageDecodeResult {
        val pfd =
            deps.openPfd(source)
                ?: return ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
        return try {
            when (val conn = sessionFactory.connect()) {
                ConnectResult.BindFailed ->
                    ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecoderUnavailable)
                is ConnectResult.Connected -> conn.session.use { it.client.decode(pfd, maxWidthPx, maxHeightPx) }
            }
        } finally {
            // We own `pfd`: a freshly opened descriptor for ContentUri, or a dup() for
            // FileDescriptor. Closing it never closes the caller's original.
            runCatching { pfd.close() }
        }
    }

    internal companion object {
        internal fun defaultOpenPfd(source: ImageSource): ParcelFileDescriptor? =
            when (source) {
                is ImageSource.ContentUri ->
                    if (source.uri.scheme != ContentResolver.SCHEME_CONTENT) {
                        null
                    } else {
                        runCatching { source.resolver.openFileDescriptor(source.uri, "r") }.getOrNull()
                    }
                is ImageSource.FileDescriptor ->
                    runCatching { source.pfd.dup() }.getOrNull()
            }
    }
}
