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
                createdAt = PassInstant(epochMillis = 1_800_000_000_000L),
            )

        assertThat(card.id).isEqualTo(ScannableCardId("card-001"))
        assertThat(card.format).isEqualTo(ScannableFormat.Ean13)
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
    fun scannableCardCreateInputAcceptsEdgeValues() {
        val empty =
            ScannableCardCreateInput(
                payload = "",
                format = ScannableFormat.Qr,
                label = "",
            )
        assertThat(empty.payload).isEmpty()
        assertThat(empty.label).isEmpty()

        // Construction is unconditionally permissive; rejection lives in the result family.
        val maxish =
            ScannableCardCreateInput(
                payload = "X".repeat(10_000),
                format = ScannableFormat.Code128,
                label = "Y".repeat(1_000),
            )
        assertThat(maxish.payload).hasLength(10_000)
    }

    /**
     * Every [ScannableCardCreateResult] arm is constructible and reachable via exhaustive
     * `when`. [InvalidPayload] / [InvalidLabel] use a sentinel reason from their sealed
     * family (the per-arm coverage of those families lives in [ScannableCardInputValidatorTest]
     * and the `*ArmsAreAllConstructible` tests in this file); the point here is the create
     * result's own arm shape.
     */
    @Test
    fun scannableCardCreateResultArmsAreReachableViaWhen() {
        val results: List<ScannableCardCreateResult> =
            listOf(
                ScannableCardCreateResult.Success(sampleCard()),
                ScannableCardCreateResult.InvalidPayload(PayloadRejection.Empty),
                ScannableCardCreateResult.InvalidLabel(LabelRejection.Empty),
                ScannableCardCreateResult.UnsupportedFormat(ScannableFormat.Code39),
                ScannableCardCreateResult.EncoderFailure(EncoderFailureReason.PayloadTooDense),
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
        assertThat(branches).containsExactly("success", "payload", "label", "format", "encoder").inOrder()
    }

    /**
     * The three sealed-reason families exist as kernel-exported types. Removing any of
     * them breaks this test before it breaks the dependent child's compile.
     */
    @Test
    fun rejectionFamiliesAreExported() {
        assertThat(PayloadRejection::class.java.isInterface).isTrue()
        assertThat(LabelRejection::class.java.isInterface).isTrue()
        assertThat(EncoderFailureReason::class.java.isInterface).isTrue()
    }

    /**
     * Every [EncoderFailureReason] arm is constructible. Removing an arm breaks
     * compilation here before it breaks a downstream `when` in the consumer's create flow.
     */
    @Test
    fun encoderFailureReasonArmsAreAllConstructible() {
        val reasons: List<EncoderFailureReason> =
            listOf(
                EncoderFailureReason.WriterRejected(ScannableFormat.Code128, "detail"),
                EncoderFailureReason.PayloadTooDense,
            )
        assertThat(reasons.toSet()).hasSize(reasons.size)
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
    fun scannableCardIdIsAValueClass() {
        // @JvmInline value classes equate by wrapped value, not reference.
        assertThat(ScannableCardId("x")).isEqualTo(ScannableCardId("x"))
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

    private fun sampleCard(): ScannableCard =
        ScannableCard(
            id = ScannableCardId("card-fixed"),
            payload = "PAYLOAD",
            format = ScannableFormat.Code128,
            label = "label",
            createdAt = PassInstant(epochMillis = 1_700_000_000_000L),
        )
}
