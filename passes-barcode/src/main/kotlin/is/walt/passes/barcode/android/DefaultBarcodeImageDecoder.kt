package `is`.walt.passes.barcode.android

import android.content.ContentResolver
import android.content.Context
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.isolation.AndroidIsolatedWorkerSessionFactory
import `is`.walt.passes.isolation.ConnectResult
import `is`.walt.passes.isolation.IsolatedWorkerSessionFactory

/**
 * Default [BarcodeImageDecoder]. Orchestrates one decode: open the source as a file
 * descriptor, bind the isolated decode service through the shared `passes-isolation` facade,
 * hand the fd across, collect the pure result, and tear the session down — in a `finally`,
 * so `unbindService` and the fd close run whether the decode succeeded, was rejected, or
 * threw.
 *
 * Trust-claim posture (wpass-zrt.2): the hostile image's bytes never enter this — the
 * caller's main — process. Unlike the PDF importer, which materializes and header-sniffs
 * bytes in the main process before binding, the decoder opens a [ParcelFileDescriptor] for
 * the source and passes *that* across the bind. The bytes are read only inside the sandbox,
 * where the bounded-decode caps (wpass-zrt.3) and ZXing decode (wpass-zrt.4) will run. That
 * is why this rides only the facade's bind-session half and not its memfd `PfdFactory`:
 * there is nothing to materialize here.
 *
 * Seams are folded into [Deps] so unit tests exercise the orchestration without a live bind
 * or a real `ContentResolver`; production callers never construct [Deps] because the public
 * [BarcodeImageDecoder.create] factory builds the production default.
 */
internal class DefaultBarcodeImageDecoder(
    private val appContext: Context,
    private val deps: Deps = Deps(),
) : BarcodeImageDecoder {
    internal data class Deps(
        val sessionFactoryFor: (Context) -> IsolatedWorkerSessionFactory<BarcodeDecodeBinder> = { ctx ->
            AndroidIsolatedWorkerSessionFactory(ctx, BarcodeDecodeService::class.java) { BarcodeDecodeClient(it) }
        },
        val openPfd: (BarcodeImageSource) -> ParcelFileDescriptor? = ::defaultOpenPfd,
    )

    private val sessionFactory: IsolatedWorkerSessionFactory<BarcodeDecodeBinder> by lazy {
        deps.sessionFactoryFor(appContext)
    }

    override suspend fun decode(source: BarcodeImageSource): BarcodeDecodeResult {
        val pfd =
            deps.openPfd(source)
                ?: return BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.SourceUnreadable)
        return try {
            when (val conn = sessionFactory.connect()) {
                ConnectResult.BindFailed ->
                    BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable)
                is ConnectResult.Connected -> conn.session.use { it.client.decode(pfd) }
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    internal companion object {
        /**
         * Open [source] as a [ParcelFileDescriptor] without reading its bytes into this
         * process. Mirrors the PDF importer's source discipline: the `content://` scheme
         * allowlist closes the `file://` escape hatch (`openFileDescriptor` would otherwise
         * resolve an arbitrary filesystem path), and the [BarcodeImageSource.FileDescriptor]
         * arm is `dup`'d so closing our copy never disturbs the caller's original fd.
         */
        internal fun defaultOpenPfd(source: BarcodeImageSource): ParcelFileDescriptor? =
            when (source) {
                is BarcodeImageSource.ContentUri ->
                    if (source.uri.scheme != ContentResolver.SCHEME_CONTENT) {
                        null
                    } else {
                        runCatching { source.resolver.openFileDescriptor(source.uri, "r") }.getOrNull()
                    }
                is BarcodeImageSource.FileDescriptor ->
                    runCatching { source.pfd.dup() }.getOrNull()
            }
    }
}
