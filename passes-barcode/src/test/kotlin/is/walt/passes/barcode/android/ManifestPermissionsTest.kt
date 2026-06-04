package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xml.sax.InputSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the zero-permissions invariant on `passes-barcode`'s source manifest, making the
 * manifest's headline security claim structural rather than aspirational (the same
 * discipline [PublicApiSurfaceTest] applies to the facade surface). The decode service that
 * lands in wpass-zrt.2 will run in an isolated process and inherit no app permissions; the
 * value of that control depends entirely on this module never declaring a `uses-permission`
 * entry, which manifest merging would otherwise fold into the consumer app's runtime set.
 *
 * The service-presence assertion is intentionally absent until wpass-zrt.2 adds the service
 * (and its `isolatedProcess`/`exported` attributes) alongside the class it names; this test
 * mirrors the zero-permission half of `passes-pdf`'s `ManifestPermissionsTest`.
 *
 * Parses the source manifest (not the merged one) because the source is the authoritative
 * artifact this repository owns, and runs pure-JVM so a permission addition fails the local
 * `./gradlew check` before CI or instrumentation can hide it.
 */
class ManifestPermissionsTest {
    @Test
    fun sourceManifestDeclaresZeroUsesPermission() {
        val manifest = manifestFile()
        assertThat(manifest.exists()).isTrue()

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(InputSource(manifest.inputStream()))

        val usesPermission = doc.getElementsByTagNameNS(ANDROID_NS, "uses-permission")
        assertThat(usesPermission.length).isEqualTo(0)

        val anyUsesPermission = doc.getElementsByTagName("uses-permission")
        assertThat(anyUsesPermission.length).isEqualTo(0)
    }

    private fun manifestFile(): File {
        val moduleDir = System.getProperty("walt.passes.barcode.moduleDir") ?: "."
        return File(moduleDir, "src/main/AndroidManifest.xml")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
