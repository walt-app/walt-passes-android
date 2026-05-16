package `is`.walt.passes.storage.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.storage.DatabaseKey
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageResult
import `is`.walt.passes.storage.UnknownStorageFailureKind
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins the "key buffer is zeroed on every open-failure path" invariant. The catch arms
 * in [SqlCipherDatabaseFactory.openHandleWithKey] now hold this contract manually (it
 * used to be enforced by the old `retainAcross` lambda shape); a future refactor that
 * drops `keyBuffer.close()` from one arm would leave the raw key resident in heap after
 * a corrupt-header / IO failure / JNI mismatch open. These tests guard against that.
 *
 * Driven by injecting a throwing `opener` so no SQLCipher native lib loads and no real
 * database file is touched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SqlCipherDatabaseFactoryTest {

    @Test
    fun openHandleWithKeyZeroesKeyBufferWhenOpenerThrowsException() {
        val capturedBytes = AtomicReference<ByteArray>()
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })

        val boom = RuntimeException("simulated openOrCreateDatabase failure")
        val result = SqlCipherDatabaseFactory.openHandleWithKey(
            dbFile = File("/tmp/unused-by-throwing-opener.db"),
            databaseKey = key,
            isDebuggable = false,
        ) { _, bytes, _ ->
            // Sanity: the buffer was alive when handed to the opener (it is the copy
            // RetainedKeyBuffer wraps; the master DatabaseKey was zeroed before this).
            assertThat(bytes.any { it.toInt() != 0 }).isTrue()
            capturedBytes.set(bytes)
            throw boom
        }

        check(result is StorageResult.Failure)
        val error = result.error
        check(error is StorageError.Unknown)
        assertThat(error.kind).isEqualTo(UnknownStorageFailureKind.DatabaseCorrupt)
        // The factory's catch arm MUST have closed the RetainedKeyBuffer.
        assertThat(capturedBytes.get().all { it.toInt() == 0 }).isTrue()
    }

    @Test
    fun openHandleWithKeyZeroesKeyBufferAndPropagatesWhenOpenerThrowsCancellation() {
        val capturedBytes = AtomicReference<ByteArray>()
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })

        val thrown = try {
            SqlCipherDatabaseFactory.openHandleWithKey(
                dbFile = File("/tmp/unused-by-throwing-opener.db"),
                databaseKey = key,
                isDebuggable = false,
            ) { _, bytes, _ ->
                capturedBytes.set(bytes)
                throw CancellationException("simulated cancellation during open")
            }
            error("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            e
        }
        // Structured cancellation must NOT be translated to Failure - it propagates.
        assertThat(thrown.message).contains("simulated cancellation")
        // But the buffer is still zeroed on the cancellation path.
        assertThat(capturedBytes.get().all { it.toInt() == 0 }).isTrue()
    }
}
