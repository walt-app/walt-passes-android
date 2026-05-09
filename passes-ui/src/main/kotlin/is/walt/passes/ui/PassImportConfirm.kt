package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Trust-claim-bearing confirmation surface for the in-app PKPASS import flow.
 *
 * Used after the host has read a chosen file's bytes and `PassParser.parse` returned
 * [is.walt.passes.core.ParseResult.Success]. The user sees the parsed pass exactly as
 * Walt will store it — preview rendered through [PassFront], signature trust band
 * captioned in plain copy — before they tap Save. Tapping Cancel discards.
 *
 * The trust contract this surface carries (decision-wlt-0tn-q1):
 *   1. The user MUST see the signature trust band before consenting to store. The
 *      caption is non-suppressible: there is no `showTrustCaption` parameter, no
 *      `compact` mode that hides it, no theme slot whose value defeats it.
 *   2. The pass preview is the parsed, post-localization, post-validation [pass] —
 *      the same data structure that will be persisted by `PassRepository.upsert`.
 *      Walt-android cannot show an alternate preview here.
 *   3. Confirm fires only on the user's explicit Save tap. The host's call to
 *      `PassRepository.upsert` MUST be the next thing constructed inside [onConfirm].
 *
 * This is a leaf composable: walt-android wraps it in its own scaffold / nav back-
 * stack. The dimensions of the pass preview (PassFront fills available width) make a
 * bottom sheet awkward; a screen-level layout is preferred.
 *
 * [locale] drives `pass.strings` substitution in the preview, identical to [PassFront].
 * Defaults to `PassLocale("en")` for tests / previews; production callers thread the
 * device locale through.
 */
@Composable
public fun PassImportConfirm(
    pass: Pass,
    signatureStatus: SignatureStatus,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
) {
    val band = remember(signatureStatus) { signatureStatus.toBand() }
    val emphasis = LocalPassesSemantics.current.securitySheet

    LaunchedEffect(pass.type, band) {
        telemetry.onImportConfirmShown(pass.type, band)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Add this pass?",
            style = MaterialTheme.typography.headlineSmall,
            color = emphasis.bodyForeground.toComposeColor(),
        )

        ImportTrustCaption(band = band)

        PassFront(
            pass = pass,
            signatureStatus = signatureStatus,
            telemetry = telemetry,
            modifier = Modifier.fillMaxWidth(),
            locale = locale,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(
                onClick = {
                    telemetry.onImportDismissed(pass.type, band)
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
                    telemetry.onImportConfirmed(pass.type, band)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = emphasis.confirmContainer.toComposeColor(),
                    contentColor = emphasis.confirmForeground.toComposeColor(),
                ),
            ) {
                Text("Save pass")
            }
        }
    }
}

@Composable
private fun ImportTrustCaption(band: SignatureBand) {
    val emphasis = LocalPassesSemantics.current.securitySheet

    // The four copy lines mirror decision-wlt-0tn-q1 (1a): "Verified Apple issuer" /
    // "Self-signed" / "Cert chain incomplete" / "No signature". Issuer name is shown
    // by the PassFront preview directly below; repeating it here would duplicate
    // user-controlled text without adding signal.
    val (title, body) = when (band) {
        SignatureBand.AppleVerified ->
            "Verified Apple issuer" to
                "Walt verified this pass's signature against Apple's issuer chain."
        SignatureBand.SelfSigned ->
            "Self-signed issuer" to
                "The signature is valid but Walt cannot verify who issued this pass."
        SignatureBand.Incomplete ->
            "Issuer chain incomplete" to
                "The pass is signed but Walt could not complete the issuer chain."
        SignatureBand.Untrusted ->
            "No signature" to
                "This pass is unsigned. Walt cannot verify who created it."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(emphasis.emphasisBackground.toComposeColor())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = emphasis.emphasisForeground.toComposeColor(),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = emphasis.emphasisForeground.toComposeColor(),
        )
    }
}

// PassFront's private toBand() is duplicated here so this file does not need a
// passes-core extension or a cross-file private. The mapping is total over
// SignatureStatus (four arms in, four arms out); a new arm in either type is a
// compile error.
private fun SignatureStatus.toBand(): SignatureBand = when (this) {
    SignatureStatus.Unsigned -> SignatureBand.Untrusted
    SignatureStatus.SelfSigned -> SignatureBand.SelfSigned
    SignatureStatus.AppleVerified -> SignatureBand.AppleVerified
    SignatureStatus.CertChainIncomplete -> SignatureBand.Incomplete
}
