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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ParseFailureKind
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Trust-claim-bearing rejection sheet shown when an in-app pass import fails. Used
 * after the host's `PassParser.parse` returned [is.walt.passes.core.ParseResult.Tampered],
 * `Malformed`, or `Unsupported`. The sheet's copy is the user-facing trust message
 * walt-android cannot reimplement: a future refactor that swapped this for a
 * generic toast would silently drop the "we detected tampering" disclosure.
 *
 * The four [ParseFailureKind] arms map to four distinct messages — collapsing them
 * defeats the lenient-with-disclosure signature policy (decision-wlt-0tn-q1 1a). A
 * tampered pass is a security event; a malformed file is not. The user must see the
 * difference.
 *
 * The sheet is dismiss-only: there is no Save / Open / Anyway button. Tampered, malformed,
 * unsupported, and resource-limit failures are all unconditional rejections at v1; ADR
 * 0001's parser-hardening posture is "fail closed and explain."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PassImportRejectionSheet(
    kind: ParseFailureKind,
    telemetry: UiTelemetryGuard,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emphasis = LocalPassesSemantics.current.securitySheet

    LaunchedEffect(kind) {
        telemetry.onImportRejected(kind)
    }

    val copy = rejectionCopy(kind)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                text = copy.title,
                style = MaterialTheme.typography.headlineSmall,
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
                    text = copy.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = emphasis.emphasisForeground.toComposeColor(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = emphasis.cancelForeground.toComposeColor(),
                    ),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

private data class RejectionCopy(val title: String, val body: String)

private fun rejectionCopy(kind: ParseFailureKind): RejectionCopy = when (kind) {
    ParseFailureKind.Tampered -> RejectionCopy(
        title = "This pass appears to have been tampered with",
        body = "The signature does not match the file's contents. Walt did not save this pass.",
    )
    ParseFailureKind.Malformed -> RejectionCopy(
        title = "This file is not a valid pass",
        body = "Walt could not read this file as a PKPASS archive.",
    )
    ParseFailureKind.Unsupported -> RejectionCopy(
        title = "Walt cannot open this pass",
        body = "This pass uses a format Walt does not support.",
    )
    ParseFailureKind.ResourceLimitExceeded -> RejectionCopy(
        title = "This pass is too large to open safely",
        body = "The pass exceeded Walt's safety limits and was not loaded.",
    )
}
