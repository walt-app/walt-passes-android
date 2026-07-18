package `is`.walt.passes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `is`.walt.passes.core.ScannableFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose smoke tests for [CompactCodeView] (wpass-tjc.2). Locks
 * the two behavioral contracts that matter at list scale:
 *
 *  1. A valid `(payload, format)` renders an image node carrying the caller-supplied
 *     [contentDescription] — the card face's dominant visual stays TalkBack-reachable.
 *  2. An encoder rejection renders the same-sized placeholder with the literal
 *     "Barcode failed to render" description (shared wording with [ScannableCardView])
 *     instead of throwing — a malformed payload cannot crash the wallet list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompactCodeViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validQrPayloadRendersImageWithCallerDescription() {
        composeRule.setContent {
            CompactCodeView(
                payload = "LOCKER-0042",
                format = ScannableFormat.Qr,
                modifier = Modifier.size(90.dp),
                contentDescription = "Locker code, QR code",
            )
        }
        composeRule.onNodeWithContentDescription("Locker code, QR code").assertIsDisplayed()
    }

    @Test
    fun validCode128PayloadRendersImageWithCallerDescription() {
        composeRule.setContent {
            CompactCodeView(
                payload = "21000456782",
                format = ScannableFormat.Code128,
                modifier = Modifier.size(width = 280.dp, height = 56.dp),
                contentDescription = "Library card, Code 128",
            )
        }
        composeRule.onNodeWithContentDescription("Library card, Code 128").assertIsDisplayed()
    }

    @Test
    fun encoderRejectionRendersFailurePlaceholderNotCrash() {
        composeRule.setContent {
            // EAN-13 requires 12-13 digits; "12" is rejected by the writer, exercising
            // the EncodeResult.Failure arm.
            CompactCodeView(
                payload = "12",
                format = ScannableFormat.Ean13,
                modifier = Modifier.size(width = 280.dp, height = 56.dp),
            )
        }
        composeRule.onNodeWithContentDescription("Barcode failed to render").assertIsDisplayed()
    }
}
