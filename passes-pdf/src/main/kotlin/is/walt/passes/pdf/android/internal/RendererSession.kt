package `is`.walt.passes.pdf.android.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.PdfRendererClient
import `is`.walt.passes.pdf.android.PdfRendererService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Owns the bind / unbind pair for a single renderer-service session. The importer
 * obtains a [RendererSession] for the duration of one import call, uses [client] for
 * `probe` and `render`, and closes the session in a `finally` block — which guarantees
 * `unbindService` runs whether the import succeeded, was rejected at any step, or threw.
 *
 * Lifted to an internal seam so unit tests can inject a fake that records bind / unbind
 * calls without an actual `bindService` round trip. The default [AndroidRendererSessionFactory]
 * does the real thing.
 */
internal interface RendererSession : AutoCloseable {
    val client: PdfRendererBinder

    override fun close()
}

internal interface RendererSessionFactory {
    suspend fun connect(): RendererSession
}

internal class AndroidRendererSessionFactory(
    private val context: Context,
) : RendererSessionFactory {
    override suspend fun connect(): RendererSession =
        suspendCancellableCoroutine { cont ->
            val intent = Intent(context, PdfRendererService::class.java)
            lateinit var conn: ServiceConnection
            conn =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                        cont.resume(AndroidRendererSession(context, conn, PdfRendererClient(binder)))
                    }

                    // onServiceDisconnected fires after a successful connect — the binder
                    // is gone but the connection record remains. The session's close()
                    // unbinds either way, so nothing to do here. The active transact will
                    // surface as RemoteException → RendererFailed via PdfRendererClient,
                    // which is the documented runtime failure path.
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
            val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                runCatching { context.unbindService(conn) }
                cont.resume(FailedSession)
            }
            cont.invokeOnCancellation {
                runCatching { context.unbindService(conn) }
            }
        }
}

/**
 * Returned when `bindService` itself returns false (the service is missing from the
 * manifest or the system rejected the bind). Surfacing the connect failure as a
 * "session whose every call rejects" rather than throwing keeps the error path inside
 * the importer's existing rejection vocabulary.
 */
private object FailedSession : RendererSession {
    override val client: PdfRendererBinder =
        RejectingBinder(`is`.walt.passes.pdf.DocumentRejectedKind.RendererFailed)

    override fun close() = Unit
}

private class AndroidRendererSession(
    private val context: Context,
    private val connection: ServiceConnection,
    override val client: PdfRendererBinder,
) : RendererSession {
    override fun close() {
        runCatching { context.unbindService(connection) }
    }
}
