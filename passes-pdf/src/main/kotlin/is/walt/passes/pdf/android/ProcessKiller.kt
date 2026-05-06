package `is`.walt.passes.pdf.android

import android.os.Process

/**
 * Indirection over `Process.killProcess(Process.myPid())` so [RenderWatchdog] is testable
 * without taking down the test JVM. Production code uses [Real]; unit tests substitute a
 * fake that records the kill and returns. The interface is internal because the
 * abstraction has no consumer outside this module.
 */
internal interface ProcessKiller {
    fun killSelf()

    object Real : ProcessKiller {
        override fun killSelf() {
            Process.killProcess(Process.myPid())
        }
    }
}
