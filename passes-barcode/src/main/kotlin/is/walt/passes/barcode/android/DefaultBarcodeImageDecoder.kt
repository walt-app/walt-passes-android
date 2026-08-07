package `is`.walt.passes.barcode.android

import android.content.ContentResolver
import android.content.Context
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.isolation.AndroidIsolatedWorkerSessionFactory
import `is`.walt.passes.isolation.ConnectResult
import `is`.walt.passes.isolation.IsolatedWorkerSessionFactory
import kotlinx.coroutines.withTimeoutOrNull

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
            when (val conn = connectWithinBudget()) {
                null, ConnectResult.BindFailed ->
                    BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.DecoderUnavailable)
                is ConnectResult.Connected -> conn.session.use { it.client.decode(pfd) }
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Bind, or give up. `bindService` reports an outright refusal synchronously, but a bind that
     * is accepted and whose service then dies before `onServiceConnected` never resumes at all —
     * the facade has no deadline of its own (wpass-67l). Without this bound the decode path could
     * hang indefinitely, and since wpass-qw3 moved sandbox warm-up into `onCreate` that window
     * covers the warm-up too. Returns null on expiry, which the caller folds to
     * `DecoderUnavailable` — accurate, because no decode was ever attempted.
     *
     * The `also` narrows the teardown race. `connect()`'s own `invokeOnCancellation` unbinds
     * while it is still suspended, but that hook is detached once it resumes — so a session that
     * materializes just as the deadline fires would be discarded by `withTimeoutOrNull` still
     * bound. Capturing on the way out lets it be closed here. What this does NOT cover is a
     * continuation resumed after cancellation, whose value the dispatcher drops without running
     * this block at all; closing that needs the facade to resume with an onCancellation handler
     * (wpass-67l). The residue is one bound sandbox in a sub-millisecond window.
     */
    private suspend fun connectWithinBudget(): ConnectResult<BarcodeDecodeBinder>? {
        var connected: ConnectResult<BarcodeDecodeBinder>? = null
        val result =
            withTimeoutOrNull(DEFAULT_BIND_TIMEOUT_MS) {
                sessionFactory.connect().also { connected = it }
            }
        if (result == null) {
            (connected as? ConnectResult.Connected)?.session?.let { session ->
                runCatching { session.close() }
            }
        }
        return result
    }

    internal companion object {
        /**
         * Liveness backstop on the bind, NOT a performance bound. Lives here rather than on
         * [BarcodeDecodeConfig] because it bounds the host's wait, not anything the sandbox
         * enforces. Deliberately loose — several times the worst cold start observed on a loaded
         * 2-vCPU emulator — so a slow sandbox is never mistaken for a dead one. Tightening it
         * would recreate the flake it exists to bound.
         */
        const val DEFAULT_BIND_TIMEOUT_MS: Long = 20_000L

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
