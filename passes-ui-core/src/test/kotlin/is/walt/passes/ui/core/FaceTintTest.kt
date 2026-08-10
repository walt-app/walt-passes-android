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
        // 0.004f pins that the edge sits at exactly zero. An epsilon would fit the rationale
        // better (inkOn's WCAG guarantee is pinned only over opaque tints), but any epsilon is
        // arbitrary and the surfaces disclaim it instead; moving the edge is a contract change.
        val denim = Color(0xFF2A75BA)
        assertThat(faceIsTinted(denim)).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.5f))).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.004f))).isTrue()
    }
}
