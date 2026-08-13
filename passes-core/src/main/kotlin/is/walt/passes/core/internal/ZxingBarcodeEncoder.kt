package `is`.walt.passes.core.internal

import com.google.zxing.EncodeHintType
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.Code39Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.UPCAWriter
import com.google.zxing.pdf417.PDF417Writer
import com.google.zxing.pdf417.encoder.Compaction
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.EncoderFailureReason
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.core.ScannableFormatConstraints
import java.util.concurrent.CancellationException
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * ZXing-backed implementation of the kernel's barcode encoder. Per-format writers
 * (`Code128Writer`, `EAN13Writer`, `UPCAWriter`, `Code39Writer`, `QRCodeWriter`) are used
 * instead of `MultiFormatWriter` so the roster of [ScannableFormat] is enforced at the
 * dispatch site: adding a format here is the only path that lets a new symbology reach the
 * encoder. The intermediate `MultiFormatWriter` would silently accept anything ZXing
 * supports, including formats the validator has not been taught to gate.
 *
 * Every roster member encodes. A decode-only addition has no refusal path to opt into:
 * [writeMatrix]'s `when` is compiler-exhaustive, so a new [ScannableFormat] member breaks the
 * build here and forces the writer (or a deliberate refusal) to be a decision someone makes
 * rather than one `MultiFormatWriter` makes for them.
 *
 * Hidden behind [BarcodeEncoder]; this object is package-internal so consumers cannot
 * reach for ZXing types directly.
 *
 * **Quiet zone.** Most ZXing writers emit their own quiet zone (margin) at default settings
 * and the kernel leaves it alone — the render layer (passes-ui) controls visual padding.
 * `PDF417Writer` is the one exception, see the margin note below.
 *
 * **QR error correction.** Fixed at [ErrorCorrectionLevel.M] (~15% redundancy). High
 * enough to survive moderate scratch/damage on a phone screen but low enough that long
 * payloads still fit. v1 does not expose a tuning knob; if scanner reliability demands it
 * later, surface a parameter on [BarcodeEncoder.encode] without changing the default.
 *
 * **Aztec error correction.** Fixed at [AZTEC_ERROR_CORRECTION_PERCENT] (33%), which is both
 * ZXing's default and the Aztec spec's recommended level. Measured against ZXing 3.5.4: a
 * 132-character BCBP boarding pass yields the same 37x37 matrix at every level from 23% to
 * 50%, so the redundancy is free at the payload size this roster addition exists to serve.
 * Capacity at 33% is ~3,000 ASCII characters, twice the validator's 1,500-character cap.
 *
 * **PDF417 error correction, compaction and margin.** Error correction is pinned at
 * [PDF417_ERROR_CORRECTION_LEVEL] (3) rather than ZXing's hardcoded default of 2:
 * ISO/IEC 15438 recommends level 3 for 41-160 data codewords, which is where a BCBP
 * boarding pass lands. Measured cost at that length is one extra row and no extra column
 * (209x48 to 209x52), so module width at a fixed render width is unchanged. Capacity at
 * level 3 is ~1,766 ASCII characters, well above the validator's 800-character cap.
 *
 * Compaction is left [Compaction.AUTO]: it emits a symbol no larger than forced BYTE
 * compaction, so forcing a mode only costs area.
 *
 * Margin is pinned to [PDF417_QUIET_ZONE_MODULES] (2, the spec quiet zone) because
 * `PDF417Writer` otherwise pads 30 modules on all four sides. On a 44-module-tall symbol
 * that more than doubles the height and drags the aspect ratio from 4.66 to 2.55 — dead
 * space the render layer then letterboxes, on top of the quiet zone it already applies
 * itself. This is the one place the kernel overrides a writer's own margin.
 *
 * **Character set.** Both writers default to ISO-8859-1, and the validator admits any visible
 * character for the byte-capable formats, so the default loses payloads a user can legitimately
 * type. The two symbologies fail differently and both are addressed: `PDF417Writer` throws
 * outright on a non-Latin-1 character, fixed by `PDF417_AUTO_ECI`, which emits an ECI header
 * only when one is actually needed; `AztecWriter` is the worse case — it encodes happily and
 * decodes back transliterated ("東京" returns as "??"), a silent corruption fixed by pinning
 * CHARACTER_SET. Measured: both fixes leave the all-ASCII case byte-identical in matrix size
 * and capacity, so they cost nothing at boarding-pass payloads.
 *
 * QR has the same silent-transliteration defect and is NOT fixed here — it predates this bead
 * and changing the emitted symbol for existing QR cards needs its own scanner verification.
 * Tracked as wpass-qj6.
 */
internal object ZxingBarcodeEncoder {
    // Per-writer hint maps. Each writer reads ERROR_CORRECTION as a different type —
    // ErrorCorrectionLevel for QR, an Int percentage for Aztec, an Int level for PDF417 —
    // so the maps cannot be merged. See the class KDoc for how each value was chosen.
    private val qrHints: Map<EncodeHintType, Any> =
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)

    private val aztecHints: Map<EncodeHintType, Any> =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to AZTEC_ERROR_CORRECTION_PERCENT,
            EncodeHintType.CHARACTER_SET to UTF_8,
        )

    private val pdf417Hints: Map<EncodeHintType, Any> =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to PDF417_ERROR_CORRECTION_LEVEL,
            EncodeHintType.PDF417_COMPACTION to Compaction.AUTO,
            EncodeHintType.MARGIN to PDF417_QUIET_ZONE_MODULES,
            EncodeHintType.PDF417_AUTO_ECI to true,
        )

    fun encode(
        payload: String,
        format: ScannableFormat,
    ): EncodeResult {
        refuseBeforeWriter(payload, format)?.let { return EncodeResult.Failure(it) }
        // runCatching absorbs anything ZXing throws — including the plain
        // NullPointerException / ArrayIndexOutOfBoundsException its hand-rolled writers do
        // raise on some edge inputs — and funnels it into EncodeResult.Failure to honor the
        // kernel's no-throw contract. Matches the pattern in SignatureVerifier (the other
        // place the kernel wraps third-party code that escapes its declared exception set).
        // CancellationException is re-thrown so that coroutine cancellation propagates if a
        // future consumer ever wraps this in withTimeout { ... }; runCatching would
        // otherwise absorb it indistinguishably from a WriterException. Uses the JDK type
        // (Kotlin's CancellationException extends it) so passes-core stays coroutines-free.
        return runCatching { writeMatrix(payload, format) }
            .fold(
                onSuccess = { EncodeResult.Success(it) },
                onFailure = {
                    if (it is CancellationException) throw it
                    EncodeResult.Failure(translateFailure(format, payload, it))
                },
            )
    }

    /**
     * The refusals that are decided without running a writer, or null to proceed.
     *
     * An empty payload is refused for every format. The validator rejects it upstream, so this
     * is defense in depth — but it cannot be left to the writers: five of the six throw on an
     * empty input and `AztecWriter` does not once a CHARACTER_SET is pinned, happily emitting a
     * 15x15 symbol that encodes nothing. Uniform refusal here beats six incidental behaviors
     * that a hint change can flip.
     *
     * PDF417 refuses supplementary-plane characters (emoji, historic scripts — anything outside
     * the BMP, which Kotlin holds as a surrogate pair). ZXing cannot encode one in PDF417 under
     * any configuration tried: with `PDF417_AUTO_ECI` it raises an `IllegalStateException` whose
     * message embeds THE ENTIRE PAYLOAD, and without it a `WriterException` naming one half of
     * the pair as an unpaired code unit. The validator admits these characters (they are visible
     * and not Cf/Cc) and Aztec encodes them fine, so this is a PDF417-specific writer limit that
     * has to be named as one rather than surfacing as an unattributable failure. Catching it
     * before the writer runs is also what keeps the payload out of [EncoderFailureReason.
     * WriterRejected.detail].
     *
     * The QR check is a proactive [EncoderFailureReason.PayloadTooDense], gated by the alphanumeric-mode
     * membership test: a payload that fits QR's numeric or alphanumeric mode has a much larger
     * capacity than byte mode (~5,596 digits or ~3,391 alphanumeric chars at v40-M vs 2,331
     * bytes), and ZXing's QRCodeWriter auto-selects the densest fitting mode, so pre-rejecting
     * such payloads against the byte-mode ceiling would over-reject. Anything outside the
     * alphanumeric set must use byte mode, where the byte-count check applies — UTF-8 because
     * byte-mode capacity is in bytes (a payload of "é" × 1500 is 3000 bytes). The "Data too
     * big" message match in [translateFailure] stays as belt-and-suspenders for dense
     * alphanumeric/numeric payloads that still overflow at v40.
     */
    private fun refuseBeforeWriter(
        payload: String,
        format: ScannableFormat,
    ): EncoderFailureReason? =
        when {
            payload.isEmpty() ->
                EncoderFailureReason.WriterRejected(format, EMPTY_PAYLOAD_MESSAGE)
            format == ScannableFormat.Pdf417 && payload.any { it.isSurrogate() } ->
                EncoderFailureReason.WriterRejected(format, NO_SUPPLEMENTARY_CHARS_MESSAGE)
            format == ScannableFormat.Qr &&
                payload.any { !ScannableFormatConstraints.isQrAlphanumericChar(it) } &&
                payload.toByteArray(Charsets.UTF_8).size >
                ScannableFormatConstraints.QR_BYTE_CEILING_ECC_M_BYTE_MODE ->
                EncoderFailureReason.PayloadTooDense
            else -> null
        }

    private fun writeMatrix(
        payload: String,
        format: ScannableFormat,
    ): BarcodeMatrix {
        // Width/height of 0 tells each writer to use its symbology's natural minimum.
        // The renderer scales the resulting matrix at draw time; encoding at the natural
        // size avoids ZXing's resampling step and keeps every module at integer width.
        val bitMatrix =
            when (format) {
                ScannableFormat.Code128 -> Code128Writer().encode(payload, ZxingFormat.CODE_128, 0, 0)
                ScannableFormat.Code39 -> Code39Writer().encode(payload, ZxingFormat.CODE_39, 0, 0)
                ScannableFormat.Ean13 -> EAN13Writer().encode(payload, ZxingFormat.EAN_13, 0, 0)
                ScannableFormat.UpcA -> UPCAWriter().encode(payload, ZxingFormat.UPC_A, 0, 0)
                ScannableFormat.Qr -> QRCodeWriter().encode(payload, ZxingFormat.QR_CODE, 0, 0, qrHints)
                ScannableFormat.Pdf417 ->
                    PDF417Writer().encode(payload, ZxingFormat.PDF_417, 0, 0, pdf417Hints)
                ScannableFormat.Aztec ->
                    AztecWriter().encode(payload, ZxingFormat.AZTEC, 0, 0, aztecHints)
            }
        return bitMatrix.toBarcodeMatrix()
    }

    private fun translateFailure(
        format: ScannableFormat,
        payload: String,
        cause: Throwable,
    ): EncoderFailureReason {
        // Belt-and-suspenders: the proactive byte-length check above handles the common QR
        // overflow path; this string match catches the same condition when ZXing surfaces it
        // for a payload that slipped under the byte ceiling (e.g. some mixed-mode inputs).
        // The match is intentionally lossy — if ZXing rewords the message on a future bump,
        // the encoder still surfaces WriterRejected and the consumer still gets a usable
        // error path, just without the PayloadTooDense-specific UI hint.
        val message = cause.message.orEmpty()
        if (OVER_CAPACITY_MESSAGES.getValue(format).any { it in message }) {
            return EncoderFailureReason.PayloadTooDense
        }
        return EncoderFailureReason.WriterRejected(format, message.withoutPayload(payload))
    }

    private fun BitMatrix.toBarcodeMatrix(): BarcodeMatrix {
        val flat = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flat[y * width + x] = this[x, y]
            }
        }
        return BarcodeMatrix(width, height, flat)
    }

    /**
     * Strips [payload] out of a third-party exception message. `PDF417Writer` interpolates the
     * whole input into its "Failed to encode" text, and [EncoderFailureReason.WriterRejected.
     * detail] is the one third-party string that crosses the kernel boundary — its own KDoc
     * warns consumers not to ship it verbatim. Removing the payload here means that warning no
     * longer has to hold back the user's card number.
     */
    private fun String.withoutPayload(payload: String): String =
        if (payload.isNotEmpty() && payload in this) replace(payload, REDACTED) else this

    // Per-format substrings that mean "this payload does not fit", lifted to the dedicated
    // PayloadTooDense arm so the consumer can say "shorten it" rather than "try another
    // format". The matches are intentionally lossy: if ZXing rewords one on a version bump the
    // encoder still reports WriterRejected, just without the specific UI hint.
    //
    // The three 2D formats need this because their length caps are in CHARACTERS while their
    // capacity is in bytes, so a multibyte payload can clear the validator and still overflow
    // (see ScannableFormatConstraints' cap KDoc). The 1D formats have no over-capacity message
    // of their own — their fixed or short caps are reached long before any density limit.
    private val OVER_CAPACITY_MESSAGES: Map<ScannableFormat, List<String>> =
        mapOf(
            ScannableFormat.Qr to listOf("Data too big"),
            ScannableFormat.Aztec to listOf("Data too large for an Aztec code"),
            ScannableFormat.Pdf417 to
                listOf(
                    "Unable to fit message in columns",
                    "Encoded message contains too many code words",
                ),
            ScannableFormat.Code128 to emptyList(),
            ScannableFormat.Code39 to emptyList(),
            ScannableFormat.Ean13 to emptyList(),
            ScannableFormat.UpcA to emptyList(),
        )

    // The pins the class KDoc argues for. ScannableFormatConstraints' PDF417 / Aztec caps were
    // derived against these values; changing one means re-deriving the other.
    private const val AZTEC_ERROR_CORRECTION_PERCENT = 33
    private const val PDF417_ERROR_CORRECTION_LEVEL = 3
    private const val PDF417_QUIET_ZONE_MODULES = 2
    private const val UTF_8 = "UTF-8"

    // Walt's own wording, not ZXing messages — these refusals never reach a writer.
    private const val EMPTY_PAYLOAD_MESSAGE = "Empty payload"
    private const val NO_SUPPLEMENTARY_CHARS_MESSAGE =
        "PDF417 cannot encode supplementary-plane characters"
    private const val REDACTED = "<payload>"
}
