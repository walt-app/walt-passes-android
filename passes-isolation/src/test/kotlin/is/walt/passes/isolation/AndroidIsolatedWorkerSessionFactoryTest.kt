package `is`.walt.passes.isolation

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric's `bindService` posts `onServiceConnected` to the paused main looper, so a test
 * that never idles the looper is a bind that was accepted and never completes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidIsolatedWorkerSessionFactoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(app, FakeService::class.java)
    private val binder = Binder()
    private val factory = AndroidIsolatedWorkerSessionFactory(app, FakeService::class.java) { it }

    @Test
    fun connectedSessionCarriesTheWrappedBinderAndClosesOnce() = runTest {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val pending = async { factory.connect() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        val result = pending.await()

        assertThat(result).isInstanceOf(ConnectResult.Connected::class.java)
        val session = (result as ConnectResult.Connected<IBinder>).session
        assertThat(session.client).isSameInstanceAs(binder)
        session.close()
        session.close()
        assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
    }

    @Test
    fun refusedBindFoldsToBindFailed() = runTest {
        shadowOf(app).declareComponentUnbindable(component)

        assertThat(factory.connect()).isEqualTo(ConnectResult.BindFailed)
    }

    @Test
    fun bindThatNeverConnectsFoldsToBindFailedAtTheDeadlineAndUnbinds() = runTest {
        val pending = async { factory.connect() }
        runCurrent()
        assertThat(shadowOf(app).boundServiceConnections).hasSize(1)

        advanceTimeBy(AndroidIsolatedWorkerSessionFactory.BIND_TIMEOUT_MS - 1)
        runCurrent()
        assertThat(pending.isCompleted).isFalse()

        advanceTimeBy(2)
        runCurrent()
        assertThat(pending.await()).isEqualTo(ConnectResult.BindFailed)
        assertThat(shadowOf(app).boundServiceConnections).isEmpty()
        assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
    }

    @Test
    fun connectionArrivingAfterTheDeadlineIsIgnored() = runTest {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val result = factory.connect()
        assertThat(result).isEqualTo(ConnectResult.BindFailed)

        // The queued callback now fires against a released bind; it must neither throw nor rebind.
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(app).boundServiceConnections).isEmpty()
        assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
    }

    @Test
    fun repeatedOnServiceConnectedAfterConnectDoesNotThrow() = runTest {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val pending = async { factory.connect() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val session = (pending.await() as ConnectResult.Connected<IBinder>).session

        // BIND_AUTO_CREATE re-fires this when the sandbox is recreated mid-session.
        shadowOf(app).boundServiceConnections.single().onServiceConnected(component, Binder())

        assertThat(session.client).isSameInstanceAs(binder)
        session.close()
    }

    @Test
    fun callerCancelledAfterTheBindResumesLeavesNothingBound() = runTest {
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val pending = async { factory.connect() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        pending.cancel()
        runCurrent()

        assertThat(shadowOf(app).boundServiceConnections).isEmpty()
    }

    @Test
    fun sessionArrivingAfterTheDeadlineIsClosedNotStranded() = runTest {
        // NonCancellable lets bind() return after the deadline has already fired.
        val session = RecordingSession()
        val result =
            AndroidIsolatedWorkerSessionFactory.connectWithin(TIMEOUT_MS) {
                withContext(NonCancellable) { delay(TIMEOUT_MS + 1) }
                ConnectResult.Connected(session)
            }

        assertThat(result).isEqualTo(ConnectResult.BindFailed)
        assertThat(session.closeCount).isEqualTo(1)
    }

    @Test
    fun sessionReturnedToACancelledCallerIsClosed() = runTest {
        val session = RecordingSession()
        val caller =
            launch {
                val self = coroutineContext.job
                AndroidIsolatedWorkerSessionFactory.connectWithin(TIMEOUT_MS) {
                    self.cancel()
                    ConnectResult.Connected(session)
                }
            }
        caller.join()

        assertThat(caller.isCancelled).isTrue()
        assertThat(session.closeCount).isEqualTo(1)
    }

    @Test
    fun sessionReturnedInTimeIsHandedOverOpen() = runTest {
        val session = RecordingSession()
        val result = AndroidIsolatedWorkerSessionFactory.connectWithin(TIMEOUT_MS) { ConnectResult.Connected(session) }

        assertThat((result as ConnectResult.Connected).session).isSameInstanceAs(session)
        assertThat(session.closeCount).isEqualTo(0)
    }

    private class RecordingSession : IsolatedWorkerSession<Unit> {
        override val client: Unit = Unit
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    private class FakeService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
    }
}
