package `is`.walt.passes.pdf.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The timeout-then-kill behaviour from ADR 0005 D7. PDFium's `render` path can hang on
 * pathological documents (deeply-nested content streams, decoder recursion); rather than
 * hold the binder thread forever and starve the main process, the watchdog enforces a
 * hard wall-clock budget and, on expiry, terminates the renderer process. The main
 * process then observes the dropped binder as a [android.os.RemoteException] and surfaces
 * [is.walt.passes.document.DocumentRejectedKind.RendererFailed].
 *
 * Killing the renderer (rather than just cancelling the coroutine) is deliberate: the
 * coroutine cancellation is cooperative, and a JNI render call cannot be cooperatively
 * cancelled. The only reliable way to stop a misbehaving native frame is to take the
 * process down. The cost is one re-bind on the next render call, paid by the main
 * process; the benefit is a guarantee that no document can lock the renderer indefinitely.
 *
 * The kill timer is launched as a *sibling* of the guarded work, not as code that runs
 * after `block` returns. This is load-bearing: `withTimeout` and other cancellation-based
 * approaches only fire at suspension points, and the production [block] is a synchronous
 * JNI call into PDFium with no suspension points at all. A sibling coroutine whose own
 * `delay` runs on the coroutine framework's timer fires regardless of what the guarded
 * block is doing — including hanging in native code. If the block returns first, the
 * sibling timer is cancelled in the `finally` and no kill happens.
 *
 * [killer] is injected so unit tests exercise the timeout path without taking down the
 * test JVM. The default is [ProcessKiller.Real].
 */
internal class RenderWatchdog(
    private val timeoutMs: Long,
    private val killer: ProcessKiller = ProcessKiller.Real,
) {
    suspend fun <T> guard(block: suspend () -> T): T =
        coroutineScope {
            val killerJob =
                launch {
                    delay(timeoutMs)
                    killer.killSelf()
                }
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                killerJob.cancel()
            }
        }
}
