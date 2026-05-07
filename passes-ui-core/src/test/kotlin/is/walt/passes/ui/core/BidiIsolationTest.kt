package `is`.walt.passes.ui.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the FSI/PDI fence shape. Both `passes-ui::SecuritySheets` (verbatim URL,
 * phone, email, organization name) and `passes-pdf-ui::DocumentTile` (user-controlled
 * displayLabel) depend on `isolated(s)` returning exactly `FSI + s + PDI`; a future
 * "polish" that drops or reorders the marks would silently weaken every consumer at
 * once. Locking the property here means the failure surfaces in this module's tests
 * before any surface module's screenshot or Robolectric pass.
 */
class BidiIsolationTest {

    @Test
    fun isolatedWrapsInFsiAndPdi() {
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
