package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.lookupOrSelf
import `is`.walt.passes.core.resolveLocalizedStrings
import `is`.walt.passes.ui.internal.FieldLinkScanner
import `is`.walt.passes.ui.internal.LinkSpan
import `is`.walt.passes.ui.internal.LocalLocalizedStrings

/**
 * Renders the back of a pass: the list of [Pass.backFields], with detected URLs,
 * phone numbers, and email addresses rendered as tappable affordances. Tapping such
 * an affordance fires the matching callback with the parsed [SecurityIntent];
 * `passes-ui` never invokes the host's outbound `Intent` directly. The host's
 * expected wiring is to display the corresponding confirmation sheet (see
 * [B3UrlConfirmSheet], [PhoneConfirmSheet], [EmailConfirmSheet]) and call
 * `Intent.ACTION_VIEW` (or `ACTION_DIAL`, `ACTION_SENDTO`) only on confirm.
 *
 * The three callbacks are NOT defaulted to no-op — see ADR 0003 D5. A host that
 * forgets to wire one of them is a compile error, not a runtime swallow.
 *
 * @param locale Drives `pass.strings` substitution via [Pass.resolveLocalizedStrings].
 *   The default `PassLocale("en")` is a *fallback for tests and previews*; production
 *   callers (walt-android) MUST thread the device locale through, or the user sees
 *   English labels regardless of system language.
 */
@Composable
public fun PassBack(
    pass: Pass,
    onUrlIntent: (B3UrlIntent) -> Unit,
    onPhoneIntent: (PhoneIntent) -> Unit,
    onEmailIntent: (EmailIntent) -> Unit,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
) {
    LaunchedEffect(pass) { telemetry.onPassBackOpened(pass.type) }
    // Narrow the remember key to the locales map; Pass.equals walks every image's
    // contentEquals which is wasted work for a function that only consults locales.
    val strings = remember(pass.locales, locale) { pass.resolveLocalizedStrings(locale) }
    // wpass-38y: substitute organizationName here (not just inside BackFieldRow) so
    // the security confirmation sheets — which read SourceField.organizationName —
    // see the same display string the front of the pass shows. Without this the
    // front would render "Tixly" while a tap on a back-field URL would surface
    // "#ORGNAME#" in the confirm copy.
    val displayOrganizationName = strings.lookupOrSelf(pass.organizationName)

    CompositionLocalProvider(LocalLocalizedStrings provides strings) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                pass.backFields.forEach { field ->
                    BackFieldRow(
                        field = field,
                        organizationName = displayOrganizationName,
                        onUrlIntent = onUrlIntent,
                        onPhoneIntent = onPhoneIntent,
                        onEmailIntent = onEmailIntent,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackFieldRow(
    field: PassField,
    organizationName: String,
    onUrlIntent: (B3UrlIntent) -> Unit,
    onPhoneIntent: (PhoneIntent) -> Unit,
    onEmailIntent: (EmailIntent) -> Unit,
) {
    // wpass-38y: field label and value pass through the resolved strings table so
    // PKPASS placeholder labels (e.g. "#LABELTICKETNUMBER#") render as their
    // localized text. A miss falls through to the raw string, which is the right
    // behavior for dynamic values (ticket numbers, codes) that never appear as keys.
    // organizationName has already been substituted at the PassBack boundary.
    val strings = LocalLocalizedStrings.current
    val displayLabel = strings.lookupOrSelf(field.label)
    val displayValue = strings.lookupOrSelf(field.value)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        displayLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val source = SourceField(
            fieldKey = field.key,
            fieldLabel = displayLabel,
            organizationName = organizationName,
        )
        val spans = FieldLinkScanner.scan(displayValue, source)
        val annotated = buildAnnotatedFieldValue(displayValue, spans)

        @Suppress("DEPRECATION")
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            onClick = { offset ->
                val span = spans.firstOrNull { offset >= it.start && offset < it.endExclusive }
                when (val intent = span?.intent) {
                    is B3UrlIntent -> onUrlIntent(intent)
                    is PhoneIntent -> onPhoneIntent(intent)
                    is EmailIntent -> onEmailIntent(intent)
                    null -> Unit
                }
            },
        )
    }
}

private fun buildAnnotatedFieldValue(text: String, spans: List<LinkSpan>): AnnotatedString =
    buildAnnotatedString {
        if (spans.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) append(text.substring(cursor, span.start))
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append(text.substring(span.start, span.endExclusive))
            }
            cursor = span.endExclusive
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
