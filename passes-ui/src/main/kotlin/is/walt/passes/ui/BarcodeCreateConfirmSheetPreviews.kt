package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import `is`.walt.passes.core.QrPayloadKind
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.PassesTheme
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    val argb = ArgbColor(0xFF202020.toInt())
    val white = ArgbColor(0xFFFFFFFF.toInt())
    val panel = ArgbColor(0xFFEFEFEF.toInt())
    val semantics = PassesSemantics(
        signatureBadge = SignatureBadgeColors(
            unsignedBackground = argb, unsignedForeground = white,
            selfSignedBackground = argb, selfSignedForeground = white,
            appleVerifiedBackground = argb, appleVerifiedForeground = white,
            certChainIncompleteBackground = argb, certChainIncompleteForeground = white,
        ),
        expiredBadge = ExpiredBadgeStyle(
            pillBackground = argb, pillForeground = white, scrimAlpha = 96,
        ),
        securitySheet = SecuritySheetStyle(
            sheetBackground = white,
            emphasisBackground = panel,
            emphasisForeground = argb,
            bodyForeground = argb,
            confirmContainer = argb,
            confirmForeground = white,
            cancelForeground = argb,
        ),
        categoryAccent = CategoryAccentColors(
            boardingPass = argb, eventTicket = argb,
            coupon = argb, storeCard = argb, generic = argb,
        ),
    )
    MaterialTheme { PassesTheme(semantics = semantics, content = content) }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Url() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Url(
                scheme = "https",
                host = "example.com",
                raw = "https://example.com/login",
            ),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Phone() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Phone("+44 7700 900000"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Sms() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Sms("+44 7700 900000"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Mailto() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Mailto("alice@example.com"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Geo() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Geo("51.5074,-0.1278"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Wifi() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Wifi(ssid = "MyNetwork"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_WifiUnnamed() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Wifi(ssid = null),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Bitcoin() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Bitcoin("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Ethereum() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Ethereum("0x71C7656EC7ab88b098defB751B7401B5f6d8976F"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Magnet() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Magnet,
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Market() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Market("details?id=com.example.app"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Intent() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.Intent("intent://example#Intent;scheme=https;end"),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBarcodeCreateConfirm_Unknown() {
    PreviewHost {
        BarcodeCreateConfirmSheet(
            payloadKind = QrPayloadKind.UnknownScheme(
                scheme = "myapp",
                raw = "myapp://action?id=1",
            ),
            telemetry = NoopUiTelemetryGuard,
            onConfirm = {},
            onCancel = {},
        )
    }
}
