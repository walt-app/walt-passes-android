package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceTintTest {

    @Test
    fun transparentAndUnspecifiedTintsAreNotTints() {
        // Color.isSpecified is true for Color.Transparent, so gating on it alone paints a
        // transparent face and — on the scannable arm — derives ink from luminance 0.
        assertThat(faceIsTinted(Color.Transparent)).isFalse()
        assertThat(faceIsTinted(Color.Unspecified)).isFalse()
    }

    @Test
    fun opaqueAndTranslucentTintsAreTints() {
        // Translucent is a tint, not a reject: the KDocs ask for opaque colours because ink
        // is derived from the nominal value, but a consumer that passes one still gets it.
        val denim = Color(0xFF2A75BA)
        assertThat(faceIsTinted(denim)).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.5f))).isTrue()
        assertThat(faceIsTinted(denim.copy(alpha = 0.004f))).isTrue()
    }
}
