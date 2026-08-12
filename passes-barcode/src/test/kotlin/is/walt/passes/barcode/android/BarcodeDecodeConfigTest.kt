package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.barcode.DecodeLadder
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the relationship between the two wall-clock numbers this config carries (wpass-pl7.2).
 * The scale ladder and [DecodeWatchdog] measure the same window from opposite ends: overrunning
 * the ladder's budget costs a "no barcode found", overrunning the watchdog costs the sandbox. A
 * config where the ladder may outlast the watchdog would convert the cheap failure into the
 * expensive one, so it is rejected at construction rather than discovered on a slow device.
 */
class BarcodeDecodeConfigTest {
    @Test
    fun defaultBudgetLeavesTheWatchdogRoomForTheReadAndCodecDecode() {
        val config = BarcodeDecodeConfig()

        assertThat(config.symbolDecodeBudgetMs).isLessThan(config.decodeTimeoutMs)
    }

    @Test
    fun ladderCarriesTheConfiguredBudget() {
        val config = BarcodeDecodeConfig(decodeTimeoutMs = 4_000L, symbolDecodeBudgetMs = 2_000L)

        assertThat(config.ladder().budget).isEqualTo(2_000L.milliseconds)
        assertThat(config.ladder().rungsPx).isEqualTo(DecodeLadder.STILL_IMAGE.rungsPx)
    }

    @Test
    fun aBudgetThatCouldOutlastTheWatchdogIsRejected() {
        val rejected =
            runCatching { BarcodeDecodeConfig(decodeTimeoutMs = 2_000L, symbolDecodeBudgetMs = 3_000L) }

        assertThat(rejected.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
