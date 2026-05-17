package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public API surface of the [ScannableCard] artifact class. Companion to
 * [PublicApiSurfaceTest] (which locks the PKPASS surface). Two load-bearing responsibilities:
 * drift detection on every arm of [ScannableCardCreateResult] / value of [ScannableFormat] /
 * required parameter of [ScannableCard], and the trust-separation lock (no shared supertype
 * with [Pass]). Encoder and validator behavior live in Children .3 and .4.
 */
class ScannableCardSurfaceTest {
    @Test
    fun scannableCardConstructorIsExercisedWithEveryShape() {
        val card =
            ScannableCard(
                id = ScannableCardId("card-001"),
                payload = "1234567890128",
                format = ScannableFormat.Ean13,
                label = "Grocery loyalty",
                color = ScannableColor(argb = 0xFF1F4FA0.toInt()),
                createdAt = PassInstant(epochMillis = 1_800_000_000_000L),
            )

        assertThat(card.id).isEqualTo(ScannableCardId("card-001"))
        assertThat(card.format).isEqualTo(ScannableFormat.Ean13)
        assertThat(card.color?.argb).isEqualTo(0xFF1F4FA0.toInt())
        // Every ScannableFormat referenced so removal breaks compilation here.
        val allFormats =
            setOf(
                ScannableFormat.Code128,
                ScannableFormat.Ean13,
                ScannableFormat.UpcA,
                ScannableFormat.Code39,
                ScannableFormat.Qr,
            )
        assertThat(allFormats).hasSize(ScannableFormat.entries.size)
    }

    @Test
    fun scannableCardEqualityIsStructural() {
        val a = sampleCard()
        val b = sampleCard()
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun scannableCardColorIsOptional() {
        val card = sampleCard(color = null)
        assertThat(card.color).isNull()
    }

    @Test
    fun scannableCardCreateInputAcceptsEdgeValues() {
        val empty =
            ScannableCardCreateInput(
                payload = "",
                format = ScannableFormat.Qr,
                label = "",
                color = null,
            )
        assertThat(empty.payload).isEmpty()
        assertThat(empty.label).isEmpty()

        // Construction is unconditionally permissive; rejection lives in the result family.
        val maxish =
            ScannableCardCreateInput(
                payload = "X".repeat(10_000),
                format = ScannableFormat.Code128,
                label = "Y".repeat(1_000),
                color = ScannableColor(argb = 0),
            )
        assertThat(maxish.payload).hasLength(10_000)
    }

    /**
     * Covers the two directly-constructible result arms. The remaining three arms wrap
     * sealed reasons ([PayloadRejection], [LabelRejection], [EncoderFailureReason]) whose
     * concrete arms land in Children .3 and .4. Compile-time `when` exhaustiveness still
     * fires at every real call site, so this test only needs to prove the constructible
     * arms reach their branches.
     */
    @Test
    fun scannableCardCreateResultConstructibleArmsAreReachableViaWhen() {
        val results: List<ScannableCardCreateResult> =
            listOf(
                ScannableCardCreateResult.Success(sampleCard()),
                ScannableCardCreateResult.UnsupportedFormat(ScannableFormat.Code39),
            )
        val branches =
            results.map { result ->
                when (result) {
                    is ScannableCardCreateResult.Success -> "success"
                    is ScannableCardCreateResult.InvalidPayload -> "payload"
                    is ScannableCardCreateResult.InvalidLabel -> "label"
                    is ScannableCardCreateResult.UnsupportedFormat -> "format"
                    is ScannableCardCreateResult.EncoderFailure -> "encoder"
                }
            }
        assertThat(branches).containsExactly("success", "format").inOrder()
    }

    /**
     * The three sealed-reason families exist as kernel-exported types even though their
     * arms land later (Child 3 for [EncoderFailureReason], Child 4 for the other two).
     * Removing any of them breaks this test before it breaks the dependent child's compile.
     */
    @Test
    fun rejectionFamiliesAreExported() {
        assertThat(PayloadRejection::class.java.isInterface).isTrue()
        assertThat(LabelRejection::class.java.isInterface).isTrue()
        assertThat(EncoderFailureReason::class.java.isInterface).isTrue()
    }

    /**
     * Trust separation lock. The two artifact classes must not share a supertype — neither
     * [Pass] nor [ScannableCard] is assignable to the other, and neither is assignable to
     * any kernel-defined interface (the only shared supertype permitted is [Any]).
     *
     * If a future refactor introduces a `DisplayableArtifact` (or similar) supertype, this
     * test fails — which is the point. The epic's pre-ship requirement #1 ("distinct
     * artifact class end-to-end") depends on the type system enforcing the distinction.
     */
    @Test
    fun scannableCardDoesNotShareSupertypeWithPass() {
        val passAncestors = ancestorsOf(Pass::class.java)
        val cardAncestors = ancestorsOf(ScannableCard::class.java)
        // Both data classes inherit only from `java.lang.Object`; the intersection must
        // contain that and nothing else. Any other shared ancestor would mean the two
        // artifact classes have been unified under a common kernel type — exactly the
        // trust-conflation risk the wpass-lzi epic forbids.
        val shared = passAncestors.intersect(cardAncestors)
        assertThat(shared).containsExactly(Any::class.java)
    }

    @Test
    fun scannableCardIdAndColorAreValueClasses() {
        // @JvmInline value classes equate by wrapped value, not reference.
        assertThat(ScannableCardId("x")).isEqualTo(ScannableCardId("x"))
        assertThat(ScannableColor(0x11223344)).isEqualTo(ScannableColor(0x11223344))
    }

    // Walks superclasses and their declared interfaces. Skips interface-of-interface ancestors
    // because neither artifact class has any; if that changes, extend this helper.
    private fun ancestorsOf(cls: Class<*>): Set<Class<*>> {
        val out = mutableSetOf<Class<*>>()
        var current: Class<*>? = cls.superclass
        while (current != null) {
            out += current
            out += current.interfaces
            current = current.superclass
        }
        out += cls.interfaces
        return out
    }

    private fun sampleCard(color: ScannableColor? = ScannableColor(argb = 0)): ScannableCard =
        ScannableCard(
            id = ScannableCardId("card-fixed"),
            payload = "PAYLOAD",
            format = ScannableFormat.Code128,
            label = "label",
            color = color,
            createdAt = PassInstant(epochMillis = 1_700_000_000_000L),
        )
}
