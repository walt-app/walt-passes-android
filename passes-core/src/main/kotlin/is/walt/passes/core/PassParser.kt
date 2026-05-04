package `is`.walt.passes.core

import java.io.InputStream

/**
 * The single entrypoint into passes-core. Implementations must be safe to call concurrently
 * across threads (each call holds only stack-local state plus the immutable [ParserConfig]),
 * and must never throw out of [parse]: every error path is encoded as a [ParseResult] arm.
 *
 * Returning a sealed result instead of `Result<Pass>` (per CLAUDE.md's preference for
 * `Result<T>`) is deliberate here: the failure space is rich enough that a generic
 * `Throwable` would lose the partition between tampered / malformed / unsupported that the
 * UI must distinguish.
 */
public fun interface PassParser {
    public fun parse(source: PassSource): ParseResult

    public companion object {
        /**
         * Construct a parser bound to [config]. The default factory is intentionally not
         * yet implemented; landing it is the next bead after this skeleton.
         */
        public fun create(config: ParserConfig = ParserConfig()): PassParser =
            PassParser { _ ->
                throw NotImplementedError(
                    "PassParser implementation pending follow-up bead; this skeleton only " +
                        "fixes the public API surface. config=$config",
                )
            }
    }
}

/**
 * A PKPASS archive in a form the parser can stream. The parser materializes the input into
 * memory only as needed, applying [ParserConfig.maxArchiveBytes] and friends along the way.
 */
public sealed interface PassSource {
    /**
     * Whole archive already resident in memory. Caller retains ownership of [bytes]; the
     * parser does not modify the array.
     */
    public class Bytes(public val bytes: ByteArray) : PassSource

    /**
     * Streaming source. [sizeHintBytes], when known, is checked against
     * [ParserConfig.maxArchiveBytes] up front to fail fast on oversized payloads. The
     * parser does not close [stream]; the caller owns its lifecycle.
     */
    public class Stream(
        public val stream: InputStream,
        public val sizeHintBytes: Long? = null,
    ) : PassSource
}
