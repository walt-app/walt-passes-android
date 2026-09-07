package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.DyingInputStream
import `is`.walt.passes.core.internal.LOCAL_HEADER_FIXED_LENGTH
import `is`.walt.passes.core.internal.ThrowingInputStream
import `is`.walt.passes.core.internal.buildArchive
import `is`.walt.passes.core.internal.findNthLocalHeaderOffset
import org.junit.Test

/**
 * Caller-stream failures: an [java.io.IOException] from the source is `SourceUnreadable`,
 * distinct from a corrupt archive, and entries already delivered stay delivered.
 */
class PassBundleReaderSourceTest {
    @Test
    fun callerStreamThatThrowsOnFirstReadIsATypedRejection() {
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Stream(ThrowingInputStream()), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.SourceUnreadable,
                accepted = 0,
                skipped = 0,
            ),
        )
        assertThat(sink.names).isEmpty()
    }

    @Test
    fun callerStreamThatDiesMidStreamIsSourceUnreadableAfterEarlierEntriesWereDelivered() {
        val zip =
            buildArchive {
                entry("a.pkpass", "A".toByteArray())
                entry("b.pkpass", ByteArray(4_096) { (it * 31).toByte() })
            }
        // Die a few bytes into the second entry's deflate stream, past its local header.
        val secondHeader = findNthLocalHeaderOffset(zip, 2)
        val dieAt = secondHeader + LOCAL_HEADER_FIXED_LENGTH + "b.pkpass".length + 16
        val sink = RecordingSink()
        val result = PassBundleReader.create().read(PassSource.Stream(DyingInputStream(zip, dieAt)), sink)
        assertThat(result).isEqualTo(
            BundleReadResult.Rejected(
                BundleRejection.SourceUnreadable,
                accepted = 1,
                skipped = 0,
            ),
        )
        assertThat(sink.names).containsExactly("a.pkpass")
    }
}
