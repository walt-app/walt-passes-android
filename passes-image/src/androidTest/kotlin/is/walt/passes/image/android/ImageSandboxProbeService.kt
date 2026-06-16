package `is`.walt.passes.image.android

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.KeyStore

/**
 * Test-only isolated-process probe. Declared `android:isolatedProcess="true"`,
 * `android:exported="false"`, `android:process=":probe"` in the androidTest manifest so its
 * binder transactions execute under the same kind of isolated UID the real [ImageDecodeService]
 * runs under. It is the runtime companion to [ManifestPermissionsTest]: that test pins the
 * sandbox declaration statically; this service proves at runtime that the sandbox actually
 * denies the privileges the decode UID must never hold. A verbatim copy of
 * `passes-barcode`'s probe — the sandbox contract is identical.
 *
 * The single [CODE_PROBE] transaction runs three privilege checks from inside the isolated
 * process and reports each as blocked/not-blocked, plus the process UID so the caller can
 * confirm the code really ran in an isolated UID and not the app UID.
 */
class ImageSandboxProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = ProbeBinder()

    private class ProbeBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                CODE_PROBE -> {
                    val sentinelPath = data.readString().orEmpty()
                    reply?.apply {
                        writeInt(Process.myUid())
                        writeInt(appDataBlocked(sentinelPath).toWire())
                        writeInt(keystoreBlocked().toWire())
                        writeInt(networkBlocked().toWire())
                    }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }

        /** Reading the app's private sentinel file must fail: the isolated UID is not the app UID. */
        private fun appDataBlocked(sentinelPath: String): Boolean =
            runCatching { File(sentinelPath).readBytes() }.isFailure

        /** Touching the Keystore daemon must fail: an isolated UID may not bind keystore2. */
        private fun keystoreBlocked(): Boolean =
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("probe-nonexistent")
            }.isFailure

        // The AF_INET socket() syscall must be denied (EPERM/EACCES): it is gated on the AID_INET
        // gid the isolated sandbox lacks. Probing the syscall tests the capability itself, unlike
        // a connect() that would time out for an unreachable host even with network access.
        private fun networkBlocked(): Boolean =
            try {
                Os.close(Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0))
                false
            } catch (e: ErrnoException) {
                e.errno == OsConstants.EPERM || e.errno == OsConstants.EACCES
            }

        private fun Boolean.toWire(): Int = if (this) 1 else 0
    }

    companion object {
        const val CODE_PROBE: Int = IBinder.FIRST_CALL_TRANSACTION
    }
}
