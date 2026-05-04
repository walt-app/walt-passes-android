package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pins the parameter-shape discipline of the trust-claim-bearing composables (ADR
 * 0003 D5). These shape constraints ARE the trust contract: a future refactor that
 * adds `enabled: Boolean = false` to `ExpiredOverlay`, or `skipConfirmation: Boolean = false`
 * to a security sheet, would silently weaken the claim documented in TRUST_CLAIMS.md.
 *
 * Goes through Java reflection because the Compose compiler plugin breaks the
 * Kotlin-reflection round-trip for top-level `@Composable` functions
 * (`KClass.staticFunctions` returns empty for these). We therefore enumerate
 * `Method.parameterTypes` and filter out the two synthetic trailing parameters
 * Compose appends (`Composer` and `int $changed`, plus optional `int $changed1`
 * for high-arity functions).
 *
 * The test locks the *user-visible parameter count*, not parameter names. That gives
 * a reviewer-friendly fail on adding any new parameter — the contract is "exactly
 * these N parameters, in this order" — without depending on parameter-name
 * preservation across Kotlin / JVM compilation modes.
 */
class ComposableSurfaceLockTest {

    @Test
    fun expiredOverlayHasExactlyThreeUserVisibleParameters() {
        // (state, locale, modifier) — D5: no `enabled`, no `suppress`.
        assertUserVisibleParamCount("ExpiredOverlayKt", "ExpiredOverlay", expected = 3)
    }

    @Test
    fun b3UrlConfirmSheetHasExactlyFiveUserVisibleParameters() {
        // (intent, passType, telemetry, onConfirm, onDismiss) — D5: no skipConfirmation.
        assertUserVisibleParamCount("SecuritySheetsKt", "B3UrlConfirmSheet", expected = 5)
    }

    @Test
    fun phoneConfirmSheetHasExactlyFiveUserVisibleParameters() {
        assertUserVisibleParamCount("SecuritySheetsKt", "PhoneConfirmSheet", expected = 5)
    }

    @Test
    fun emailConfirmSheetHasExactlyFiveUserVisibleParameters() {
        assertUserVisibleParamCount("SecuritySheetsKt", "EmailConfirmSheet", expected = 5)
    }

    @Test
    fun passFrontHasExactlySixUserVisibleParameters() {
        // (pass, signatureStatus, locale, nowEpochMillis, telemetry, modifier) — D5:
        // no showTrustBadge, no showExpired, no expiredOverlay override.
        assertUserVisibleParamCount("PassFrontKt", "PassFront", expected = 6)
    }

    @Test
    fun passBackHasExactlySevenUserVisibleParameters() {
        // (pass, locale, onUrlIntent, onPhoneIntent, onEmailIntent, telemetry, modifier).
        // D5 also requires the three intent callbacks to be non-defaulted; that's
        // structurally enforced because they have no default expressions in source.
        assertUserVisibleParamCount("PassBackKt", "PassBack", expected = 7)
    }

    @Test
    fun boundedImageHasExactlySixUserVisibleParameters() {
        // (bytes, role, contentDescription, bounds, telemetry, modifier). D5: no
        // form of `bounds: ImageRenderBounds? = null` that would let a caller opt out.
        assertUserVisibleParamCount("BoundedImageKt", "BoundedImage", expected = 6)
    }

    @Test
    fun boundedImageBoundsParameterIsTheImageRenderBoundsType() {
        // Belt: locking the count guarantees no extra params; suspenders: confirm the
        // bounds parameter is in fact the strong type, not e.g. a nullable.
        val method = findComposable("BoundedImageKt", "BoundedImage")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertThat(typeNames).contains("ImageRenderBounds")
    }

    @Test
    fun securitySheetsAllAcceptUiTelemetryGuard() {
        listOf("B3UrlConfirmSheet", "PhoneConfirmSheet", "EmailConfirmSheet").forEach { name ->
            val method = findComposable("SecuritySheetsKt", name)
            val typeNames = method.parameterTypes.map { it.simpleName }
            assertWithMessage("$name must declare a UiTelemetryGuard parameter")
                .that(typeNames)
                .contains("UiTelemetryGuard")
        }
    }

    // -- helpers -------------------------------------------------------------------

    private fun findComposable(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.ui.$fileClassSimpleName")
        // Kotlin's value-class name mangling appends `-<hash>` to a JVM method name
        // when the function takes a value-class parameter. `passes-core` exposes
        // several value classes (`PassLocale`, `PassInstant`, `ImageBytes`,
        // `ColorValue`), so most of these composables compile to e.g.
        // `PassFront-I15CzMI`. Match either the bare name or the mangled prefix.
        return klass.methods
            .filter { it.name == methodName || it.name.startsWith("$methodName-") }
            .maxByOrNull { it.parameterCount }
            ?: error("Composable $fileClassSimpleName.$methodName not found")
    }

    private fun userVisibleParameterCount(method: Method): Int {
        // Compose appends `Composer $composer` and `int $changed` (and `int $changed1`
        // for high-arity functions). Strip them by their JVM type names.
        val params = method.parameterTypes
        var count = params.size
        // Walk backwards stripping Composer and int parameters that are clearly
        // Compose-synthetic. We stop at the first parameter that is neither Composer
        // nor a primitive int.
        var i = params.lastIndex
        while (i >= 0) {
            val t = params[i]
            val isComposer = t.name == "androidx.compose.runtime.Composer"
            val isInt = t.name == "int"
            if (!isComposer && !isInt) break
            count--
            i--
        }
        // The very-last user-visible parameter could legitimately be an `Int`
        // (e.g. PassFront accepts `nowEpochMillis: Long`, not Int — so this is safe
        // for our composables). If a future composable adds a trailing `Int` arg,
        // re-anchor the strip on the Composer parameter only.
        return count
    }

    private fun assertUserVisibleParamCount(
        fileClassSimpleName: String,
        methodName: String,
        expected: Int,
    ) {
        val method = findComposable(fileClassSimpleName, methodName)
        val actual = userVisibleParameterCount(method)
        assertWithMessage(
            "$methodName user-visible parameter count drifted; review ADR 0003 D5 " +
                "before changing this number. Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(expected)
    }
}
