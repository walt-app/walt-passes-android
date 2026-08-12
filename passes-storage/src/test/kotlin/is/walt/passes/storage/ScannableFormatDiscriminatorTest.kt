package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Pins the on-disk `scannable_cards.format` discriminator contract (wpass-pl7.1).
 *
 * `SqlCipherScannableCardStore` writes `format.name` and reads it back with
 * `ScannableFormat.entries.firstOrNull { it.name == stored }`, dropping the row via
 * `onMigrationRowDropped(UnknownEnumValue)` when nothing matches. Two properties follow, and
 * both are asserted here because the store itself needs SQLCipher's native library and so can
 * only be exercised on a device:
 *
 *  - **Names are the discriminator, not ordinals.** Appending Pdf417/Aztec is therefore safe
 *    for existing rows; RENAMING any member would orphan every row already written under the
 *    old spelling, which the frozen table below turns into a failing test rather than silent
 *    data loss.
 *  - **An unknown discriminator fails safe.** The lookup is total — it returns null instead of
 *    throwing — so an older build that meets a row written by a newer one drops that single
 *    row and still lists the rest. This is what makes rolling back a Walt install non-fatal.
 */
class ScannableFormatDiscriminatorTest {
    @Test
    fun everyFormatPersistsUnderItsFrozenName() {
        val frozen =
            mapOf(
                ScannableFormat.Code128 to "Code128",
                ScannableFormat.Ean13 to "Ean13",
                ScannableFormat.UpcA to "UpcA",
                ScannableFormat.Code39 to "Code39",
                ScannableFormat.Qr to "Qr",
                ScannableFormat.Pdf417 to "Pdf417",
                ScannableFormat.Aztec to "Aztec",
            )
        for ((format, discriminator) in frozen) {
            assertThat(format.name).isEqualTo(discriminator)
        }
        // Fails closed when a format is added without being frozen here.
        assertThat(frozen.keys).containsExactlyElementsIn(ScannableFormat.entries)
    }

    @Test
    fun newFormatsResolveFromTheirStoredDiscriminator() {
        // The round trip a card written by this build takes on the next read.
        for (format in ScannableFormat.entries) {
            assertThat(lookUp(format.name)).isEqualTo(format)
        }
    }

    @Test
    fun unknownDiscriminatorResolvesToNullRatherThanThrowing() {
        // "DataMatrix" stands in for a discriminator a FUTURE build could write: out of scope
        // for wpass-pl7 but the most plausible next roster member.
        assertThat(lookUp("DataMatrix")).isNull()
        assertThat(lookUp("")).isNull()
        // Case-sensitive: a near-miss spelling must not silently resolve to a real format.
        assertThat(lookUp("aztec")).isNull()
    }

    /** Mirrors the store's private cursor lookup; kept in step by the tests above. */
    private fun lookUp(stored: String): ScannableFormat? =
        ScannableFormat.entries.firstOrNull { it.name == stored }
}
