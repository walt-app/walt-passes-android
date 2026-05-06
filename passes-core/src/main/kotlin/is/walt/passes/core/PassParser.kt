package `is`.walt.passes.core

import `is`.walt.passes.core.internal.DefaultPassParser
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
    /**
     * Parse [source] into a [ParseResult]. Synchronous and CPU-bound: BouncyCastle's
     * PKCS#7 verification, JSON tokenization, and zip extraction all run on the calling
     * thread. Wrap with `withTimeout(...)` (kotlinx.coroutines) when invoking from a
     * coroutine context that requires a per-call timeout — [ParserConfig] does not yet
     * expose a built-in deadline knob and will not until the public surface freeze
     * around ADR 0001 lifts.
     *
     * Never throws: every failure mode is encoded as a [ParseResult] arm.
     */
    public fun parse(source: PassSource): ParseResult

    public companion object {
        /**
         * Construct a parser bound to [config]. The returned instance is safe to call
         * concurrently from multiple threads — it holds only the immutable [config] and
         * delegates to stateless internal pipeline functions on each [parse] invocation.
         */
        public fun create(config: ParserConfig = ParserConfig()): PassParser = DefaultPassParser(config)
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
