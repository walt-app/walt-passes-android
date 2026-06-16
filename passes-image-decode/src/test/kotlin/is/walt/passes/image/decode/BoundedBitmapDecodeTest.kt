package `is`.walt.passes.image.decode

import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * JVM coverage for the shared bounded-decode dispatch and the OOM-containment decision.
 *
 * Two halves, split by what is deterministically observable off-device:
 * - [containOutOfMemory] is pure, so the propagate-vs-contain split — the security-relevant
 *   branch that differs between the in-process display caller (null fold, propagate) and the
 *   sandbox callers (fold, contain) — is asserted directly, without forcing a real OOM.
 * - [decodeBounded] is exercised through Robolectric's `ImageDecoder` shadow, which returns a
 *   ~100x100 stub for arbitrary bytes rather than decoding or rejecting. That is enough to
 *   pin the gate dispatch (tight caps reject, generous caps decode) across two different
 *   rejection taxonomies [R], proving the generic carries the caller's type. The real native
 *   decode of malformed/oversized bytes is the instrumented half
 *   ([BoundedBitmapDecodeInstrumentedTest]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BoundedBitmapDecodeTest {

    // A caller's rejection taxonomy. The mechanism names no reason of its own; this stands in
    // for DecodeFailureReason / ImageDecodeRejection.
    private enum class Reason { TooLarge, Malformed, RuntimeFailure, Oom }

    private fun policy(
        maxSide: Int,
        onOutOfMemory: (() -> Reason)? = { Reason.Oom },
    ) = BoundedDecodePolicy(
        allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
        gate = { _, w, h -> if (w > maxSide || h > maxSide) Reason.TooLarge else null },
        onMalformed = { Reason.Malformed },
        onRuntimeFailure = { Reason.RuntimeFailure },
        onOutOfMemory = onOutOfMemory,
    )

    @Test
    fun containOutOfMemoryPropagatesWhenNoFold() {
        // In-process display posture: no rejection bucket for an OOM, so signal propagate.
        val contained = containOutOfMemory(pendingRejection = null, policy = policy(maxSide = 8, onOutOfMemory = null))
        assertThat(contained).isNull()
    }

    @Test
    fun containOutOfMemoryFoldsWhenSupplied() {
        // Sandbox posture: an OOM is contained as the caller's fold value.
        val contained = containOutOfMemory(pendingRejection = null, policy = policy(maxSide = 8))
        assertThat(contained).isEqualTo(BoundedBitmap.Rejected(Reason.Oom))
    }

    @Test
    fun containOutOfMemoryPrefersPendingGateRejection() {
        // A header rejection already in flight wins over the OOM fold (the `rejection ?:` idiom).
        val contained = containOutOfMemory(pendingRejection = Reason.TooLarge, policy = policy(maxSide = 8))
        assertThat(contained).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun decodeRejectsWhenGateTripsOnStubDimensions() {
        // Robolectric's stub bitmap exceeds a 1px cap, so the gate rejects with the caller's reason.
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = policy(maxSide = 1))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun decodeReturnsBitmapWhenGateAccepts() {
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = policy(maxSide = 4096))
        assertThat(result).isInstanceOf(BoundedBitmap.Decoded::class.java)
        assertThat((result as BoundedBitmap.Decoded).bitmap).isNotNull()
    }

    @Test
    fun decodeCarriesADifferentRejectionTaxonomy() {
        // Same mechanism, a second caller type: proves the generic is not pinned to one enum.
        val stringPolicy =
            BoundedDecodePolicy<String>(
                allocator = ImageDecoder.ALLOCATOR_DEFAULT,
                gate = { _, _, _ -> "too-big" },
                onMalformed = { "malformed" },
                onRuntimeFailure = { "runtime" },
                onOutOfMemory = null,
            )
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = stringPolicy)
        assertThat(result).isEqualTo(BoundedBitmap.Rejected("too-big"))
    }

    private companion object {
        // Arbitrary bytes; the Robolectric shadow yields a stub bitmap regardless of content.
        val STUB_INPUT = ByteArray(64) { (it * 31).toByte() }
    }
}
