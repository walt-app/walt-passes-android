package `is`.walt.passes.barcode.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xml.sax.InputSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins both manifest security invariants on `passes-barcode`'s source manifest, making the
 * manifest's headline security claims structural rather than aspirational (the same
 * discipline [PublicApiSurfaceTest] applies to the facade surface):
 *
 *  1. Zero `uses-permission` entries — the decode service inherits no app permissions; the
 *     value of that control depends entirely on this module never declaring one, which
 *     manifest merging would otherwise fold into the consumer app's runtime set.
 *  2. The decode service is declared `isolatedProcess="true"`, `exported="false"`, and
 *     names exactly the [BarcodeDecodeService] class. This is the wpass-zrt.2 half deferred
 *     from wpass-zrt.1, mirroring `passes-pdf`'s `rendererServiceIsIsolatedAndNotExported`.
 *
 * Parses the source manifest (not the merged one) because the source is the authoritative
 * artifact this repository owns, and runs pure-JVM so a regression fails the local
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

    @Test
    fun decodeServiceIsIsolatedAndNotExported() {
        val manifest = manifestFile()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(InputSource(manifest.inputStream()))
        // Manifest elements live in the default namespace; only their attributes carry the
        // android: prefix. Look up the service by local name.
        val services = doc.getElementsByTagName("service")
        assertThat(services.length).isEqualTo(1)
        val svc = services.item(0) as org.w3c.dom.Element
        assertThat(svc.getAttributeNS(ANDROID_NS, "isolatedProcess")).isEqualTo("true")
        assertThat(svc.getAttributeNS(ANDROID_NS, "exported")).isEqualTo("false")
        assertThat(svc.getAttributeNS(ANDROID_NS, "name")).isEqualTo(".android.BarcodeDecodeService")
        assertThat(svc.getAttributeNS(ANDROID_NS, "process")).isEqualTo(":barcodeDecoder")
    }

    private fun manifestFile(): File {
        val moduleDir = System.getProperty("walt.passes.barcode.moduleDir") ?: "."
        return File(moduleDir, "src/main/AndroidManifest.xml")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
