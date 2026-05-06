package `is`.walt.passes.core.internal

/**
 * PKPASS archive member names that the parser-glue layer, the manifest verifier, and
 * the hardened ZIP extractor must agree on byte-for-byte. The trust claim ("a manifest
 * cannot self-reference, and the signature signs the manifest") rides on three call
 * sites using the same string; the constant lives here so that agreement is structural
 * rather than three private duplicates that could drift.
 */
internal const val SIGNATURE_FILE_NAME: String = "signature"

/** See [SIGNATURE_FILE_NAME] — same rationale, applied to `manifest.json`. */
internal const val MANIFEST_FILE_NAME: String = "manifest.json"
