package `is`.walt.passes.pdf.android

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The timeout-then-kill behaviour from ADR 0005 D7. PDFium's `render` path can hang on
 * pathological documents (deeply-nested content streams, decoder recursion); rather than
 * hold the binder thread forever and starve the main process, the watchdog enforces a
 * hard wall-clock budget and, on expiry, terminates the renderer process. The main
 * process then observes the dropped binder as a [android.os.RemoteException] and surfaces
 * [is.walt.passes.pdf.DocumentRejectedKind.RendererFailed].
 *
 * Killing the renderer (rather than just cancelling the coroutine) is deliberate: the
 * coroutine cancellation is cooperative, and a JNI render call cannot be cooperatively
 * cancelled. The only reliable way to stop a misbehaving native frame is to take the
 * process down. The cost is one re-bind on the next render call, paid by the main
 * process; the benefit is a guarantee that no document can lock the renderer indefinitely.
 *
 * [killer] is injected so unit tests exercise the timeout path without taking down the
 * test JVM. The default is [ProcessKiller.Real].
 */
internal class RenderWatchdog(
    private val timeoutMs: Long,
    private val killer: ProcessKiller = ProcessKiller.Real,
) {
    suspend fun <T> guard(block: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { block() }
        } catch (timeout: TimeoutCancellationException) {
            killer.killSelf()
            throw timeout
        }
}
