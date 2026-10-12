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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        advanceTimeBy(BIND_TIMEOUT_MS - 1)
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

    private class FakeService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}
