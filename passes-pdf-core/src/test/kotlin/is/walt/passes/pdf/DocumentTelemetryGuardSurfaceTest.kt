package `is`.walt.passes.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * The structural lock that makes [DocumentTelemetryGuard] a security control rather than a
 * convenience interface. The KDoc on the guard claims "enums, counts, and durations only";
 * this test enforces that claim by *allowlist*, not denylist.
 *
 * Allowlist over denylist is deliberate. A denylist test ("no `String` parameter") leaks
 * past the obvious smuggling routes — `List<String>`, `Set<String>`, `Map<Enum, String>`,
 * `Pair<String, Long>`, `Array<String>`. Each of those slips a `String` into telemetry
 * while resolving to a raw class (`List`, `Map`, `Pair`, `Array`) that the denylist would
 * not flag. An allowlist that names every legitimate parameter shape (Long, Int, enums,
 * and the event classes themselves) closes that gap structurally: anything else is a
 * test failure, including things future contributors have not invented yet.
 *
 * Reflection is the right tool here because the test is asserting a property of the
 * *shape* of the API rather than a behavior. A behavior test could never close the gap of
 * "someone added a field that nobody happens to log yet."
 */
class DocumentTelemetryGuardSurfaceTest {
    @Test
    fun guardMethodsAcceptOnlyEventClassParameters() {
        val violations = mutableListOf<String>()
        for (method in DocumentTelemetryGuard::class.java.declaredMethods) {
            method.collectViolations(violations, allowed = AllowedTypes.GuardMethod)
        }
        assertThat(violations).isEmpty()
    }

    /**
     * Meta-test demonstrating the lock is not vacuous: every smuggling shape the PR
     * review enumerated (`List<String>`, `Set<String>`, `Map<Enum, String>`,
     * `Pair<String, Long>`, `Array<String>`, `ByteArray`) is rejected by the allowlist.
     * If a future refactor accidentally weakens the lock, this test fails first and
     * surfaces the issue inside the same file as the rule it protects.
     */
    @Test
    fun allowlistRejectsKnownStringSmugglingShapes() {
        val bad: List<Class<*>> =
            listOf(
                BadListOfString::class.java,
                BadSetOfString::class.java,
                BadMapEnumToString::class.java,
                BadPairOfStringLong::class.java,
                BadArrayOfString::class.java,
                BadByteArray::class.java,
            )
        for (klass in bad) {
            val violations = mutableListOf<String>()
            klass.collectCtorViolations(violations, allowed = AllowedTypes.EventCtor)
            assertThat(violations).isNotEmpty()
        }
    }

    private data class BadListOfString(val tags: List<String>, val durationMillis: Long)

    private data class BadSetOfString(val tags: Set<String>, val durationMillis: Long)

    private data class BadMapEnumToString(
        val tags: Map<DocumentRejectedKind, String>,
        val durationMillis: Long,
    )

    private data class BadPairOfStringLong(val tag: Pair<String, Long>, val durationMillis: Long)

    private data class BadArrayOfString(val tags: Array<String>, val durationMillis: Long)

    private data class BadByteArray(val payload: ByteArray, val durationMillis: Long)

    @Test
    fun eventConstructorsAcceptOnlyEnumsAndCountsAndDurations() {
        val violations = mutableListOf<String>()
        for (klass in eventClasses) {
            klass.collectCtorViolations(violations, allowed = AllowedTypes.EventCtor)
            klass.collectMethodViolations(violations, allowed = AllowedTypes.EventCtor)
        }
        assertThat(violations).isEmpty()
    }

    private fun Class<*>.collectCtorViolations(
        violations: MutableList<String>,
        allowed: AllowedTypes,
    ) {
        for (ctor in declaredConstructors) {
            for ((index, paramType) in ctor.genericParameterTypes.withIndex()) {
                if (!paramType.isAllowed(allowed)) {
                    violations += "$simpleName ctor parameter $index has disallowed type $paramType"
                }
            }
        }
    }

    private fun Class<*>.collectMethodViolations(
        violations: MutableList<String>,
        allowed: AllowedTypes,
    ) {
        for (method in declaredMethods) {
            if (method.isSyntheticOrInherited()) continue
            method.collectViolations(violations, allowed)
        }
    }

    // Data classes generate componentN, copy, copy$default (synthetic), equals, hashCode,
    // and toString. None of them are surface a contributor would add a String to in
    // practice; they are mechanical compiler output from the data class declaration. The
    // ctor parameters, which are the actual contributor-author-able shape, are checked
    // separately by collectCtorViolations.
    private fun Method.isSyntheticOrInherited(): Boolean =
        isSynthetic ||
            name.startsWith("component") ||
            name == "copy" ||
            name == "equals" ||
            name == "hashCode" ||
            name == "toString"

    private fun Method.collectViolations(
        violations: MutableList<String>,
        allowed: AllowedTypes,
    ) {
        for ((index, paramType) in genericParameterTypes.withIndex()) {
            if (!paramType.isAllowed(allowed)) {
                violations += "${declaringClass.simpleName}.$name parameter $index has disallowed type $paramType"
            }
        }
    }

    private fun Type.isAllowed(allowed: AllowedTypes): Boolean {
        // Generic type arguments (List<String>, Map<*, String>, etc.) are not on the
        // allowlist and are caught by rawClassOrNull returning the erased class. Any
        // ParameterizedType collapses to its raw and then must clear the same bar.
        val raw = rawClassOrNull() ?: return false
        return when (allowed) {
            // Guard methods accept either an event class wrapper (the historical
            // `onImport*` shape) or a bare enum (the `onConsumerRenderFailed(reason)`
            // shape). Both clear the PII bar — an enum is the leanest legitimate shape
            // and is already proven safe at the EventCtor level. `String`, `ByteArray`,
            // `Map`, etc. all collapse to non-enum, non-event-class raws and stay caught.
            AllowedTypes.GuardMethod -> raw in eventClasses || raw.isEnum
            AllowedTypes.EventCtor ->
                raw == java.lang.Long.TYPE ||
                    raw == java.lang.Integer.TYPE ||
                    raw == java.lang.Long::class.java ||
                    raw == java.lang.Integer::class.java ||
                    raw.isEnum
        }
    }

    private fun Type.rawClassOrNull(): Class<*>? =
        when (this) {
            is Class<*> -> this
            is ParameterizedType -> rawType as? Class<*>
            else -> null
        }

    private enum class AllowedTypes { GuardMethod, EventCtor }

    private val eventClasses: List<Class<*>> =
        listOf(
            DocumentImportSucceededEvent::class.java,
            DocumentImportFailedEvent::class.java,
        )
}
