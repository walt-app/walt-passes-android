package `is`.walt.passes.isolation

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Owns the bind / unbind pair for a single isolated-worker session. A consumer connects
 * for the duration of one operation, makes one or more transactions through [client], and
 * closes the session in a `finally` — which guarantees `unbindService` runs whether the
 * operation succeeded, was rejected at any step, or threw.
 *
 * This is the shared substrate the PDF renderer and the barcode decoder both bind through.
 * Before consolidation each had its own copy of this bind dance; the copies had already
 * drifted (one represented connect failure as a rejecting session, another as `null`).
 * Folding them into one type and one connect-failure discipline ([ConnectResult]) is what
 * wpass-zrt.6 / wlt-ygl exist to do.
 *
 * [client] is the typed wrapper a caller hands to [AndroidIsolatedWorkerSessionFactory];
 * this module never interprets it, so the per-worker binder contract and its result shape
 * stay in the consumer module.
 */
public interface IsolatedWorkerSession<out C> : AutoCloseable {
    public val client: C

    override fun close()
}

/** Connects to an isolated worker service, yielding a [ConnectResult]. */
public interface IsolatedWorkerSessionFactory<out C> {
    public suspend fun connect(): ConnectResult<C>
}

/**
 * Outcome of a connect attempt. Modelling connect failure as an explicit arm — rather than
 * a session whose every call rejects, or a nullable session — is the single discipline the
 * consolidation settles on: each consumer folds [BindFailed] into its own domain rejection
 * vocabulary at the call site, and this module stays free of any domain reject type.
 */
public sealed interface ConnectResult<out C> {
    public data class Connected<C>(public val session: IsolatedWorkerSession<C>) : ConnectResult<C>

    /**
     * `bindService` returned false (the service is missing from the manifest or the system
     * refused the bind). No connection was established, so there is nothing to unbind.
     */
    public data object BindFailed : ConnectResult<Nothing>
}

/**
 * The production factory: binds [serviceClass] in the consumer's process group and wraps
 * the returned [IBinder] with [wrapBinder] into the caller's typed client. Lifted to an
 * interface seam so unit tests inject a fake that records connect / close without an actual
 * `bindService` round trip.
 */
public class AndroidIsolatedWorkerSessionFactory<out C>(
    private val context: Context,
    private val serviceClass: Class<out Service>,
    private val wrapBinder: (IBinder) -> C,
) : IsolatedWorkerSessionFactory<C> {
    override suspend fun connect(): ConnectResult<C> =
        suspendCancellableCoroutine { cont ->
            val intent = Intent(context, serviceClass)
            lateinit var conn: ServiceConnection
            conn =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                        // BIND_AUTO_CREATE re-fires this if the isolated process dies and is
                        // recreated while still bound — the routine hostile-input crash this
                        // gate exists for. The continuation resumes exactly once; a second
                        // resume would throw on the main thread and take the wallet down with
                        // the sandbox. Skip the rebind: the in-flight transact already surfaced
                        // the death as a RemoteException on the consumer's client.
                        if (!cont.isActive) return
                        cont.resume(ConnectResult.Connected(AndroidSession(context, conn, wrapBinder(binder))))
                    }

                    // onServiceDisconnected fires after a successful connect — the binder
                    // is gone but the connection record remains. The session's close()
                    // unbinds either way, so nothing to do here. An active transact will
                    // surface as RemoteException on the consumer's client, which is the
                    // documented runtime failure path.
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
            val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                runCatching { context.unbindService(conn) }
                cont.resume(ConnectResult.BindFailed)
            }
            cont.invokeOnCancellation {
                runCatching { context.unbindService(conn) }
            }
        }
}

private class AndroidSession<out C>(
    private val context: Context,
    private val connection: ServiceConnection,
    override val client: C,
) : IsolatedWorkerSession<C> {
    private val closed = AtomicBoolean(false)

    // AtomicBoolean-idempotent: a second close() (or a `use {}` that re-runs the block) must
    // not call unbindService twice — Android throws IllegalArgumentException on an unbalanced
    // unbind. compareAndSet makes the first close win and every later one a no-op.
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { context.unbindService(connection) }
        }
    }
}
