package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.PassType
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Security confirmation bottom sheet for an outbound URL detected on a pass back-field.
 *
 * The sheet displays the issuer, the source field label, and the verbatim URL the host
 * is about to open. `onConfirm` fires only on the user's explicit confirm tap; the
 * host's outbound `Intent.ACTION_VIEW` MUST be the next thing constructed after that
 * callback fires. There is no `skipConfirmation` parameter — see ADR 0003 D5.
 *
 * @param emphasisStyle Layout choice between [B3EmphasisStyle.Container] (default,
 *   behavior-identical to pre-wpass-48v) and [B3EmphasisStyle.DomainHero]. Both
 *   layouts show the verbatim URL; only the visual prominence and the second
 *   destination-summary line differ. See the [B3EmphasisStyle] kdoc.
 */
@Suppress("LongParameterList")
@Composable
public fun B3UrlConfirmSheet(
    intent: B3UrlIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Url,
        passType = passType,
        title = "Open this link?",
        target = intent.url,
        hero = intent.registrableDomain,
        source = intent.sourceField,
        // Copy diverges between layouts: Container keeps the original "Open link"
        // so existing call sites stay byte-identical (wpass-48v reviewer note),
        // DomainHero uses "Open in browser" because the destination-summary hero
        // makes the action explicit.
        confirmCopy = when (emphasisStyle) {
            B3EmphasisStyle.Container -> "Open link"
            B3EmphasisStyle.DomainHero -> "Open in browser"
        },
        emphasisStyle = emphasisStyle,
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Security confirmation bottom sheet for an outbound phone number detected on a pass
 * back-field. Displays the verbatim digits the host is about to dial.
 *
 * @param emphasisStyle See [B3UrlConfirmSheet].
 */
@Suppress("LongParameterList")
@Composable
public fun PhoneConfirmSheet(
    intent: PhoneIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Phone,
        passType = passType,
        title = "Call this number?",
        target = intent.phoneNumber,
        // The verbatim string already IS the formatted phone in real input; the
        // hero collapses adjacent spaces / dashes for a more compact read but the
        // forensic row carries every character the dialer receives.
        hero = phoneHeroOf(intent.phoneNumber),
        source = intent.sourceField,
        confirmCopy = "Call",
        emphasisStyle = emphasisStyle,
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Security confirmation bottom sheet for an outbound email address. Displays the
 * verbatim address; the host's outbound composer Intent receives ONLY the address
 * (no subject, no body) — see TRUST_CLAIMS.md.
 *
 * @param emphasisStyle See [B3UrlConfirmSheet].
 */
@Suppress("LongParameterList")
@Composable
public fun EmailConfirmSheet(
    intent: EmailIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
) {
    SecuritySheet(
        kind = SecurityIntentKind.Email,
        passType = passType,
        title = "Send an email?",
        target = intent.emailAddress,
        // Hero is the *host portion* of the address, NOT the local-part. The
        // local-part is attacker-chosen (`support@phisher.example` can pick any
        // friendly local-part); the host is the security-relevant half. Mirrors
        // the URL layout's domain-as-hero policy. Falls back to the full address
        // when the `@` is missing (an EmailIntent constructed outside the scanner
        // that took an ill-formed string — the forensic row still keeps the
        // verbatim contract).
        hero = emailHostHero(intent.emailAddress),
        source = intent.sourceField,
        confirmCopy = "Compose",
        emphasisStyle = emphasisStyle,
        telemetry = telemetry,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private fun phoneHeroOf(phone: String): String =
    phone.trim().replace(Regex("\\s+"), " ")

private fun emailHostHero(email: String): String {
    val at = email.indexOf('@')
    return if (at in 0 until email.length - 1) email.substring(at + 1) else email
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
private fun SecuritySheet(
    kind: SecurityIntentKind,
    passType: PassType,
    title: String,
    target: String,
    hero: String?,
    source: SourceField,
    confirmCopy: String,
    emphasisStyle: B3EmphasisStyle,
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
        when (emphasisStyle) {
            B3EmphasisStyle.Container -> ContainerLayout(
                emphasis = emphasis,
                title = title,
                target = target,
                source = source,
                confirmCopy = confirmCopy,
                kind = kind,
                passType = passType,
                telemetry = telemetry,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
            B3EmphasisStyle.DomainHero -> DomainHeroLayout(
                emphasis = emphasis,
                target = target,
                hero = hero ?: target,
                source = source,
                confirmCopy = confirmCopy,
                kind = kind,
                passType = passType,
                telemetry = telemetry,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ContainerLayout(
    emphasis: SecuritySheetStyle,
    title: String,
    target: String,
    source: SourceField,
    confirmCopy: String,
    kind: SecurityIntentKind,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun DomainHeroLayout(
    emphasis: SecuritySheetStyle,
    target: String,
    hero: String,
    source: SourceField,
    confirmCopy: String,
    kind: SecurityIntentKind,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The DomainHero typography reads from MaterialTheme so hosts retain the
    // theme contract: walt-android's core/ui supplies the type ramp, dark/large-
    // text/dynamic-color scaling flow through unchanged. The kernel commits to
    // the *layout shape* (eyebrow + hero + forensic + provenance + actions),
    // not to specific point sizes or letter spacing. The textual emphasis
    // hierarchy (hero > target > provenance) survives any theme.
    //
    // All four colors come from PassesSemantics.securitySheet — never raw
    // MaterialTheme.colorScheme — so a host that retunes the security sheet via
    // PassesTheme gets a fully-themed DomainHero, not a half-themed one.
    val body = emphasis.bodyForeground.toComposeColor()
    val eyebrow = emphasis.eyebrowForeground.toComposeColor()
    val muted = emphasis.mutedForeground.toComposeColor()
    val eyebrowCopy = when (kind) {
        SecurityIntentKind.Url -> "LEAVING WALT"
        SecurityIntentKind.Phone -> "CALLING"
        SecurityIntentKind.Email -> "EMAILING"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = eyebrowCopy,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = eyebrow,
        )
        Text(
            // Hero is user-controlled (it's derived from the intent target); isolate
            // it the same way as the verbatim target so a bidi character cannot
            // reorder surrounding chrome.
            text = isolated(hero),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = body,
        )
        Text(
            // Forensic row: the verbatim target is the trust-claim load-bearing
            // string. Monospace + lower visual weight so the hero leads, but the
            // string is always visible.
            text = isolated(target),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = muted,
        )
        Text(
            text = provenanceAnnotated(source, body, muted),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(muted),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    telemetry.onSecuritySheetDismissed(kind, passType)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = emphasis.cancelForeground.toComposeColor(),
                ),
            ) {
                Text("Cancel", textAlign = TextAlign.Center)
            }
            Button(
                onClick = {
                    telemetry.onSecuritySheetConfirmed(kind, passType)
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = emphasis.confirmContainer.toComposeColor(),
                    contentColor = emphasis.confirmForeground.toComposeColor(),
                ),
            ) {
                Text(confirmCopy, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun provenanceAnnotated(
    source: SourceField,
    bodyColor: Color,
    dimColor: Color,
): AnnotatedString {
    val label = source.fieldLabel?.let { isolated(it) }
    val org = isolated(source.organizationName)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = dimColor)) {
            append(if (label != null) "From the " else "From your ")
        }
        if (label != null) {
            withStyle(SpanStyle(color = bodyColor, fontWeight = FontWeight.SemiBold)) {
                append(label)
            }
            withStyle(SpanStyle(color = dimColor)) {
                append(" field on your ")
            }
        }
        withStyle(SpanStyle(color = bodyColor, fontWeight = FontWeight.SemiBold)) {
            append(org)
        }
        withStyle(SpanStyle(color = dimColor)) {
            append(" pass.")
        }
    }
}

// `isolated()`, `FSI`, and `PDI` moved to `passes-ui-core` so `passes-pdf-ui` can use the
// same bidi fence without depending on `passes-ui` (wpass-r4z). Imported above.
