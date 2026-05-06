package `is`.walt.passes.core.internal

import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParseFailedEvent
import `is`.walt.passes.core.ParseResult
import `is`.walt.passes.core.ParseSucceededEvent
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassParser
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.ResourceLimit
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import `is`.walt.passes.core.UnsupportedReason
import `is`.walt.passes.core.toFailureKind
import `is`.walt.passes.core.toKind

/**
 * The single concrete [PassParser] implementation. Stitches the four security-critical
 * slices ([extractSafely], [verifyManifest], [verifySignature], [decodePassJson]) plus
 * [parseStrings] and [readPngDimensions] into a non-throwing pipeline whose every error
 * path lands on a [ParseResult] arm.
 *
 * **Pipeline order is load-bearing.** The shape mirrors the trust hierarchy the public
 * surface partitions along: structural validity (extract) → integrity binding (manifest
 * hash chain) → cryptographic provenance (signature) → semantic content (pass.json,
 * strings, images). Reordering any earlier step past a later one would let a structural
 * attack surface as a tampering / unsupported reason or vice versa, which in turn would
 * mis-route the trust UI.
 *
 * **Concurrency.** The class is intentionally stateless beyond the immutable
 * [ParserConfig]. Every helper takes the work-in-progress entries / bytes as parameters;
 * nothing is cached on the instance. A single [DefaultPassParser] is therefore safe to
 * call from any number of threads concurrently.
 *
 * **Telemetry.** [System.nanoTime] brackets the entire [parse] call; the duration and
 * the type-flattened [ParseFailureKind] / [SignatureStatusKind] flow through to the
 * configured [TelemetryGuard]. Pass content (serial, organization name, field values,
 * barcode message) never enters the event payload — that constraint is enforced
 * structurally by [TelemetryGuard]'s event types, not by discipline here.
 */
internal class DefaultPassParser(private val config: ParserConfig) : PassParser {
    override fun parse(source: PassSource): ParseResult {
        val started = System.nanoTime()
        config.telemetryGuard.onParseStarted()
        val archiveBytes = sourceArchiveBytesOrZero(source)
        val result = pipeline(source)
        emit(result, started, archiveBytes)
        return result
    }

    /**
     * Sequenced as a chain of helpers (rather than a single body with seven `return`
     * statements) so each stage's failure routing is local to its own helper. The
     * chain order — extract → manifest → signature → pass.json → strings → images →
     * assemble — is the trust hierarchy in code: a structural failure can never
     * surface as tampering and a tampering signal can never be coalesced with
     * malformedness.
     */
    private fun pipeline(source: PassSource): ParseResult =
        when (val r = extractSafely(source, config)) {
            is ExtractResult.Failure -> ParseResult.Malformed(r.reason)
            is ExtractResult.Success -> withEntries(r.entries)
        }

    private fun withEntries(entries: Map<String, ByteArray>): ParseResult =
        when (val r = verifyManifest(entries)) {
            is ManifestVerifyResult.Failed -> manifestFailureToResult(r.failure)
            is ManifestVerifyResult.Ok -> withVerifiedManifest(entries, r.manifestBytes)
        }

    private fun withVerifiedManifest(
        entries: Map<String, ByteArray>,
        manifestBytes: ByteArray,
    ): ParseResult =
        when (val r = resolveSignature(entries, manifestBytes)) {
            is SignaturePhaseResult.Failure -> r.parseResult
            is SignaturePhaseResult.Status -> withSignatureStatus(entries, r.status)
        }

    private fun withSignatureStatus(
        entries: Map<String, ByteArray>,
        status: SignatureStatus,
    ): ParseResult =
        when (val r = decodePassJson(entries, config)) {
            is PassJsonDecodeResult.Failed -> passJsonFailureToResult(r.failure)
            is PassJsonDecodeResult.Ok -> assemble(entries, r.pass, status)
        }

    private fun assemble(
        entries: Map<String, ByteArray>,
        pass: Pass,
        status: SignatureStatus,
    ): ParseResult =
        when (val locales = collectLocales(entries)) {
            is LocaleCollect.Failure -> locales.parseResult
            is LocaleCollect.Ok ->
                when (val images = collectImages(entries)) {
                    is ImageCollect.Failure -> images.parseResult
                    is ImageCollect.Ok ->
                        ParseResult.Success(
                            pass = pass.copy(images = images.map, locales = locales.map),
                            signatureStatus = status,
                        )
                }
        }

    private fun resolveSignature(
        entries: Map<String, ByteArray>,
        manifestBytes: ByteArray,
    ): SignaturePhaseResult {
        val signatureBytes = entries[SIGNATURE_FILE_NAME]
        if (signatureBytes == null) {
            // No signature blob. The lenient default surfaces this as Unsigned; strict
            // mode treats absence as a security event. Routing through
            // SignatureCryptoFailure (rather than coining a new "Unsigned" tamper arm)
            // matches decision-wlt-0tn-q1: strict refuses unsigned input *because* it
            // refuses to trust unsigned cryptographic provenance, which is the same
            // category of refusal as "the bytes did not parse as a CMS envelope."
            return if (config.acceptUnsignedArchives) {
                SignaturePhaseResult.Status(SignatureStatus.Unsigned)
            } else {
                SignaturePhaseResult.Failure(
                    ParseResult.Tampered(TamperReason.SignatureCryptoFailure),
                )
            }
        }
        return when (val r = verifySignature(signatureBytes, manifestBytes, config)) {
            is SignatureVerifyResult.Ok -> SignaturePhaseResult.Status(r.status)
            is SignatureVerifyResult.Failed -> SignaturePhaseResult.Failure(ParseResult.Tampered(r.reason))
        }
    }

    private fun collectLocales(entries: Map<String, ByteArray>): LocaleCollect {
        // Two-pass over the entries: first identify the locale-bearing names so the cap
        // can fire before any .strings parsing happens, then parse. Doing the cap check
        // up-front avoids parsing N strings files just to reject N+1.
        val stringsEntries =
            entries.entries
                .mapNotNull { (name, bytes) ->
                    val locale = lprojStringsLocaleOrNull(name) ?: return@mapNotNull null
                    locale to bytes
                }
        return if (stringsEntries.size > config.maxLocaleCount) {
            LocaleCollect.Failure(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.LocaleCount)),
            )
        } else {
            parseStringsEntries(stringsEntries)
        }
    }

    private fun parseStringsEntries(stringsEntries: List<Pair<String, ByteArray>>): LocaleCollect {
        val map = LinkedHashMap<PassLocale, LocalizedStrings>(stringsEntries.size)
        var failure: ParseResult? = null
        for ((locale, bytes) in stringsEntries) {
            if (failure != null) break
            when (val r = parseStrings(bytes, config)) {
                is StringsResult.Ok -> map[PassLocale(locale)] = r.strings
                is StringsResult.Failed -> failure = stringsFailureToResult(r.failure)
            }
        }
        return failure?.let(LocaleCollect::Failure) ?: LocaleCollect.Ok(map)
    }

    private fun collectImages(entries: Map<String, ByteArray>): ImageCollect {
        // Pre-filter to top-level role images so the loop body has a single jump
        // statement (the break-on-failure). Localized images (under `<locale>.lproj/`)
        // are silently dropped — ADR 0001 does not yet model locale-aware image
        // binding, and the renderer consumes only the top-level role images today.
        val candidates =
            entries.entries.mapNotNull { (name, bytes) ->
                val role = topLevelImageRole(name) ?: return@mapNotNull null
                role to bytes
            }
        val map = LinkedHashMap<ImageRole, ImageBytes>(candidates.size)
        var failure: ParseResult? = null
        for ((role, bytes) in candidates) {
            if (failure != null) break
            val limitTrip = pngPixelLimitFailure(bytes)
            if (limitTrip != null) failure = limitTrip else map[role] = ImageBytes(bytes)
        }
        return failure?.let(ImageCollect::Failure) ?: ImageCollect.Ok(map)
    }

    private fun topLevelImageRole(name: String): ImageRole? {
        val isTopLevelPng = '/' !in name && name.endsWith(PNG_EXTENSION, ignoreCase = true)
        return if (isTopLevelPng) imageRoleForBasename(name) else null
    }

    private fun pngPixelLimitFailure(bytes: ByteArray): ParseResult? {
        // A PNG whose IHDR is unreadable is treated as "skip the pixel cap" rather
        // than malformed: the bytes are inert here (the renderer will surface a decode
        // failure downstream), and per-entry size is already bounded by maxEntryBytes
        // upstream. Rejecting on unreadable IHDR would force the parser to police PNG
        // structural validity, which is outside its remit.
        val dim = readPngDimensions(bytes) ?: return null
        val pixels = dim.width * dim.height
        return if (pixels > config.maxImagePixelCount.toLong()) {
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.ImagePixelCount))
        } else {
            null
        }
    }

    private fun emit(
        result: ParseResult,
        startedNanos: Long,
        archiveBytes: Long,
    ) {
        val durationMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        when (result) {
            is ParseResult.Success ->
                config.telemetryGuard.onParseSucceeded(
                    ParseSucceededEvent(
                        passType = result.pass.type,
                        signatureStatus = result.signatureStatus.toKind(),
                        archiveBytes = archiveBytes,
                        durationMillis = durationMillis,
                        imageCount = result.pass.images.size,
                        localeCount = result.pass.locales.size,
                    ),
                )
            else ->
                config.telemetryGuard.onParseFailed(
                    ParseFailedEvent(
                        // toFailureKind() returns null only for Success, which the
                        // outer `when` already routed; the !! is a category check, not
                        // a runtime risk. A future ParseResult arm that breaks this
                        // surfaces as an immediate test failure here, not a silent
                        // observability hole.
                        outcome = result.toFailureKind()!!,
                        durationMillis = durationMillis,
                    ),
                )
        }
    }

    private sealed interface SignaturePhaseResult {
        data class Status(val status: SignatureStatus) : SignaturePhaseResult

        data class Failure(val parseResult: ParseResult) : SignaturePhaseResult
    }

    private sealed interface LocaleCollect {
        data class Ok(val map: Map<PassLocale, LocalizedStrings>) : LocaleCollect

        data class Failure(val parseResult: ParseResult) : LocaleCollect
    }

    private sealed interface ImageCollect {
        data class Ok(val map: Map<ImageRole, ImageBytes>) : ImageCollect

        data class Failure(val parseResult: ParseResult) : ImageCollect
    }
}

/**
 * Maps a top-level PKPASS image basename onto its [ImageRole]. Unknown basenames return
 * `null` so the caller can drop them silently — PKPASS allows extra files alongside the
 * known roles, and the trust claim does not ride on rejecting them. The map is
 * case-insensitive on the basename only; the extractor's allowlist already restricted
 * names to `*.png`.
 */
private fun imageRoleForBasename(name: String): ImageRole? = ROLE_BY_BASENAME[name.lowercase()]

/**
 * The hash-mismatch arm is the only manifest failure that constitutes tampering: the
 * archive is structurally valid, but a file's bytes differ from what the manifest
 * declared. Every other arm is structural malformedness collapsed onto
 * [MalformedReason.MissingManifest] / [MalformedReason.InvalidManifest] until
 * `wpass-n6g` lands dedicated arms for each shape.
 */
private fun manifestFailureToResult(failure: ManifestFailure): ParseResult =
    when (failure) {
        is ManifestFailure.Missing -> ParseResult.Malformed(MalformedReason.MissingManifest)
        is ManifestFailure.HashMismatch -> ParseResult.Tampered(TamperReason.FileHashMismatch)
        is ManifestFailure.InvalidJson,
        is ManifestFailure.InvalidShape,
        is ManifestFailure.InvalidHashFormat,
        is ManifestFailure.SelfReferentialEntry,
        is ManifestFailure.ExtraEntry,
        is ManifestFailure.MissingEntry,
        -> ParseResult.Malformed(MalformedReason.InvalidManifest)
    }

private fun passJsonFailureToResult(failure: PassJsonFailure): ParseResult =
    when (failure) {
        is PassJsonFailure.Missing -> ParseResult.Malformed(MalformedReason.MissingPassJson)
        is PassJsonFailure.InvalidJson -> ParseResult.Malformed(MalformedReason.InvalidPassJson)
        is PassJsonFailure.InvalidShape -> ParseResult.Malformed(MalformedReason.InvalidPassJson)
        is PassJsonFailure.JsonDepthExceeded ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonDepth))
        is PassJsonFailure.JsonStringTooLong ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize))
        is PassJsonFailure.UnknownFormatVersion ->
            ParseResult.Unsupported(UnsupportedReason.FormatVersion(failure.version))
        is PassJsonFailure.UnknownPassStyle ->
            ParseResult.Unsupported(UnsupportedReason.UnknownPassStyle(failure.raw))
    }

private fun stringsFailureToResult(failure: StringsFailure): ParseResult =
    when (failure) {
        is StringsFailure.ValueTooLong ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize))
        is StringsFailure.InvalidEncoding,
        is StringsFailure.UnterminatedString,
        is StringsFailure.UnterminatedComment,
        is StringsFailure.BadStructure,
        is StringsFailure.BadEscape,
        -> ParseResult.Malformed(MalformedReason.InvalidStrings)
    }

private fun lprojStringsLocaleOrNull(name: String): String? {
    // A valid PKPASS keeps locale directories at the archive root, so the locale
    // segment must be non-empty and slash-free. Path traversal is already rejected
    // upstream; this guard is the structural-shape check for a top-level lproj.
    if (!name.endsWith(LPROJ_STRINGS_SUFFIX)) return null
    val locale = name.substring(0, name.length - LPROJ_STRINGS_SUFFIX.length)
    return locale.takeUnless { it.isEmpty() || '/' in it }
}

private fun sourceArchiveBytesOrZero(source: PassSource): Long =
    when (source) {
        is PassSource.Bytes -> source.bytes.size.toLong()
        is PassSource.Stream -> source.sizeHintBytes ?: 0L
    }

private val ROLE_BY_BASENAME: Map<String, ImageRole> =
    mapOf(
        "logo.png" to ImageRole.Logo,
        "logo@2x.png" to ImageRole.LogoRetina,
        "logo@3x.png" to ImageRole.LogoSuperRetina,
        "icon.png" to ImageRole.Icon,
        "icon@2x.png" to ImageRole.IconRetina,
        "icon@3x.png" to ImageRole.IconSuperRetina,
        "strip.png" to ImageRole.Strip,
        "strip@2x.png" to ImageRole.StripRetina,
        "strip@3x.png" to ImageRole.StripSuperRetina,
        "background.png" to ImageRole.Background,
        "background@2x.png" to ImageRole.BackgroundRetina,
        "background@3x.png" to ImageRole.BackgroundSuperRetina,
        "thumbnail.png" to ImageRole.Thumbnail,
        "thumbnail@2x.png" to ImageRole.ThumbnailRetina,
        "thumbnail@3x.png" to ImageRole.ThumbnailSuperRetina,
        "footer.png" to ImageRole.Footer,
        "footer@2x.png" to ImageRole.FooterRetina,
        "footer@3x.png" to ImageRole.FooterSuperRetina,
    )

private const val SIGNATURE_FILE_NAME = "signature"
private const val PNG_EXTENSION = ".png"
private const val LPROJ_STRINGS_SUFFIX = ".lproj/pass.strings"
private const val NANOS_PER_MILLI = 1_000_000L
