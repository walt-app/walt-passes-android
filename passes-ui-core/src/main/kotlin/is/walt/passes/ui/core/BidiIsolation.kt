package `is`.walt.passes.ui.core

/**
 * Wrap [s] in Unicode First-Strong Isolate / Pop Directional Isolate (U+2068, U+2069).
 * Inside the isolate the bidi algorithm treats the contents as a single neutral
 * directional unit: characters within cannot reorder text outside, and surrounding
 * directional context cannot reorder characters within. This is the recommended
 * fence for displaying user-controlled strings in bidi-sensitive surfaces (UAX #9
 * §3.4 isolate formatting characters).
 *
 * Used in `passes-ui` (security sheets — verbatim URL / phone / email / org name)
 * and in `passes-pdf-ui` (document tile — user-controlled `displayLabel` / filename).
 * Both surfaces combine this with consumer-side `Cf`/`Cc` rejection so the displayed
 * string is rendered as-typed; an attacker can no longer craft a value that looks
 * visually like a trusted string while parsing as a hostile one.
 *
 * Lives in `passes-ui-core` so it does not have to be duplicated between
 * `passes-ui` and `passes-pdf-ui`; a duplicated bidi fence is exactly the kind of
 * trust-claim-bearing logic the kernel commits NOT to parallel-implement.
 */
public fun isolated(s: String): String = "$FSI$s$PDI"

/**
 * First Strong Isolate (U+2068). Opens an isolate that takes the directional class
 * of the first strong-class character within.
 */
public const val FSI: Char = '⁨'

/** Pop Directional Isolate (U+2069). Closes the most recently opened isolate. */
public const val PDI: Char = '⁩'
