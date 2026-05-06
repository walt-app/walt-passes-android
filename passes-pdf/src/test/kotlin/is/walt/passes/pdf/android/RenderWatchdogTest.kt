package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the timeout-then-kill behaviour from ADR 0005 D7. The watchdog is the only
 * mechanism preventing a stuck PDFium render from holding the renderer process
 * indefinitely; if the kill path silently regresses, every other security control here
 * stops being load-bearing because an attacker can simply force a hang.
 *
 * The kill is mediated through [ProcessKiller] so the JVM unit test does not actually
 * terminate itself. The fake records the call and returns; the watchdog re-throws the
 * underlying [TimeoutCancellationException] so callers observe the same failure shape
 * they would in production after the binder is dropped.
 */
class RenderWatchdogTest {
    @Test
    fun timeoutTriggersKill() =
        runTest {
            val killer = RecordingKiller()
            val watchdog = RenderWatchdog(timeoutMs = 50L, killer = killer)

            var threw = false
            try {
                watchdog.guard {
                    // Stalling block stands in for a hung PdfRenderer.render call. delay
                    // suspends cooperatively under withTimeout so the timeout fires.
                    delay(10_000L)
                    "unreachable"
                }
            } catch (_: TimeoutCancellationException) {
                threw = true
            }

            assertThat(threw).isTrue()
            assertThat(killer.killCount).isEqualTo(1)
        }

    @Test
    fun fastPathDoesNotKill() =
        runTest {
            val killer = RecordingKiller()
            val watchdog = RenderWatchdog(timeoutMs = 5_000L, killer = killer)

            val result = watchdog.guard { "ok" }

            assertThat(result).isEqualTo("ok")
            assertThat(killer.killCount).isEqualTo(0)
        }

    @Test
    fun synchronousBlockUnderTimeoutPasses() {
        // Sanity check that a non-suspending fast block also passes; the watchdog should
        // not introduce overhead that pushes a trivial call past its budget.
        val killer = RecordingKiller()
        val watchdog = RenderWatchdog(timeoutMs = 5_000L, killer = killer)
        val result = runBlocking { watchdog.guard { 42 } }
        assertThat(result).isEqualTo(42)
        assertThat(killer.killCount).isEqualTo(0)
    }
}

private class RecordingKiller : ProcessKiller {
    var killCount: Int = 0
        private set

    override fun killSelf() {
        killCount += 1
    }
}
