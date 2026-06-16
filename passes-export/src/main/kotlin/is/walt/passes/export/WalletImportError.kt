package `is`.walt.passes.export

/**
 * Failure reasons returned inside the [Result] from [WalletImporter]. Modelled as a
 * sealed exception hierarchy rather than a plain sealed interface so they can propagate
 * through [runCatching] naturally and be matched via `exceptionOrNull() as?` at the
 * call site — no try/catch required.
 *
 *  - [AuthenticationFailed]: the GCM tag did not verify. This means either the wrong key
 *    was supplied or the file was tampered. The two cases are intentionally conflated:
 *    distinguishing them would help an attacker confirm brute-force hits.
 *  - [WrongKdf]: the file's KDF algorithm does not match the decrypt method called
 *    (e.g. calling [WalletImporter.decrypt] on a file that used PBKDF2). [expected] and
 *    [actual] are for developer logging only and MUST NOT be surfaced in user-facing UI.
 *  - [UnsupportedVersion]: the outer envelope's [WalletExportEnvelope.version] exceeds
 *    [ExportConstants.VERSION]. The importer stopped rather than silently misinterpreting
 *    a format it was not designed for.
 *  - [MalformedFile]: the outer JSON could not be parsed. Returned by [WalletImporter.parseFile];
 *    the inner [cause] is for logging.
 */
public sealed class WalletImportError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    public data object AuthenticationFailed :
        WalletImportError("GCM authentication tag did not verify — wrong key or tampered file")

    public data class WrongKdf(public val expected: String, public val actual: String) :
        WalletImportError("Expected KDF '$expected' but file uses '$actual'")

    public data class UnsupportedVersion(public val found: Int) :
        WalletImportError(
            "Export schema version $found is not supported " +
                "(this build supports up to ${ExportConstants.VERSION})",
        )

    public data class MalformedFile(override val cause: Throwable) :
        WalletImportError("Outer envelope JSON could not be parsed", cause)
}
