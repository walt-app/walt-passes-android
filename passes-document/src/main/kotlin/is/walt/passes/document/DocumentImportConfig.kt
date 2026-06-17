package `is`.walt.passes.document

/**
 * Configuration for [DocumentImporter]. Bundles the shared sniff-stage byte cap, the PDF
 * backend's existing [PdfImportConfig] (which carries the PDF caps and PDF telemetry guard),
 * the image arm's telemetry guard, and the bound the image arm requests from the decode
 * sandbox.
 *
 * [maxBytes] caps the bounded read the importer performs once, before sniffing — so a
 * MIME-spoofed multi-gigabyte file is refused before either backend is touched. It defaults to
 * the same 25 MB ceiling the PDF path uses ([PdfImportConfig.DEFAULT_MAX_BYTES]); the per-kind
 * caps in [pdfConfig] / the `passes-image` sandbox still apply on top.
 *
 * [maxImageDecodePx] is the per-side bound the image arm asks the sandbox to decode within
 * (aspect-preserving, never upscaled, and additionally capped by the sandbox's own 4 MP
 * output ceiling). The returned bounded raster is what becomes the [ImageDocument][`is`.walt
 * .passes.document.ImageDocument] dimensions and the persisted display thumbnail.
 */
public data class DocumentImportConfig(
    public val maxBytes: Long = PdfImportConfig.DEFAULT_MAX_BYTES,
    public val pdfConfig: PdfImportConfig = PdfImportConfig(),
    public val imageTelemetryGuard: ImageImportTelemetryGuard = ImageImportTelemetryGuard.NoOp,
    public val maxImageDecodePx: Int = DEFAULT_MAX_IMAGE_DECODE_PX,
) {
    public companion object {
        public const val DEFAULT_MAX_IMAGE_DECODE_PX: Int = 2048
    }
}
