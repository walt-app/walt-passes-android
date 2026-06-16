package `is`.walt.passes.export

/**
 * Stable wire names for the [ExportableArtifact.exportKind] discriminator. Each constant
 * identifies one artifact family across all app versions and both platforms.
 *
 * An importer that encounters a kind string not listed here MUST preserve the envelope
 * verbatim rather than failing — unknown kinds represent future artifact types that
 * older builds have not seen yet.
 */
public object ArtifactKind {
    public const val PKPASS: String = "pkpass"
    public const val SCANNABLE_CARD: String = "scannable_card"
    public const val PDF_DOCUMENT: String = "pdf_document"
}
