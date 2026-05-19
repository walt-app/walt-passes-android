package `is`.walt.passes.ui

/**
 * Layout option for the three B3 confirmation sheets ([B3UrlConfirmSheet],
 * [PhoneConfirmSheet], [EmailConfirmSheet]). The choice is purely visual: both arms
 * display the verbatim target string the host's outbound `Intent` will carry, both
 * fire the same telemetry events on the same gestures, and both block dispatch on
 * an explicit confirm tap. The default is [Container] so existing call sites are
 * behavior-identical.
 *
 * The split (wpass-48v) exists because the original [Container] layout reads
 * visually identical to a pass-issuer Verified band — both render in
 * `PassesSemantics.signatureBadge`'s orange container token. For hosts that surface
 * a Verified signal anywhere else on the same screen, the [DomainHero] layout is
 * the trust-claim-safer choice: the verbatim target moves to a low-emphasis
 * forensic row and a destination summary (registrable domain / formatted phone /
 * address local-part) takes the visual lead — a structurally different shape, so
 * the user does not mistake "we are about to leave Walt and visit this URL" for
 * "this pass is verified."
 *
 * The threat-contract guarantees from `TRUST_CLAIMS.md` are layout-agnostic.
 */
public sealed interface B3UrlEmphasisStyle {
    /** The original kernel layout: verbatim target in an orange emphasis container. */
    public data object Container : B3UrlEmphasisStyle

    /**
     * Domain-first layout: `LEAVING WALT` eyebrow, a hero line summarising the
     * destination (registrable domain / formatted phone / local-part), the verbatim
     * target in a forensic monospace row, a provenance line, then a two-button
     * action row (Cancel outlined, Confirm filled).
     */
    public data object DomainHero : B3UrlEmphasisStyle
}
