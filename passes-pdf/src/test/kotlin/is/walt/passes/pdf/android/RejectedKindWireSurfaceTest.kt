package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.DocumentRejectedKind
import org.junit.Test

/**
 * Pins the binder-wire encoding of [DocumentRejectedKind] to its explicit code table.
 *
 * The wire format used to be `kind.ordinal`. That left every consumer of this binder
 * (eventually walt-android, post-`wlt-4pg`) coupled to the source-order of the enum: a
 * benign-looking refactor in `passes-pdf-core` that reorders or inserts an arm would
 * have shifted every code on the wire and silently mis-decoded rejections. Today the
 * proxy and client live in the same process from the same build, so the on-the-wire
 * fragility is latent rather than active — but the kernel-vs-consumer coupling is real,
 * and this test is the structural gate that keeps the mapping honest.
 *
 * Allowlist by explicit pair (mirrors `PublicApiSurfaceTest` in `passes-pdf-core`): the
 * exhaustive `when` in [RejectedKindWire.encode] ensures every enum arm has a code, and
 * this test ensures the codes are the documented ones. Adding an arm requires touching
 * the enum, the mapping table, and this test in lockstep.
 */
class RejectedKindWireSurfaceTest {
    @Test
    fun encodeMapsEachArmToItsDocumentedCode() {
        val expected =
            mapOf(
                DocumentRejectedKind.OversizedAtImport to RejectedKindWire.OVERSIZED_AT_IMPORT,
                DocumentRejectedKind.NotAPdf to RejectedKindWire.NOT_A_PDF,
                DocumentRejectedKind.Encrypted to RejectedKindWire.ENCRYPTED,
                DocumentRejectedKind.TooManyPages to RejectedKindWire.TOO_MANY_PAGES,
                DocumentRejectedKind.RendererFailed to RejectedKindWire.RENDERER_FAILED,
                DocumentRejectedKind.UnsupportedAndroidVersion to RejectedKindWire.UNSUPPORTED_ANDROID_VERSION,
            )
        for ((kind, code) in expected) {
            assertThat(RejectedKindWire.encode(kind)).isEqualTo(code)
        }
        // Drift detection: every enum arm must have a documented code in the table
        // above. If a new arm is added without updating this test, the assertion below
        // catches the gap.
        assertThat(expected.keys).containsExactlyElementsIn(DocumentRejectedKind.entries)
    }

    @Test
    fun decodeIsInverseOfEncode() {
        for (kind in DocumentRejectedKind.entries) {
            assertThat(RejectedKindWire.decode(RejectedKindWire.encode(kind))).isEqualTo(kind)
        }
    }

    @Test
    fun codesAreStableIntegers() {
        // Pin the actual integer values. A contributor reordering the constants in
        // RejectedKindWire would silently shift the wire even with the encode `when` in
        // place; this test catches that.
        assertThat(RejectedKindWire.OVERSIZED_AT_IMPORT).isEqualTo(0)
        assertThat(RejectedKindWire.NOT_A_PDF).isEqualTo(1)
        assertThat(RejectedKindWire.ENCRYPTED).isEqualTo(2)
        assertThat(RejectedKindWire.TOO_MANY_PAGES).isEqualTo(3)
        assertThat(RejectedKindWire.RENDERER_FAILED).isEqualTo(4)
        assertThat(RejectedKindWire.UNSUPPORTED_ANDROID_VERSION).isEqualTo(5)
    }

    @Test
    fun codesAreUnique() {
        val codes =
            DocumentRejectedKind.entries.map(RejectedKindWire::encode)
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun decodeRejectsUnknownCode() {
        runCatching { RejectedKindWire.decode(99) }
            .onSuccess { error("Expected decode(99) to throw, got $it") }
    }
}
