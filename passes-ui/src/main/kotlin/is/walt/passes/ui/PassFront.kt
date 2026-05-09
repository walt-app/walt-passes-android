package `is`.walt.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TextAlignment
import `is`.walt.passes.core.lookupOrSelf
import `is`.walt.passes.core.resolveLocalizedStrings
import `is`.walt.passes.ui.internal.LocalLocalizedStrings
import `is`.walt.passes.ui.internal.toBand
import `is`.walt.passes.ui.theme.LocalPassesSemantics
import `is`.walt.passes.ui.theme.toComposeColor

/**
 * Renders the front of a pass. Layout switches on [Pass.type]:
 *
 * - [PassType.BoardingPass]: prominent primary "from / to" row (typically two large
 *   primary fields), header above, secondary + auxiliary below, barcode at the foot.
 * - [PassType.EventTicket]: header, primary, secondary; barcode at the foot.
 * - [PassType.Coupon], [PassType.StoreCard], [PassType.Generic]: header / primary /
 *   secondary / auxiliary in a vertical stack.
 *
 * The pass's own [Pass.colors] override the host's MaterialTheme on the pass surface
 * only — the trust badge and expired overlay above this composable still read from
 * `LocalPassesSemantics`. See ADR 0003 D4.
 *
 * The signature trust badge and (when applicable) the expired overlay are composited
 * on top of the pass; neither has a suppression parameter — see ADR 0003 D5.
 *
 * @param locale Drives `pass.strings` substitution via [Pass.resolveLocalizedStrings].
 *   The default `PassLocale("en")` is a *fallback for tests and previews*; production
 *   callers (walt-android) MUST thread the device locale through, or the user sees
 *   English labels regardless of system language.
 */
@Composable
public fun PassFront(
    pass: Pass,
    signatureStatus: SignatureStatus,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val band = signatureStatus.toBand()
    val expired = remember(pass, nowEpochMillis) {
        ExpiredOverlayState.from(pass, nowEpochMillis)
    }
    // wpass-38y: PKPASS allows the front-side organizationName, field labels, and
    // field values to be pass.strings keys; substitute them through the resolved
    // strings table so localized labels render instead of raw `#KEY#` placeholders.
    //
    // The remember key is narrowed to the locales map (the only input
    // resolveLocalizedStrings consults) instead of the full Pass — Pass.equals walks
    // every image's contentEquals, which is megabytes of work on each recomposition
    // for a real PKPASS.
    val strings = remember(pass.locales, locale) { pass.resolveLocalizedStrings(locale) }
    val displayOrganizationName = strings.lookupOrSelf(pass.organizationName)
    androidx.compose.runtime.LaunchedEffect(pass, band) {
        telemetry.onPassRendered(pass.type, band)
    }

    val passBackground = pass.colors.background.toComposeOrDefault(MaterialTheme.colorScheme.surface)
    val passForeground = pass.colors.foreground.toComposeOrDefault(MaterialTheme.colorScheme.onSurface)
    val passLabel = pass.colors.label.toComposeOrDefault(passForeground.copy(alpha = 0.7f))

    CompositionLocalProvider(LocalLocalizedStrings provides strings) {
        Box(modifier = modifier) {
            PassFrontSurface(
                pass = pass,
                band = band,
                displayOrganizationName = displayOrganizationName,
                passBackground = passBackground,
                passForeground = passForeground,
                passLabel = passLabel,
            )
            if (expired !is ExpiredOverlayState.None) {
                ExpiredOverlay(state = expired, modifier = Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun PassFrontSurface(
    pass: Pass,
    band: SignatureBand,
    displayOrganizationName: String,
    passBackground: Color,
    passForeground: Color,
    passLabel: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = passBackground,
        contentColor = passForeground,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = displayOrganizationName,
                    style = MaterialTheme.typography.labelLarge,
                    color = passLabel,
                    modifier = Modifier.weight(1f),
                )
                SignatureTrustBadge(band = band)
            }

            when (pass.type) {
                PassType.BoardingPass -> BoardingPassBody(pass.frontFields, passForeground, passLabel)
                PassType.EventTicket -> EventTicketBody(pass.frontFields, passForeground, passLabel)
                PassType.Coupon, PassType.StoreCard, PassType.Generic ->
                    GenericBody(pass.frontFields, passForeground, passLabel)
            }

            pass.barcode?.let { barcode ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    BarcodeView(barcode = barcode)
                }
            }
        }
    }
}

@Composable
private fun BoardingPassBody(fields: PassFields, foreground: Color, labelColor: Color) {
    HeaderRow(fields.header, foreground, labelColor)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Two primary slots ("from" / "to") read at the largest size; if a single
        // primary is supplied it just takes the leading position.
        fields.primary.take(2).forEachIndexed { index, field ->
            PrimaryField(
                field,
                foreground,
                labelColor,
                modifier = Modifier.weight(1f),
                align = if (index == 1) TextAlign.End else TextAlign.Start,
            )
            if (index == 0 && fields.primary.size > 1) {
                Box(modifier = Modifier.width(8.dp))
            }
        }
    }
    SecondaryRow(fields.secondary, foreground, labelColor)
    if (fields.auxiliary.isNotEmpty()) {
        SecondaryRow(fields.auxiliary, foreground, labelColor)
    }
}

@Composable
private fun EventTicketBody(fields: PassFields, foreground: Color, labelColor: Color) {
    HeaderRow(fields.header, foreground, labelColor)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.primary.forEach { PrimaryField(it, foreground, labelColor) }
    }
    SecondaryRow(fields.secondary, foreground, labelColor)
}

@Composable
private fun GenericBody(fields: PassFields, foreground: Color, labelColor: Color) {
    HeaderRow(fields.header, foreground, labelColor)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.primary.forEach { PrimaryField(it, foreground, labelColor) }
    }
    SecondaryRow(fields.secondary, foreground, labelColor)
    if (fields.auxiliary.isNotEmpty()) {
        SecondaryRow(fields.auxiliary, foreground, labelColor)
    }
}

@Composable
private fun HeaderRow(fields: List<PassField>, foreground: Color, labelColor: Color) {
    if (fields.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        fields.forEach { field ->
            FieldCell(
                field = field,
                foreground = foreground,
                labelColor = labelColor,
                valueStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PrimaryField(
    field: PassField,
    foreground: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    FieldCell(
        field = field,
        foreground = foreground,
        labelColor = labelColor,
        valueStyle = MaterialTheme.typography.headlineSmall,
        modifier = modifier,
        textAlign = align,
    )
}

@Composable
private fun SecondaryRow(fields: List<PassField>, foreground: Color, labelColor: Color) {
    if (fields.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        fields.forEach { field ->
            FieldCell(
                field = field,
                foreground = foreground,
                labelColor = labelColor,
                valueStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FieldCell(
    field: PassField,
    foreground: Color,
    labelColor: Color,
    valueStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val align = textAlign ?: when (field.textAlignment) {
        TextAlignment.Left, TextAlignment.Natural -> TextAlign.Start
        TextAlignment.Center -> TextAlign.Center
        TextAlignment.Right -> TextAlign.End
    }
    // wpass-38y: substitute label and value through the pass.strings table provided
    // by the enclosing PassFront. Misses fall through to the raw text, so dynamic
    // values (codes, dates, numbers) emerge unchanged.
    val strings = LocalLocalizedStrings.current
    val displayLabel = strings.lookupOrSelf(field.label)
    val displayValue = strings.lookupOrSelf(field.value)
    Column(modifier = modifier) {
        displayLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = displayValue,
            style = valueStyle,
            color = foreground,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignatureTrustBadge(band: SignatureBand) {
    val semantics = LocalPassesSemantics.current.signatureBadge
    val (background, foreground, copy) = when (band) {
        SignatureBand.Untrusted ->
            Triple(semantics.unsignedBackground, semantics.unsignedForeground, "Unsigned")
        SignatureBand.SelfSigned ->
            Triple(semantics.selfSignedBackground, semantics.selfSignedForeground, "Self-signed")
        SignatureBand.AppleVerified ->
            Triple(semantics.appleVerifiedBackground, semantics.appleVerifiedForeground, "Verified")
        SignatureBand.Incomplete ->
            Triple(
                semantics.certChainIncompleteBackground,
                semantics.certChainIncompleteForeground,
                "Signature unknown",
            )
    }
    Text(
        text = copy,
        style = MaterialTheme.typography.labelSmall,
        color = foreground.toComposeColor(),
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(background.toComposeColor())
            .padding(PaddingValues(horizontal = 12.dp, vertical = 4.dp)),
    )
}

private fun ColorValue?.toComposeOrDefault(default: Color): Color {
    val v = this ?: return default
    // Mask via Long to avoid sign-extension surprises if the stored Int ever has a
    // bit set above the documented 24-bit range. The pass-core parser contract is
    // 24-bit RGB, but the type itself is `Int`, so this is belt-and-suspenders.
    val packed = v.rgb.toLong() and 0xFFFFFFL
    val r = ((packed shr 16) and 0xFF).toInt()
    val g = ((packed shr 8) and 0xFF).toInt()
    val b = (packed and 0xFF).toInt()
    return Color(red = r, green = g, blue = b)
}
