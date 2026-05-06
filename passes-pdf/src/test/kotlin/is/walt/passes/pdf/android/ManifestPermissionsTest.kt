package `is`.walt.passes.pdf.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xml.sax.InputSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the zero-permissions invariant on `passes-pdf`'s source manifest. The renderer
 * service runs in an isolated process and inherits no app permissions; the value of
 * that control depends entirely on this module never declaring a `uses-permission`
 * entry of its own. A future contributor adding `<uses-permission android:name="…">`
 * would silently widen the renderer's reachable capabilities the moment manifest
 * merging fold the new permission into the consumer app's runtime set.
 *
 * The test parses the source manifest directly (not the merged manifest) because the
 * source is the authoritative artifact this repository owns. CI should additionally
 * lint the merged manifest in the consumer app to confirm no unrelated module
 * smuggled a permission upstream of `passes-pdf`.
 *
 * Pure-JVM by design: the test must run on the same `./gradlew check` invocation as
 * the rest of the unit suite, so a permission addition fails the local build before
 * CI or instrumentation can hide it.
 */
class ManifestPermissionsTest {
    @Test
    fun sourceManifestDeclaresZeroUsesPermission() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertThat(manifest.exists()).isTrue()

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(manifest.inputStream()))

        val usesPermission = doc.getElementsByTagNameNS(ANDROID_NS, "uses-permission")
        assertThat(usesPermission.length).isEqualTo(0)

        val anyUsesPermission = doc.getElementsByTagName("uses-permission")
        assertThat(anyUsesPermission.length).isEqualTo(0)
    }

    @Test
    fun rendererServiceIsIsolatedAndNotExported() {
        val manifest = File("src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(InputSource(manifest.inputStream()))
        // Manifest elements live in the default namespace; only their attributes carry
        // the android: prefix. Look up by local name.
        val services = doc.getElementsByTagName("service")
        assertThat(services.length).isEqualTo(1)
        val svc = services.item(0) as org.w3c.dom.Element
        assertThat(svc.getAttributeNS(ANDROID_NS, "isolatedProcess")).isEqualTo("true")
        assertThat(svc.getAttributeNS(ANDROID_NS, "exported")).isEqualTo("false")
        assertThat(svc.getAttributeNS(ANDROID_NS, "name")).isEqualTo(".android.PdfRendererService")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
