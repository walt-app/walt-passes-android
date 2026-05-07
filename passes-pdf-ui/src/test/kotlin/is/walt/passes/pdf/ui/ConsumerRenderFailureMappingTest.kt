package `is`.walt.passes.pdf.ui

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.ConsumerRenderFailure
import org.junit.Test
import java.nio.BufferUnderflowException

/**
 * JVM-pure cover for the `Throwable -> ConsumerRenderFailure` mapping inside
 * `DocumentView.bitmapFromSharedMemory` (wpass-8v4). The instrumented test
 * `DocumentViewInstrumentedTest.captionStillRendersWhenEveryRenderCallIsRejected`
 * exercises the renderer-rejection path that does not reach `bitmapFromSharedMemory`
 * at all; the SharedMemory-side failures (OOM, dimension mismatch, closed handle) only
 * surface on a real device. Pinning the dispatch table here is what keeps a future
 * refactor from silently re-routing one of the four legitimate failure shapes through
 * the `Other` arm — that bucket exists for genuine triage signal, not for absorbing
 * known cases.
 */
class ConsumerRenderFailureMappingTest {

    @Test
    fun outOfMemoryMapsToOutOfMemory() {
        assertThat(consumerRenderFailureFor(OutOfMemoryError("bitmap")))
            .isEqualTo(ConsumerRenderFailure.OutOfMemory)
    }

    @Test
    fun bufferUnderflowMapsToDimensionMismatch() {
        assertThat(consumerRenderFailureFor(BufferUnderflowException()))
            .isEqualTo(ConsumerRenderFailure.DimensionMismatch)
    }

    @Test
    fun illegalStateMapsToSharedMemoryUnavailable() {
        assertThat(consumerRenderFailureFor(IllegalStateException("closed")))
            .isEqualTo(ConsumerRenderFailure.SharedMemoryUnavailable)
    }

    @Test
    fun unknownThrowableFallsThroughToOther() {
        // RuntimeException is the canonical "didn't match any of the three deterministic
        // arms" case. Defensive `Other` exists so a future Android/JDK change that
        // surfaces a new failure class never crashes the consumer; a spike on this arm
        // in production telemetry is the signal to add a new mapping.
        assertThat(consumerRenderFailureFor(RuntimeException("unexpected")))
            .isEqualTo(ConsumerRenderFailure.Other)
    }

    @Test
    fun mostSpecificMatchWins() {
        // Subclasses of mapped types keep the mapping. This pins that adding a new
        // catch in `bitmapFromSharedMemory` for, say, `IllegalArgumentException` would
        // not silently move existing IllegalState callers to `Other`.
        class CustomISE : IllegalStateException("custom")
        assertThat(consumerRenderFailureFor(CustomISE()))
            .isEqualTo(ConsumerRenderFailure.SharedMemoryUnavailable)
    }
}
