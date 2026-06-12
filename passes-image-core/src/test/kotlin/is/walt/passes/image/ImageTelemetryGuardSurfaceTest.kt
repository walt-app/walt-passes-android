package `is`.walt.passes.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * The structural lock that makes [ImageTelemetryGuard] a security control rather than a
 * convenience interface. The KDoc on the guard claims "enums, counts, and durations only";
 * this test enforces that claim by *allowlist*, not denylist.
 *
 * Allowlist over denylist is deliberate: a denylist ("no String parameter") leaks past
 * `List<String>`, `Set<String>`, `Map<Enum, String>`, `Pair<String, Long>`,
 * `Array<String>`. An allowlist that names every legitimate parameter shape closes that
 * gap structurally — anything else is a test failure.
 */
class ImageTelemetryGuardSurfaceTest {

    @Test
    fun guardMethodsAcceptOnlyEventClassParameters() {
        val violations = mutableListOf<String>()
        for (method in ImageTelemetryGuard::class.java.declaredMethods) {
            method.collectViolations(violations, allowed = AllowedTypes.GuardMethod)
        }
        assertThat(violations).isEmpty()
    }

    @Test
    fun allowlistRejectsKnownStringSmugglingShapes() {
        val bad: List<Class<*>> = listOf(
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
    private data class BadMapEnumToString(val tags: Map<ImageRejectedKind, String>, val durationMillis: Long)
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

    private fun Class<*>.collectCtorViolations(violations: MutableList<String>, allowed: AllowedTypes) {
        for (ctor in declaredConstructors) {
            for ((index, paramType) in ctor.genericParameterTypes.withIndex()) {
                if (!paramType.isAllowed(allowed)) {
                    violations += "$simpleName ctor parameter $index has disallowed type $paramType"
                }
            }
        }
    }

    private fun Class<*>.collectMethodViolations(violations: MutableList<String>, allowed: AllowedTypes) {
        for (method in declaredMethods) {
            if (method.isSyntheticOrInherited()) continue
            method.collectViolations(violations, allowed)
        }
    }

    private fun Method.isSyntheticOrInherited(): Boolean =
        isSynthetic ||
            name.startsWith("component") ||
            name == "copy" ||
            name == "equals" ||
            name == "hashCode" ||
            name == "toString"

    private fun Method.collectViolations(violations: MutableList<String>, allowed: AllowedTypes) {
        for ((index, paramType) in genericParameterTypes.withIndex()) {
            if (!paramType.isAllowed(allowed)) {
                violations += "${declaringClass.simpleName}.$name parameter $index has disallowed type $paramType"
            }
        }
    }

    private fun Type.isAllowed(allowed: AllowedTypes): Boolean {
        val raw = rawClassOrNull() ?: return false
        return when (allowed) {
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

    private val eventClasses: List<Class<*>> = listOf(
        ImageImportSucceededEvent::class.java,
        ImageImportFailedEvent::class.java,
    )
}
