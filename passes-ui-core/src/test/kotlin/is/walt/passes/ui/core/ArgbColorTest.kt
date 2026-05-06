package `is`.walt.passes.ui.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArgbColorTest {

    @Test
    fun argbColorIsAValueClassWrappingAnInt() {
        val color = ArgbColor(0xFFEE2200.toInt())
        assertThat(color.argb).isEqualTo(0xFFEE2200.toInt())
    }

    @Test
    fun isolatedWrapsInFsiAndPdi() {
        // The fence is the load-bearing property — both passes-ui and passes-pdf-ui
        // depend on the displayed bytes being exactly FSI + content + PDI. Asserting
        // it here means a future "polish" of the helper that drops one of the marks
        // breaks before any surface module's screenshot pass would.
        val wrapped = isolated("hello")
        assertThat(wrapped).isEqualTo("⁨hello⁩")
        assertThat(wrapped.first()).isEqualTo(FSI)
        assertThat(wrapped.last()).isEqualTo(PDI)
    }

    @Test
    fun isolatedAcceptsEmptyStringAndPreservesFence() {
        // A surface should never invoke isolated("") in practice — the empty case is
        // handled by the caller — but if it does, the fence must still be intact so
        // surrounding bidi context cannot reorder later glyphs into the empty span.
        assertThat(isolated("")).isEqualTo("⁨⁩")
    }
}
