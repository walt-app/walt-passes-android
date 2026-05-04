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
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.ui.internal.FieldLinkScanner
import `is`.walt.passes.ui.internal.LinkSpan

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
 */
@Composable
public fun PassBack(
    pass: Pass,
    onUrlIntent: (B3UrlIntent) -> Unit,
    onPhoneIntent: (PhoneIntent) -> Unit,
    onEmailIntent: (EmailIntent) -> Unit,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") locale: PassLocale = PassLocale("en"),
) {
    LaunchedEffect(pass) { telemetry.onPassBackOpened(pass.type) }

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
                    organizationName = pass.organizationName,
                    onUrlIntent = onUrlIntent,
                    onPhoneIntent = onPhoneIntent,
                    onEmailIntent = onEmailIntent,
                )
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        field.label?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val source = SourceField(
            fieldKey = field.key,
            fieldLabel = field.label,
            organizationName = organizationName,
        )
        val spans = FieldLinkScanner.scan(field.value, source)
        val annotated = buildAnnotatedFieldValue(field.value, spans)

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
