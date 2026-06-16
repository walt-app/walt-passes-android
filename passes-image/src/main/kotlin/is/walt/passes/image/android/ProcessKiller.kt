package `is`.walt.passes.image.android

import android.os.Process

/**
 * Indirection over `Process.killProcess(Process.myPid())` so [DecodeWatchdog] is testable
 * without taking down the test JVM. Production code uses [Real]; unit tests substitute a fake
 * that records the kill and returns.
 *
 * A module-local copy of `passes-pdf` / `passes-barcode`'s `ProcessKiller` rather than a
 * shared type: the isolated services are independent peers (only the bind/memfd plumbing is
 * shared, via `passes-isolation`), and the kill is one line with no policy to centralise.
 * `internal` because nothing outside this module references it.
 */
internal interface ProcessKiller {
    fun killSelf()

    object Real : ProcessKiller {
        override fun killSelf() {
            Process.killProcess(Process.myPid())
        }
    }
}
