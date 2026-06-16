package `is`.walt.passes.core

import `is`.walt.passes.export.ArtifactKind
import `is`.walt.passes.export.ExportableArtifact
import java.time.Instant

/**
 * A user-generated, unsigned scannable artifact. Sibling of [Pass], NOT a subtype: existence
 * of a [ScannableCard] value asserts that the wrapped payload has cleared the kernel's
 * validator (length caps, charset rules, bidi/control-character rejection). The constructor
 * is [internal] so this invariant cannot be bypassed by an outside caller hand-building one
 * around raw input — the only construction path is via the validator's
 * [ScannableCardCreateResult.Success].
 *
 * Where [Pass] carries a sibling [SignatureStatus] to convey trust, [ScannableCard] carries
 * trust at the type level instead — there is no signature to validate because the user typed
 * the data. The two artifact classes deliberately share no supertype; introducing one would
 * re-create the trust-conflation risk the wpass-lzi epic forbids.
 */
@ConsistentCopyVisibility
public data class ScannableCard internal constructor(
    public val id: ScannableCardId,
    public val payload: String,
    public val format: ScannableFormat,
    public val label: String,
    public val createdAt: PassInstant,
) : ExportableArtifact {
    override val exportKind: String get() = ArtifactKind.SCANNABLE_CARD
    override val exportId: String get() = id.value
    override val exportCreatedAt: String get() = Instant.ofEpochMilli(createdAt.epochMillis).toString()
}

/**
 * Type-safe identifier for a [ScannableCard]. passes-core does not mint IDs; the storage
 * module assigns one on insert and consumers pass that value back through here for any
 * subsequent reference.
 */
@JvmInline
public value class ScannableCardId(public val value: String)
