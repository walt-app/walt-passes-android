package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the timeout-then-kill behaviour from ADR 0005 D7. The watchdog is the only
 * mechanism preventing a stuck PDFium render from holding the renderer process
 * indefinitely; if the kill path silently regresses, every other security control here
 * stops being load-bearing because an attacker can simply force a hang.
 *
 * Two timeout shapes are exercised:
 *  - a cooperatively-suspending block (delay-based polling), and
 *  - an uncancellable native-shape block ([Thread.sleep] polling).
 *
 * The second is the test that matters. The production [renderToSharedMemory] is a
 * synchronous JNI call into PDFium with no suspension points at all; a watchdog built on
 * `withTimeout` would silently fail to fire against it because cancellation is
 * cooperative. The Thread.sleep test stands in for that exact shape and proves the kill
 * fires regardless of whether the guarded block is willing to check for cancellation.
 *
 * The fake [ProcessKiller] records the call and returns rather than killing the JVM.
 * Each polling block exits once it observes `killer.killCount > 0`, so the test winds
 * down even though the watchdog itself never throws after the kill (in production, the
 * process is dead before any post-kill code path matters). A JUnit method timeout is set
 * on each test as a regression-net: a kill-path failure produces a hung block, and we'd
 * rather see a CI timeout than a green build.
 */
class RenderWatchdogTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutFiresKillerForCooperativelySuspendingBlock() =
        runBlocking {
            val killer = RecordingKiller()
            val watchdog = RenderWatchdog(timeoutMs = TIMEOUT_MS, killer = killer)

            val job =
                launch {
                    watchdog.guard {
                        // delay() is cancellable; the loop exits when the sibling killer
                        // fires and records its call, after which we let the guard
                        // unwind cleanly.
                        while (killer.killCount == 0) delay(POLL_MS)
                    }
                }
            job.join()

            assertThat(killer.killCount).isEqualTo(1)
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutFiresKillerForUncancellableNativeShapedBlock() =
        runBlocking {
            // The defining test for D7. A coroutine-based watchdog whose timer is itself
            // a child of the guarded work would hang here forever — Thread.sleep does not
            // check coroutine state. The sibling-timer design fires regardless.
            val killer = RecordingKiller()
            val watchdog = RenderWatchdog(timeoutMs = TIMEOUT_MS, killer = killer)

            val job =
                launch {
                    watchdog.guard {
                        // Synchronous polling stands in for an uncancellable native frame.
                        // In production the kill takes the process down before this loop
                        // matters; in the test we observe the recorded kill and release
                        // ourselves so the test can wind down.
                        while (killer.killCount == 0) Thread.sleep(POLL_MS)
                    }
                }
            job.join()

            assertThat(killer.killCount).isEqualTo(1)
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun fastPathDoesNotKill() =
        runBlocking {
            val killer = RecordingKiller()
            val watchdog = RenderWatchdog(timeoutMs = LONG_TIMEOUT_MS, killer = killer)

            val result = watchdog.guard { "ok" }

            assertThat(result).isEqualTo("ok")
            assertThat(killer.killCount).isEqualTo(0)
        }

    private companion object {
        const val TIMEOUT_MS = 50L
        const val LONG_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val TEST_TIMEOUT_MS = 3_000L
    }
}

private class RecordingKiller : ProcessKiller {
    @Volatile
    var killCount: Int = 0
        private set

    override fun killSelf() {
        killCount += 1
    }
}
