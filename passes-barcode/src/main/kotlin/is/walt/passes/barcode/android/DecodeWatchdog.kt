package `is`.walt.passes.barcode.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Timeout-then-kill for the bounded image decode, the barcode counterpart of `passes-pdf`'s
 * `RenderWatchdog`. The decode path has two ways to hang indefinitely: a slow-loris
 * descriptor handed across the bind (the read off the fd blocks while the synchronous binder
 * transaction holds a sandbox binder thread — flagged on wpass-zrt.3), and a pathological
 * container that sends the platform codec into a long parse. Either would starve the decode
 * process without a hard wall-clock bound. On expiry the watchdog terminates the isolated
 * process; the main process then observes the dropped binder as a
 * [android.os.RemoteException] and surfaces
 * [is.walt.passes.core.DecodeFailureReason.DecoderUnavailable].
 *
 * Killing the process (rather than cancelling the coroutine) is deliberate: coroutine
 * cancellation is cooperative and only fires at suspension points, but the decode is a
 * blocking read plus a synchronous `ImageDecoder` call with none. The kill timer is launched
 * as a *sibling* of the guarded work — its own `delay` runs on the coroutine framework's
 * timer and fires regardless of what the guarded block is doing, including blocking in a
 * native frame. If the block returns first, the sibling timer is cancelled in the `finally`
 * and no kill happens. The cost on expiry is one re-bind on the next decode, paid by the
 * main process; the benefit is that no image can lock the decoder indefinitely.
 *
 * [killer] is injected so unit tests exercise the timeout path without taking down the test
 * JVM. The default is [ProcessKiller.Real].
 */
internal class DecodeWatchdog(
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
