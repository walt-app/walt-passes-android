package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceTintTest {

    @Test
    fun transparentAndUnspecifiedTintsAreNotTints() {
        assertThat(faceIsTinted(Color.Transparent)).isFalse()
        assertThat(faceIsTinted(Color.Unspecified)).isFalse()
    }

    @Test
    fun anyNonZeroAlphaIsATint() {
        // The knife edge sits at exactly zero, deliberately, and 0.004f pins that rather
        // than leaving it to be discovered. An epsilon threshold would be a better fit for
        // the stated rationale — at alpha 0.004 the face is visually host paint, yet the
        // scannable arm still derives ink from the nominal RGB, so inkOn's WCAG guarantee
        // (pinned only over opaque tints) does not hold there. It is not adopted because
        // any epsilon is arbitrary and this gate is the shipped document-arm behaviour that
        // wpass-80y.5 hoisted rather than redesigned; the surfaces disclaim it instead
        // ("pass an opaque color"). Moving the edge is a contract change, not a fix.
        val denim = Color(0xFF2A75BA)
        assertThat(faceIsTinted(denim)).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.5f))).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.004f))).isTrue()
    }
}
