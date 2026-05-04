package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.PassType
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Security confirmation bottom sheet for an outbound URL detected on a pass back-field.
 *
 * The sheet displays the issuer, the source field label, and the verbatim URL the host
 * is about to open. `onConfirm` fires only on the user's explicit confirm tap; the
 * host's outbound `Intent.ACTION_VIEW` MUST be the next thing constructed after that
 * callback fires. There is no `skipConfirmation` parameter — see ADR 0003 D5.
 */
@Composable
public fun B3UrlConfirmSheet(
    intent: B3UrlIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Url,
        passType = passType,
        title = "Open this link?",
        target = intent.url,
        source = intent.sourceField,
        confirmCopy = "Open link",
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Security confirmation bottom sheet for an outbound phone number detected on a pass
 * back-field. Displays the verbatim digits the host is about to dial.
 */
@Composable
public fun PhoneConfirmSheet(
    intent: PhoneIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Phone,
        passType = passType,
        title = "Call this number?",
        target = intent.phoneNumber,
        source = intent.sourceField,
        confirmCopy = "Call",
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Security confirmation bottom sheet for an outbound email address. Displays the
 * verbatim address; the host's outbound composer Intent receives ONLY the address
 * (no subject, no body) — see TRUST_CLAIMS.md.
 */
@Composable
public fun EmailConfirmSheet(
    intent: EmailIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Email,
        passType = passType,
        title = "Send an email?",
        target = intent.emailAddress,
        source = intent.sourceField,
        confirmCopy = "Compose",
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecuritySheet(
    kind: SecurityIntentKind,
    passType: PassType,
    title: String,
    target: String,
    source: SourceField,
    confirmCopy: String,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emphasis = LocalPassesSemantics.current.securitySheet

    LaunchedEffect(kind, passType) {
        telemetry.onSecuritySheetShown(kind, passType)
    }

    ModalBottomSheet(
        onDismissRequest = {
            telemetry.onSecuritySheetDismissed(kind, passType)
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = emphasis.sheetBackground.toComposeColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                // Title is a hardcoded English literal, not user-controlled, so no
                // isolation needed.
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = emphasis.bodyForeground.toComposeColor(),
            )
            Text(
                // Organization name and field label come straight from the parsed
                // pass and ARE user-controlled. Isolate each independently so a
                // bidi character in either cannot reorder the rest of the line.
                text = isolated(source.organizationName) +
                    (source.fieldLabel?.let { " — ${isolated(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = emphasis.bodyForeground.toComposeColor(),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(emphasis.emphasisBackground.toComposeColor())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    // The verbatim target is the trust-claim load-bearing string.
                    // Isolate it so any residual directional marks (the scanner
                    // already rejects Cf/Cc, but defense in depth) cannot reorder
                    // glyphs against surrounding context.
                    text = isolated(target),
                    style = MaterialTheme.typography.bodyLarge,
                    color = emphasis.emphasisForeground.toComposeColor(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = {
                        telemetry.onSecuritySheetDismissed(kind, passType)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = emphasis.cancelForeground.toComposeColor(),
                    ),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        telemetry.onSecuritySheetConfirmed(kind, passType)
                        onConfirm()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = emphasis.confirmContainer.toComposeColor(),
                        contentColor = emphasis.confirmForeground.toComposeColor(),
                    ),
                ) {
                    Text(confirmCopy)
                }
            }
        }
    }
}

/**
 * Wrap [s] in Unicode First-Strong Isolate / Pop Directional Isolate (U+2068, U+2069).
 * Inside the isolate the bidi algorithm treats the contents as a single neutral
 * directional unit: characters within cannot reorder text outside, and surrounding
 * directional context cannot reorder characters within. This is the recommended
 * fence for displaying user-controlled strings in bidi-sensitive surfaces (UAX #9
 * §3.4 isolate formatting characters).
 *
 * Combined with the Cf/Cc rejection in `FieldLinkScanner`, this guarantees that the
 * sheet's displayed target string is rendered as-typed; an attacker can no longer
 * craft a pass field that looks visually like a trusted host while parsing as a
 * hostile one.
 */
internal fun isolated(s: String): String = "$FSI$s$PDI"

/** First Strong Isolate (U+2068). Opens an isolate that takes the directional class of the first strong-class character within. */
internal const val FSI: Char = '⁨'

/** Pop Directional Isolate (U+2069). Closes the most recently opened isolate. */
internal const val PDI: Char = '⁩'
