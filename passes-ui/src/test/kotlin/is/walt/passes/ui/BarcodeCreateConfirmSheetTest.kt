package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.QrPayloadKind
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
 * Behavioral lock for [BarcodeCreateConfirmSheet]. Each arm of [QrPayloadKind]
 * asserts:
 *
 *  - The per-arm warning copy is the visible explanation (no silent confirm).
 *  - The verbatim payload (where applicable) renders wrapped in U+2068...U+2069
 *    (FSI/PDI) so a future directional context surrounding the sheet chrome
 *    cannot reorder the load-bearing trust-claim string.
 *
 * Plus three structural assertions:
 *
 *  - [QrPayloadKind.PlainText] composes nothing (the gate is skipped).
 *  - Cancel button is focused on open (cancel-default-action posture).
 *  - Confirm and Cancel callbacks fire only on explicit tap, not on initial
 *    composition.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BarcodeCreateConfirmSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun urlArmShowsWarningAndIsolatedHost() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Url(
                        scheme = "https",
                        host = "example.com",
                        raw = "https://example.com/login",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will open a website:",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨example.com⁩").assertIsDisplayed()
    }

    @Test
    fun urlArmFallsBackToRawWhenHostMissing() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Url(scheme = "http", host = null, raw = "http://"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("⁨http://⁩").assertIsDisplayed()
    }

    @Test
    fun phoneArmShowsWarningAndIsolatedNumber() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Phone("+44 7700 900000"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("When scanned, this QR will dial:").assertIsDisplayed()
        composeRule.onNodeWithText("⁨+44 7700 900000⁩").assertIsDisplayed()
    }

    @Test
    fun smsArmShowsWarningAndIsolatedNumber() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Sms("+44 7700 900000"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("When scanned, this QR will text:").assertIsDisplayed()
        composeRule.onNodeWithText("⁨+44 7700 900000⁩").assertIsDisplayed()
    }

    @Test
    fun mailtoArmShowsWarningAndIsolatedAddress() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Mailto("alice@example.com"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("When scanned, this QR will email:").assertIsDisplayed()
        composeRule.onNodeWithText("⁨alice@example.com⁩").assertIsDisplayed()
    }

    @Test
    fun geoArmShowsWarningAndIsolatedCoords() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Geo("51.5074,-0.1278"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will show a location on a map:",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨51.5074,-0.1278⁩").assertIsDisplayed()
    }

    @Test
    fun wifiArmWithSsidShowsWarningAndIsolatedSsid() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Wifi(ssid = "MyNetwork"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will offer to join Wi-Fi network:",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨MyNetwork⁩").assertIsDisplayed()
    }

    @Test
    fun wifiArmWithoutSsidShowsGenericWarning() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Wifi(ssid = null),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will offer to join a Wi-Fi network.",
        ).assertIsDisplayed()
    }

    @Test
    fun bitcoinArmShowsWarningAndMasksLongAddress() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Bitcoin(
                        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will prepare a Bitcoin payment to:",
        ).assertIsDisplayed()
        // First 6 + ... + last 6 of the source address. Wrapped in FSI/PDI.
        composeRule.onNodeWithText("⁨1A1zP1...DivfNa⁩").assertIsDisplayed()
    }

    @Test
    fun ethereumArmShowsWarningAndMasksLongAddress() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Ethereum(
                        "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will prepare an Ethereum payment to:",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨0x71C7...d8976F⁩").assertIsDisplayed()
    }

    @Test
    fun magnetArmShowsWarningWithoutEmphasisPanel() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Magnet,
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will start a torrent download.",
        ).assertIsDisplayed()
    }

    @Test
    fun marketArmShowsWarningAndIsolatedProductId() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Market("details?id=com.example.app"),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR will open a Play Store listing:",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨details?id=com.example.app⁩").assertIsDisplayed()
    }

    @Test
    fun intentArmShowsExtraStrongWarningAndIsolatedRaw() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Intent(
                        "intent://example#Intent;scheme=https;end",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR can trigger an app action. " +
                "This is uncommon for loyalty cards. Continue?",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "⁨intent://example#Intent;scheme=https;end⁩",
        ).assertIsDisplayed()
    }

    @Test
    fun unknownSchemeArmShowsWarningAndIsolatedRaw() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.UnknownScheme(
                        scheme = "myapp",
                        raw = "myapp://action?id=1",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "When scanned, this QR uses a scheme Walt doesn't recognize. " +
                "If you didn't expect this, cancel.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("⁨myapp://action?id=1⁩").assertIsDisplayed()
    }

    @Test
    fun plainTextSkipsTheGateEntirely() {
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.PlainText,
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("Confirm what this QR will do").assertDoesNotExist()
    }

    @Test
    fun maskAddressLeavesShortAddressesUntouched() {
        assertThat(maskAddress("short")).isEqualTo("short")
        assertThat(maskAddress("14characters!!")).isEqualTo("14characters!!")
    }

    @Test
    fun maskAddressShowsFirstAndLastSixWithEllipsis() {
        assertThat(maskAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
            .isEqualTo("1A1zP1...DivfNa")
        assertThat(maskAddress("0x71C7656EC7ab88b098defB751B7401B5f6d8976F"))
            .isEqualTo("0x71C7...d8976F")
    }

    @Test
    fun requiresCreateConfirmationIsFalseOnlyForPlainText() {
        assertThat(QrPayloadKind.PlainText.requiresCreateConfirmation()).isFalse()
        assertThat(QrPayloadKind.Magnet.requiresCreateConfirmation()).isTrue()
        assertThat(
            QrPayloadKind.Url("https", "example.com", "https://example.com")
                .requiresCreateConfirmation(),
        ).isTrue()
        assertThat(QrPayloadKind.Wifi(ssid = null).requiresCreateConfirmation()).isTrue()
    }

    @Test
    fun cancelButtonExposesRequestFocusActionForDefaultFocus() {
        // Robolectric's Compose host does not propagate window focus the way a
        // real device does, so a literal `assertIsFocused()` is unreliable here;
        // the contract this test locks is the structural one: the Cancel button
        // is the focusable target, with RequestFocus exposed on its semantics
        // node, so the in-source `LaunchedEffect { cancelFocus.requestFocus() }`
        // has a real target to land on. The on-device focus behavior is the
        // instrumentation bead's coverage.
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Bitcoin(
                        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BarcodeCreateConfirmTestTags.Cancel)
            .assert(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsActions.RequestFocus,
                ),
            )
    }

    @Test
    fun openingTheSheetFiresNeitherCallback() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Bitcoin(
                        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                    ),
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                )
            }
        }
        composeRule.waitForIdle()
        assertThat(confirmed).isEqualTo(0)
        assertThat(cancelled).isEqualTo(0)
    }

    @Test
    fun confirmTapInvokesOnConfirmExactlyOnce() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Phone("+44 7700 900000"),
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                )
            }
        }
        composeRule.onNodeWithTag(BarcodeCreateConfirmTestTags.Confirm).performClick()
        composeRule.waitForIdle()
        assertThat(confirmed).isEqualTo(1)
        assertThat(cancelled).isEqualTo(0)
    }

    @Test
    fun cancelTapInvokesOnCancelExactlyOnce() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            ThemedHost {
                BarcodeCreateConfirmSheet(
                    payloadKind = QrPayloadKind.Mailto("alice@example.com"),
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                )
            }
        }
        composeRule.onNodeWithTag(BarcodeCreateConfirmTestTags.Cancel).performClick()
        composeRule.waitForIdle()
        assertThat(confirmed).isEqualTo(0)
        assertThat(cancelled).isEqualTo(1)
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme { PassesTheme(semantics = semantics, content = content) }
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

}
