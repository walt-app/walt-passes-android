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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.QrPayloadKind
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Create-time URI-scheme confirmation gate for a QR `ScannableCard`. Mirrors the
 * verbatim-rendering / FSI-PDI isolation / trust-styling-token posture of the
 * back-field [B3UrlConfirmSheet], but inverts the button prominence: Cancel is the
 * focused, filled action; Confirm is the lower-emphasis text button. That divergence
 * is pinned by `ComposableSurfaceLockTest`; a future neighbor refactor that resyncs
 * the two will trip the lock. The dialog runs between input validation and
 * persistence so a payload classified by [QrPayloadKind] as auto-acting on a future
 * scanner phone cannot land in the wallet without an explicit confirm tap.
 *
 * Triggered ONLY for the QR symbology - linear formats do not auto-act when
 * scanned and skip this gate at the caller. [QrPayloadKind.PlainText] also skips:
 * the composable short-circuits to no output so the caller's create flow can be a
 * single unconditional `BarcodeCreateConfirmSheet` invocation.
 *
 * The trust claim that this gate enforces is "the user saw, in their own typed
 * form, what a scanner phone would do" - the verbatim payload is the load-bearing
 * surface. Crypto addresses are rendered in full (monospace + wrap) rather than
 * masked: at create time the user is verifying their own transcription, and base58
 * / hex typos accumulate in the middle of the string - exactly where a mask would
 * hide them. Wrapping in [isolated] is defense in depth against any residual
 * directional context surrounding the sheet chrome; the upstream validator
 * (wpass-lzi.4) already rejects Cf/Cc characters in the payload itself.
 *
 * Emits [UiTelemetryGuard.onBarcodeCreateGateShown] on first composition for each
 * non-PlainText payload kind, [onBarcodeCreateGateConfirmed] on confirm tap, and
 * [onBarcodeCreateGateDismissed] on cancel tap or sheet dismissal. The
 * [BarcodeCreateKind] dimension is the coarse family of the underlying
 * [QrPayloadKind] - the user-typed string itself never crosses the boundary,
 * matching the PII discipline pinned by `PublicApiSurfaceTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BarcodeCreateConfirmSheet(
    payloadKind: QrPayloadKind,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (payloadKind is QrPayloadKind.PlainText) return

    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emphasis = LocalPassesSemantics.current.securitySheet
    val cancelFocus = remember { FocusRequester() }
    val kind = barcodeCreateKindOf(payloadKind)
    LaunchedEffect(kind) { telemetry.onBarcodeCreateGateShown(kind) }
    // Cancel-default focus: the prominent action wins keyboard / d-pad focus on
    // open so muscle memory pointed at the primary slot lands on Cancel. The
    // LaunchedEffect fires after composition, so the requester is attached by
    // the time requestFocus() runs; a throw here is a real regression worth
    // surfacing, not swallowing.
    LaunchedEffect(payloadKind) { cancelFocus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = {
            telemetry.onBarcodeCreateGateDismissed(kind)
            onCancel()
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
            BarcodeCreateBody(payloadKind = payloadKind, emphasis = emphasis)
            BarcodeCreateActions(
                emphasis = emphasis,
                cancelFocus = cancelFocus,
                onConfirm = {
                    telemetry.onBarcodeCreateGateConfirmed(kind)
                    onConfirm()
                },
                onCancel = {
                    telemetry.onBarcodeCreateGateDismissed(kind)
                    onCancel()
                },
            )
        }
    }
}

/**
 * Maps a [QrPayloadKind] to its coarse [BarcodeCreateKind] family for telemetry.
 * [QrPayloadKind.PlainText] is unreachable here - the caller short-circuits before
 * any telemetry-bearing path - but the `when` enumerates it so adding a new arm in
 * `passes-core` surfaces as a missing-branch compile error rather than a silent
 * fallthrough to the wrong family.
 */
@Suppress("CyclomaticComplexMethod")
private fun barcodeCreateKindOf(payloadKind: QrPayloadKind): BarcodeCreateKind = when (payloadKind) {
    is QrPayloadKind.PlainText -> error("PlainText payloads short-circuit the gate before telemetry fires")
    is QrPayloadKind.Url -> BarcodeCreateKind.Url
    is QrPayloadKind.Phone -> BarcodeCreateKind.Phone
    is QrPayloadKind.Sms -> BarcodeCreateKind.Sms
    is QrPayloadKind.Mailto -> BarcodeCreateKind.Mailto
    is QrPayloadKind.Geo -> BarcodeCreateKind.Geo
    is QrPayloadKind.Wifi -> BarcodeCreateKind.Wifi
    is QrPayloadKind.Bitcoin -> BarcodeCreateKind.Bitcoin
    is QrPayloadKind.Ethereum -> BarcodeCreateKind.Ethereum
    QrPayloadKind.Magnet -> BarcodeCreateKind.Magnet
    is QrPayloadKind.Market -> BarcodeCreateKind.Market
    is QrPayloadKind.Intent -> BarcodeCreateKind.Intent
    is QrPayloadKind.UnknownScheme -> BarcodeCreateKind.UnknownScheme
}

@Composable
private fun BarcodeCreateBody(
    payloadKind: QrPayloadKind,
    emphasis: SecuritySheetStyle,
) {
    val message = barcodeCreateMessage(payloadKind)
    val verbatim = verbatimForKind(payloadKind)
    Text(
        text = stringResource(R.string.barcode_create_confirm_title),
        style = MaterialTheme.typography.headlineSmall,
        color = emphasis.bodyForeground.toComposeColor(),
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = emphasis.bodyForeground.toComposeColor(),
    )
    if (verbatim != null) {
        // Crypto addresses get monospace + wrap so every character is
        // distinguishable for create-time transcription review.
        val isCryptoAddress = payloadKind is QrPayloadKind.Bitcoin ||
            payloadKind is QrPayloadKind.Ethereum
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(emphasis.emphasisBackground.toComposeColor())
                .padding(16.dp),
        ) {
            Text(
                // User-controlled payload data, fenced in FSI/PDI so any
                // directional context cannot reorder it against chrome.
                text = isolated(verbatim),
                style = MaterialTheme.typography.bodyLarge,
                color = emphasis.emphasisForeground.toComposeColor(),
                fontFamily = if (isCryptoAddress) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
private fun BarcodeCreateActions(
    emphasis: SecuritySheetStyle,
    cancelFocus: FocusRequester,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        // Confirm is the low-emphasis text button - inverted from B3Url so a
        // stray dismissal lands on Cancel, not on a silent persist.
        TextButton(
            onClick = onConfirm,
            modifier = Modifier.testTag(BarcodeCreateConfirmTestTags.Confirm),
            colors = ButtonDefaults.textButtonColors(
                contentColor = emphasis.cancelForeground.toComposeColor(),
            ),
        ) {
            Text(stringResource(R.string.barcode_create_confirm_confirm))
        }
        Button(
            onClick = onCancel,
            modifier = Modifier
                .focusRequester(cancelFocus)
                .testTag(BarcodeCreateConfirmTestTags.Cancel),
            colors = ButtonDefaults.buttonColors(
                containerColor = emphasis.confirmContainer.toComposeColor(),
                contentColor = emphasis.confirmForeground.toComposeColor(),
            ),
        ) {
            Text(stringResource(R.string.barcode_create_confirm_cancel))
        }
    }
}

/**
 * Whether [BarcodeCreateConfirmSheet] should be invoked for [QrPayloadKind]. Returns
 * `false` only for [QrPayloadKind.PlainText]; callers branch on this to persist a
 * plain-text QR directly without showing a no-op sheet.
 */
public fun QrPayloadKind.requiresCreateConfirmation(): Boolean =
    this !is QrPayloadKind.PlainText

/**
 * Test tags for the two action buttons. Exposed so per-arm focus and click assertions
 * in `BarcodeCreateConfirmSheetTest` (and any host smoke test) can target the buttons
 * structurally rather than by their localized labels.
 */
public object BarcodeCreateConfirmTestTags {
    public const val Cancel: String = "barcode-create-confirm:cancel"
    public const val Confirm: String = "barcode-create-confirm:confirm"
}

/**
 * Per-arm dispatcher for the warning sentence shown above the verbatim payload.
 * Cyclomatic complexity is load-bearing: every [QrPayloadKind] arm enumerated in
 * one place forces adding a new arm in `passes-core` to surface here as a missing-
 * branch compile error, rather than silently rendering an empty warning.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
private fun barcodeCreateMessage(kind: QrPayloadKind): String = when (kind) {
    is QrPayloadKind.PlainText -> ""
    is QrPayloadKind.Url -> stringResource(R.string.barcode_create_confirm_url)
    is QrPayloadKind.Phone -> stringResource(R.string.barcode_create_confirm_phone)
    is QrPayloadKind.Sms -> stringResource(R.string.barcode_create_confirm_sms)
    is QrPayloadKind.Mailto -> stringResource(R.string.barcode_create_confirm_mailto)
    is QrPayloadKind.Geo -> stringResource(R.string.barcode_create_confirm_geo)
    is QrPayloadKind.Wifi ->
        if (kind.ssid != null) stringResource(R.string.barcode_create_confirm_wifi)
        else stringResource(R.string.barcode_create_confirm_wifi_unnamed)
    is QrPayloadKind.Bitcoin -> stringResource(R.string.barcode_create_confirm_bitcoin)
    is QrPayloadKind.Ethereum -> stringResource(R.string.barcode_create_confirm_ethereum)
    QrPayloadKind.Magnet -> stringResource(R.string.barcode_create_confirm_magnet)
    is QrPayloadKind.Market -> stringResource(R.string.barcode_create_confirm_market)
    is QrPayloadKind.Intent -> stringResource(R.string.barcode_create_confirm_intent)
    is QrPayloadKind.UnknownScheme -> stringResource(R.string.barcode_create_confirm_unknown)
}

/**
 * Per-arm dispatcher for the verbatim payload string rendered in the emphasis
 * panel. Same load-bearing-complexity rationale as [barcodeCreateMessage].
 */
@Suppress("CyclomaticComplexMethod")
internal fun verbatimForKind(kind: QrPayloadKind): String? = when (kind) {
    is QrPayloadKind.PlainText -> null
    is QrPayloadKind.Url -> kind.host ?: kind.raw
    is QrPayloadKind.Phone -> kind.number
    is QrPayloadKind.Sms -> kind.number
    is QrPayloadKind.Mailto -> kind.address
    is QrPayloadKind.Geo -> kind.coords
    is QrPayloadKind.Wifi -> kind.ssid
    is QrPayloadKind.Bitcoin -> kind.address
    is QrPayloadKind.Ethereum -> kind.address
    QrPayloadKind.Magnet -> null
    is QrPayloadKind.Market -> kind.productId
    is QrPayloadKind.Intent -> kind.raw
    is QrPayloadKind.UnknownScheme -> kind.raw
}
