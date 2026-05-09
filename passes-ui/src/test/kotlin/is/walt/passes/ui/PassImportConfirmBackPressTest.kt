package `is`.walt.passes.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.ParseFailureKind
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.PassesTheme
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the trust contract that Android system back-press behaves identically to
 * Cancel on [PassImportConfirm]: same telemetry, same callback. Lives in its own
 * file because the back-press path needs `createAndroidComposeRule<ComponentActivity>()`
 * to reach the activity's `OnBackPressedDispatcher`, while the rest of the UI suite
 * uses `createComposeRule()`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PassImportConfirmBackPressTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val telemetry = RecordingGuard()

    @Test
    fun backPressFiresDismissedTelemetryAndCallback() {
        var dismissed = 0
        var confirmed = 0
        composeRule.setContent {
            ThemedHost {
                PassImportConfirm(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.SelfSigned,
                    telemetry = telemetry,
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(confirmed).isEqualTo(0)
        assertThat(telemetry.events).contains("import-dismiss:Generic:SelfSigned")
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            PassesTheme(semantics = semantics, content = content)
        }
    }

    private val semantics = PassesSemantics(
        signatureBadge = SignatureBadgeColors(
            unsignedBackground = ArgbColor(0xFFFFE0E0.toInt()),
            unsignedForeground = ArgbColor(0xFF101010.toInt()),
            selfSignedBackground = ArgbColor(0xFFFFF0E0.toInt()),
            selfSignedForeground = ArgbColor(0xFF101010.toInt()),
            appleVerifiedBackground = ArgbColor(0xFFE0FFE0.toInt()),
            appleVerifiedForeground = ArgbColor(0xFF101010.toInt()),
            certChainIncompleteBackground = ArgbColor(0xFFFFFFE0.toInt()),
            certChainIncompleteForeground = ArgbColor(0xFF101010.toInt()),
        ),
        expiredBadge = ExpiredBadgeStyle(
            pillBackground = ArgbColor(0xFF202020.toInt()),
            pillForeground = ArgbColor(0xFFFFFFFF.toInt()),
            scrimAlpha = 96,
        ),
        securitySheet = SecuritySheetStyle(
            sheetBackground = ArgbColor(0xFFFFFFFF.toInt()),
            emphasisBackground = ArgbColor(0xFFEFEFEF.toInt()),
            emphasisForeground = ArgbColor(0xFF000000.toInt()),
            bodyForeground = ArgbColor(0xFF202020.toInt()),
            confirmContainer = ArgbColor(0xFF202020.toInt()),
            confirmForeground = ArgbColor(0xFFFFFFFF.toInt()),
            cancelForeground = ArgbColor(0xFF202020.toInt()),
        ),
        categoryAccent = CategoryAccentColors(
            boardingPass = ArgbColor(0xFF1D4ED8.toInt()),
            eventTicket = ArgbColor(0xFF7C2D92.toInt()),
            coupon = ArgbColor(0xFF555555.toInt()),
            storeCard = ArgbColor(0xFF555555.toInt()),
            generic = ArgbColor(0xFF555555.toInt()),
        ),
    )

    private fun passFixture(): Pass = Pass(
        type = PassType.Generic,
        serialNumber = "0",
        description = "fixture",
        organizationName = "Acme",
        expirationDate = null as PassInstant?,
        voided = false,
        colors = PassColors(
            foreground = ColorValue(0x000000),
            background = ColorValue(0xFFFFFF),
            label = ColorValue(0x444444),
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = "From", value = "JFK")),
        ),
        backFields = emptyList(),
        barcode = null,
        images = emptyMap(),
        locales = emptyMap(),
    )

    private class RecordingGuard : UiTelemetryGuard {
        val events: MutableList<String> = mutableListOf()
        override fun onPassRendered(type: PassType, signatureBand: SignatureBand) {
            events += "rendered:${type.name}:${signatureBand.name}"
        }
        override fun onPassBackOpened(type: PassType) { events += "back:${type.name}" }
        override fun onSecuritySheetShown(intentKind: SecurityIntentKind, type: PassType) {
            events += "shown:${intentKind.name}:${type.name}"
        }
        override fun onSecuritySheetConfirmed(intentKind: SecurityIntentKind, type: PassType) {
            events += "confirm:${intentKind.name}:${type.name}"
        }
        override fun onSecuritySheetDismissed(intentKind: SecurityIntentKind, type: PassType) {
            events += "dismiss:${intentKind.name}:${type.name}"
        }
        override fun onImageDecodeRejected(reason: ImageDecodeRejection) {
            events += "decode:${reason.name}"
        }
        override fun onImportConfirmShown(type: PassType, signatureBand: SignatureBand) {
            events += "import-shown:${type.name}:${signatureBand.name}"
        }
        override fun onImportConfirmed(type: PassType, signatureBand: SignatureBand) {
            events += "import-confirm:${type.name}:${signatureBand.name}"
        }
        override fun onImportDismissed(type: PassType, signatureBand: SignatureBand) {
            events += "import-dismiss:${type.name}:${signatureBand.name}"
        }
        override fun onImportRejected(kind: ParseFailureKind) {
            events += "import-rejected:${kind.name}"
        }
    }
}
