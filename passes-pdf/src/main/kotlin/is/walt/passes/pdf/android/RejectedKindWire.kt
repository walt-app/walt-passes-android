package `is`.walt.passes.pdf.android

import `is`.walt.passes.pdf.DocumentRejectedKind

/**
 * Stable Int <-> [DocumentRejectedKind] mapping for the binder wire format.
 *
 * The previous wire encoding used [DocumentRejectedKind.ordinal] directly; that left
 * the wire silently coupled to the source-order of the enum. Reordering or inserting an
 * arm in `passes-pdf-core` would have shifted every subsequent code on the wire and
 * mis-decoded rejections without a compile error. Today the renderer service and its
 * client live in the same process from the same build, so the on-the-wire fragility is
 * latent rather than active, but the kernel-vs-consumer coupling already requires the
 * mapping to be explicit: when walt-android (closed source) starts wiring the binder in,
 * a contributor reordering the enum in this repository must not silently break decoding
 * downstream.
 *
 * Add a new arm: extend [DocumentRejectedKind] in passes-pdf-core, append a new code
 * here, and update [encode] / [decode]. [RejectedKindWireSurfaceTest] fails closed if
 * the mapping table drifts from the enum; that gates the change behind a structural
 * test rather than a code-review judgement call.
 */
internal object RejectedKindWire {
    const val OVERSIZED_AT_IMPORT: Int = 0
    const val NOT_A_PDF: Int = 1
    const val ENCRYPTED: Int = 2
    const val TOO_MANY_PAGES: Int = 3
    const val RENDERER_FAILED: Int = 4
    const val UNSUPPORTED_ANDROID_VERSION: Int = 5

    fun encode(kind: DocumentRejectedKind): Int =
        when (kind) {
            DocumentRejectedKind.OversizedAtImport -> OVERSIZED_AT_IMPORT
            DocumentRejectedKind.NotAPdf -> NOT_A_PDF
            DocumentRejectedKind.Encrypted -> ENCRYPTED
            DocumentRejectedKind.TooManyPages -> TOO_MANY_PAGES
            DocumentRejectedKind.RendererFailed -> RENDERER_FAILED
            DocumentRejectedKind.UnsupportedAndroidVersion -> UNSUPPORTED_ANDROID_VERSION
        }

    fun decode(code: Int): DocumentRejectedKind =
        when (code) {
            OVERSIZED_AT_IMPORT -> DocumentRejectedKind.OversizedAtImport
            NOT_A_PDF -> DocumentRejectedKind.NotAPdf
            ENCRYPTED -> DocumentRejectedKind.Encrypted
            TOO_MANY_PAGES -> DocumentRejectedKind.TooManyPages
            RENDERER_FAILED -> DocumentRejectedKind.RendererFailed
            UNSUPPORTED_ANDROID_VERSION -> DocumentRejectedKind.UnsupportedAndroidVersion
            else -> error("Unknown DocumentRejectedKind wire code: $code")
        }
}
