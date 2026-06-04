package `is`.walt.passes.isolation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM contract coverage for the shared session types. The Android `bindService` dance
 * in [AndroidIsolatedWorkerSessionFactory] is exercised on-device by
 * [IsolatedWorkerSessionInstrumentedTest]; what runs here is the part that needs no
 * framework — that [IsolatedWorkerSession] honours `AutoCloseable` (so a consumer's
 * `connect().session.use { ... }` always tears down) and that [ConnectResult] is the closed
 * two-arm shape every consumer folds over.
 *
 * These look small, but they pin the consolidation's headline decision: connect failure is
 * an explicit [ConnectResult.BindFailed] arm, not a rejecting session and not `null`. A
 * contributor collapsing the arm or widening the result would trip the exhaustiveness here.
 */
class IsolatedWorkerSessionContractTest {
    @Test
    fun useClosesSessionExactlyOnce() {
        val session = FakeSession("client")
        session.use { assertThat(it.client).isEqualTo("client") }
        assertThat(session.closeCount).isEqualTo(1)
    }

    @Test
    fun useClosesEvenWhenBodyThrows() {
        val session = FakeSession("client")
        runCatching { session.use { error("body blew up") } }
        assertThat(session.closeCount).isEqualTo(1)
    }

    @Test
    fun connectedCarriesItsSession() {
        val session = FakeSession(42)
        val result: ConnectResult<Int> = ConnectResult.Connected(session)
        assertThat(foldToLabel(result)).isEqualTo("connected:42")
    }

    @Test
    fun bindFailedIsADistinctArm() {
        assertThat(foldToLabel(ConnectResult.BindFailed)).isEqualTo("bind-failed")
    }

    // Exhaustive fold proves the sealed shape is exactly two arms: a contributor adding a
    // third would force this `when` (and every consumer's) to stop compiling.
    private fun <C> foldToLabel(result: ConnectResult<C>): String =
        when (result) {
            is ConnectResult.Connected -> "connected:${result.session.client}"
            ConnectResult.BindFailed -> "bind-failed"
        }

    private class FakeSession<out C>(override val client: C) : IsolatedWorkerSession<C> {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }
}
