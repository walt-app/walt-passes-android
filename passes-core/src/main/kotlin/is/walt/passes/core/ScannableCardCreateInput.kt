package `is`.walt.passes.core

/**
 * Raw, pre-validation input the consumer passes in when the user submits the create form.
 * Separate type from [ScannableCard] so the validation boundary is explicit: anything of
 * type [ScannableCardCreateInput] has NOT been checked against length caps, charset rules,
 * or bidi/control-character hygiene yet, and anything of type [ScannableCard] has.
 *
 * Construction is deliberately permissive — the validator (Child 4) is the choke point.
 * `color = null` means "use the consumer-side default tint," not "no tint" — there is no
 * untinted render path; the consumer always applies a fallback.
 */
public data class ScannableCardCreateInput(
    public val payload: String,
    public val format: ScannableFormat,
    public val label: String,
    public val color: ScannableColor?,
)
