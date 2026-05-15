package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the sub-rect validation rule the renderer service enforces before touching
 * PdfRenderer (`wpass-f4b`). Invalid rects fold to RendererFailed at the renderer.
 */
class SourceRectValidationTest {
    @Test
    fun fullPageIsAlwaysValid() {
        assertThat(isSourceRectValid(RenderSourceRect.FullPage)).isTrue()
    }

    @Test
    fun unitSquareSubRectIsValid() {
        assertThat(
            isSourceRectValid(RenderSourceRect.SubRect(0f, 0f, 1f, 1f)),
        ).isTrue()
    }

    @Test
    fun centeredQuarterRectIsValid() {
        assertThat(
            isSourceRectValid(RenderSourceRect.SubRect(0.25f, 0.25f, 0.75f, 0.75f)),
        ).isTrue()
    }

    @Test
    fun zeroAreaSubRectIsRejected() {
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0.5f, 0.5f, 0.5f, 0.5f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0.5f, 0.25f, 0.5f, 0.75f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0.25f, 0.5f, 0.75f, 0.5f))).isFalse()
    }

    @Test
    fun reversedSubRectIsRejected() {
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0.75f, 0.25f, 0.25f, 0.75f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0.25f, 0.75f, 0.75f, 0.25f))).isFalse()
    }

    @Test
    fun outOfUnitSquareSubRectIsRejected() {
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(-0.1f, 0f, 0.5f, 0.5f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0f, -0.1f, 0.5f, 0.5f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0f, 0f, 1.1f, 0.5f))).isFalse()
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(0f, 0f, 0.5f, 1.1f))).isFalse()
    }

    @Test
    fun nonFiniteSubRectIsRejected() {
        assertThat(isSourceRectValid(RenderSourceRect.SubRect(Float.NaN, 0f, 1f, 1f))).isFalse()
        assertThat(
            isSourceRectValid(RenderSourceRect.SubRect(0f, 0f, Float.POSITIVE_INFINITY, 1f)),
        ).isFalse()
    }
}
