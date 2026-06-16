package `is`.walt.passes.image.android

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the timeout-then-kill behaviour of [DecodeWatchdog], the only mechanism stopping a
 * slow-loris descriptor or a hung codec parse from holding a sandbox binder thread forever. If
 * the kill path silently regresses, the bounded-decode caps stop being load-bearing because an
 * attacker can simply force a hang instead of an over-cap canvas.
 *
 * Two timeout shapes, mirroring `passes-pdf`'s `RenderWatchdogTest` and `passes-barcode`'s
 * `DecodeWatchdogTest`:
 *  - a cooperatively-suspending block (delay-based polling), and
 *  - an uncancellable native-shape block ([Thread.sleep] polling).
 *
 * The second is the one that matters: the production read-off-the-fd + `ImageDecoder` call is a
 * blocking sequence with no suspension points, so a `withTimeout`-based watchdog would never
 * fire against it. The sibling-timer design fires regardless.
 */
class DecodeWatchdogTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutFiresKillerForCooperativelySuspendingBlock() =
        runBlocking {
            val killer = RecordingKiller()
            val watchdog = DecodeWatchdog(timeoutMs = TIMEOUT_MS, killer = killer)

            val job =
                launch {
                    watchdog.guard {
                        while (killer.killCount == 0) delay(POLL_MS)
                    }
                }
            job.join()

            assertThat(killer.killCount).isEqualTo(1)
        }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeoutFiresKillerForUncancellableNativeShapedBlock() =
        runBlocking {
            val killer = RecordingKiller()
            val watchdog = DecodeWatchdog(timeoutMs = TIMEOUT_MS, killer = killer)

            val job =
                launch {
                    watchdog.guard {
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
            val watchdog = DecodeWatchdog(timeoutMs = LONG_TIMEOUT_MS, killer = killer)

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
