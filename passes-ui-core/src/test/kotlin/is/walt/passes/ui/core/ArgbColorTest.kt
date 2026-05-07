package `is`.walt.passes.ui.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArgbColorTest {

    @Test
    fun argbColorIsAValueClassWrappingAnInt() {
        val color = ArgbColor(0xFFEE2200.toInt())
        assertThat(color.argb).isEqualTo(0xFFEE2200.toInt())
    }
}
