package `is`.walt.passes.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

class WalletExportKeyTest {

    @Test
    fun generateProduces32RandomBytes() {
        val key = WalletExportKey.generate()
        assertThat(key).hasLength(WalletExportKey.KEY_SIZE_BYTES)
    }

    @Test
    fun twoGeneratedKeysAreNotEqual() {
        // Collision probability is negligible (~2^-256); a failure here is a broken PRNG.
        val a = WalletExportKey.generate()
        val b = WalletExportKey.generate()
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val key = WalletExportKey.generate()
        val decoded = WalletExportKey.decode(WalletExportKey.encode(key))
        assertThat(decoded).isEqualTo(key)
    }

    @Test
    fun encodedKeyIsValidBase64() {
        val encoded = WalletExportKey.encode(WalletExportKey.generate())
        // Should not throw.
        Base64.getDecoder().decode(encoded)
    }

    @Test
    fun decodeRejectsKeyThatIsTooShort() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        try {
            WalletExportKey.decode(shortKey)
            error("Expected IAE")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("16")
        }
    }

    @Test
    fun decodeRejectsKeyThatIsTooLong() {
        val longKey = Base64.getEncoder().encodeToString(ByteArray(64))
        try {
            WalletExportKey.decode(longKey)
            error("Expected IAE")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("64")
        }
    }

    @Test
    fun decodeRejectsInvalidBase64() {
        try {
            WalletExportKey.decode("not-valid-base64!!!")
            error("Expected IAE")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("base64")
        }
    }
}
