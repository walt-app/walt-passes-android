package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.barcode.DecodeLadder
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the relationship between the two wall-clock numbers this config carries (wpass-pl7.2).
 * The scale ladder and [DecodeWatchdog] measure the same window from opposite ends: exceeding the
 * ladder's budget costs a "no barcode found", exceeding the watchdog costs the sandbox. The
 * ordering below is necessary for the cheap failure to come first, not sufficient — the ladder's
 * budget bounds when a rung may start, and its first rung is unconditional — so the headroom
 * between the two numbers is what covers the rung already running when the budget runs out.
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

        assertThat(config.ladder.budget).isEqualTo(2_000L.milliseconds)
        assertThat(config.ladder.rungsPx).isEqualTo(DecodeLadder.STILL_IMAGE.rungsPx)
    }

    @Test
    fun aBudgetLargerThanTheWatchdogTimeoutIsRejected() {
        val rejected =
            runCatching { BarcodeDecodeConfig(decodeTimeoutMs = 2_000L, symbolDecodeBudgetMs = 3_000L) }

        assertThat(rejected.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
