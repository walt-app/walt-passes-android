package `is`.walt.passes.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * The structural lock that makes [DocumentTelemetryGuard] a security control rather than a
 * convenience interface: no method on the guard, and no constructor of any event data
 * class that flows through it, may accept a `String`, `CharSequence`, `ByteArray`, or
 * `Map`. Adding such a parameter is precisely the policy change reviewers should catch
 * before code merges, so this test fails compilation-or-assertion if it ever happens.
 *
 * Reflection is the right tool here because the test is asserting a property of the
 * *shape* of the API rather than a behavior. A behavior test could never close the gap of
 * "someone added a field that nobody happens to log yet."
 */
class DocumentTelemetryGuardSurfaceTest {
    @Test
    fun guardMethodsRejectFreeFormPayloadTypes() {
        val violations = mutableListOf<String>()
        for (method in DocumentTelemetryGuard::class.java.declaredMethods) {
            method.collectViolations(violations)
        }
        assertThat(violations).isEmpty()
    }

    @Test
    fun eventConstructorsRejectFreeFormPayloadTypes() {
        val violations = mutableListOf<String>()
        for (klass in eventClasses) {
            klass.collectCtorViolations(violations)
            klass.collectMethodViolations(violations)
        }
        assertThat(violations).isEmpty()
    }

    private fun Class<*>.collectCtorViolations(violations: MutableList<String>) {
        for (ctor in declaredConstructors) {
            for ((index, paramType) in ctor.genericParameterTypes.withIndex()) {
                if (paramType.isForbidden()) {
                    violations += "$simpleName ctor parameter $index is forbidden type $paramType"
                }
            }
        }
    }

    private fun Class<*>.collectMethodViolations(violations: MutableList<String>) {
        for (method in declaredMethods) {
            if (method.name.startsWith("component") || method.name == "copy") continue
            method.collectViolations(violations)
        }
    }

    private fun Method.collectViolations(violations: MutableList<String>) {
        for ((index, paramType) in genericParameterTypes.withIndex()) {
            if (paramType.isForbidden()) {
                violations += "${declaringClass.simpleName}.$name parameter $index is forbidden type $paramType"
            }
        }
    }

    private fun Type.isForbidden(): Boolean {
        val raw: Class<*> = rawClassOrNull() ?: return false
        val isByteArray = raw.isArray && raw.componentType == java.lang.Byte.TYPE
        return isByteArray || forbiddenClasses.any { it.isAssignableFrom(raw) }
    }

    private fun Type.rawClassOrNull(): Class<*>? =
        when (this) {
            is Class<*> -> this
            is ParameterizedType -> rawType as? Class<*>
            else -> null
        }

    private val eventClasses: List<Class<*>> =
        listOf(
            DocumentImportSucceededEvent::class.java,
            DocumentImportFailedEvent::class.java,
        )

    private val forbiddenClasses: List<Class<*>> =
        listOf(
            String::class.java,
            CharSequence::class.java,
            Map::class.java,
        )
}
