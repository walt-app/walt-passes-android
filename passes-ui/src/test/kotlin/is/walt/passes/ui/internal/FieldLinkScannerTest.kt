package `is`.walt.passes.ui.internal

import `is`.walt.passes.ui.B3UrlIntent
import `is`.walt.passes.ui.EmailIntent
import `is`.walt.passes.ui.PhoneIntent
import `is`.walt.passes.ui.SourceField
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldLinkScannerTest {

    private val source = SourceField(
        fieldKey = "support_url",
        fieldLabel = "Support",
        organizationName = "Acme",
    )

    @Test
    fun detectsHttpsUrl() {
        val spans = FieldLinkScanner.scan(
            "Visit https://acme.example/help for assistance.",
            source,
        )
        assertThat(spans).hasSize(1)
        val span = spans.single()
        assertThat(span.intent).isInstanceOf(B3UrlIntent::class.java)
        assertThat((span.intent as B3UrlIntent).url).isEqualTo("https://acme.example/help")
    }

    @Test
    fun detectsHttpUrl() {
        val spans = FieldLinkScanner.scan("http://example.com/x", source)
        assertThat(spans.single().intent).isInstanceOf(B3UrlIntent::class.java)
    }

    @Test
    fun doesNotDetectSchemeLessUrl() {
        val spans = FieldLinkScanner.scan("acme.example", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun detectsEmail() {
        val spans = FieldLinkScanner.scan("Email support@acme.example for help", source)
        assertThat(spans.single().intent).isInstanceOf(EmailIntent::class.java)
        assertThat((spans.single().intent as EmailIntent).emailAddress)
            .isEqualTo("support@acme.example")
    }

    @Test
    fun detectsInternationalPhone() {
        val spans = FieldLinkScanner.scan("Call us at +1 (555) 123-4567 anytime.", source)
        assertThat(spans.single().intent).isInstanceOf(PhoneIntent::class.java)
        assertThat((spans.single().intent as PhoneIntent).phoneNumber)
            .isEqualTo("+1 (555) 123-4567")
    }

    @Test
    fun rejectsShortDigitRunsAsPhone() {
        // 5 digits: too short to be a phone number
        val spans = FieldLinkScanner.scan("Promo code 12345", source)
        assertThat(spans).isEmpty()
    }

    @Test
    fun emailInsideUrlIsNotDoubleClaimed() {
        val spans = FieldLinkScanner.scan("https://x@example.com/path", source)
        assertThat(spans).hasSize(1)
        assertThat(spans.single().intent).isInstanceOf(B3UrlIntent::class.java)
    }

    @Test
    fun multipleLinksReturnInOrder() {
        val text = "Site https://a.example or call +1-555-123-4567 or mailto:b@c.example."
        val spans = FieldLinkScanner.scan(text, source)
        // URL, phone, email (order in the source string).
        assertThat(spans.map { it.intent::class.simpleName }).containsExactly(
            "B3UrlIntent",
            "PhoneIntent",
            "EmailIntent",
        ).inOrder()
    }

    @Test
    fun targetStringIsVerbatim() {
        val text = "Reach me at  +44  20  7946  0958 ."
        val spans = FieldLinkScanner.scan(text, source)
        val intent = spans.single().intent as PhoneIntent
        // Trim is applied at the boundary so only the surrounding whitespace is removed;
        // internal whitespace is preserved verbatim, matching what the user sees.
        assertThat(intent.phoneNumber).isEqualTo("+44  20  7946  0958")
    }

    @Test
    fun sourceFieldIsCarriedThrough() {
        val spans = FieldLinkScanner.scan("https://example.com", source)
        assertThat(spans.single().intent.sourceField).isEqualTo(source)
    }
}
