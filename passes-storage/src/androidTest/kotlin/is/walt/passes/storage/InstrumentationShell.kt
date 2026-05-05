package `is`.walt.passes.storage

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException

/**
 * Shell-command access for instrumentation tests. Wraps [UiAutomation.executeShellCommand]
 * so individual tests stay focused on the trust-claim they verify.
 *
 * Shell commands run as the `shell` user, not root. That is sufficient for `bmgr`,
 * `pm`, `cmd backup`, `device_config`, and most `dumpsys` reads, but not for paths
 * under `/data/data/com.android.localtransport/`. Tests that need privileged paths
 * must `assumeTrue(canRead(...))` and skip cleanly on devices where the path is opaque.
 */
internal object InstrumentationShell {

    fun run(command: String): ShellResult {
        val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = automation.executeShellCommand(command)
        val output = try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return ShellResult(command = command, output = "", ioError = e.message)
        }
        return ShellResult(command = command, output = output)
    }

    data class ShellResult(
        val command: String,
        val output: String,
        val ioError: String? = null,
    ) {
        val succeeded: Boolean get() = ioError == null
    }
}
