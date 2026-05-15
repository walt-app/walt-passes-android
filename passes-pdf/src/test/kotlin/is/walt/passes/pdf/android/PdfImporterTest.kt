package `is`.walt.passes.pdf.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.DocumentImportFailedEvent
import `is`.walt.passes.pdf.DocumentImportSucceededEvent
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.DocumentTelemetryGuard
import `is`.walt.passes.pdf.PdfImportConfig
import `is`.walt.passes.pdf.PdfImportResult
import `is`.walt.passes.pdf.android.internal.PdfPfdFactory
import `is`.walt.passes.pdf.android.internal.RendererSession
import `is`.walt.passes.pdf.android.internal.RendererSessionFactory
import `is`.walt.passes.pdf.android.internal.ThumbnailEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Behavioural coverage for [DefaultPdfImporter]. Each test pins one rule the importer
 * promises in its KDoc:
 *
 *  - SDK gate fires immediately, before any source byte is read or any service is bound.
 *  - Header sniff short-circuits *before* `bindService`.
 *  - Size cap fail-fast — never reads more than `maxBytes + 1` bytes.
 *  - Every [DocumentRejectedKind] from the renderer service rounds back through `import`.
 *  - `unbindService` runs in every outcome (success / each rejection / persist throw).
 *  - `persist` is invoked exactly once on success, never on rejection.
 *  - Telemetry: `onImportFailed` fires on every rejection; `onImportStarted` fires only
 *    once we've cleared the SDK gate; `onImportSucceeded` fires only on success.
 *
 * Production internals are wired via the package-internal seams `PdfPfdFactory` and
 * `RendererSessionFactory`; the public surface (`PdfImporter.create`) stays untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfImporterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sdkBelow34RejectsWithoutTouchingSourceOrBindingService() = runTest {
        val recordingFactory = RecordingSessionFactory()
        val recordingPfd = RecordingPfdFactory()

        val importer =
            importer(
                sdkInt = 33,
                pfdFactory = recordingPfd,
                sessionFactory = recordingFactory,
            )

        val result = importer.import(
            // FileDescriptor source so the assertion "renderer was not invoked" can be
            // proven directly via the recording session factory; if the orchestration
            // had advanced past the gate the recording PFD factory would have been
            // called to materialize the bytes for the renderer.
            source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
            displayLabel = "irrelevant.pdf",
            persist = { _, _, _, _ -> error("persist must not be invoked on SDK gate") },
        )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.UnsupportedAndroidVersion))
        assertThat(recordingPfd.calls).isEqualTo(0)
        assertThat(recordingFactory.connectCalls).isEqualTo(0)
    }

    @Test
    fun headerSniffShortCircuitsBeforeBindingService() = runTest {
        val nonPdf = "Not a PDF at all".encodeToByteArray()
        val recordingFactory = RecordingSessionFactory()
        val recordingPfd = RecordingPfdFactory()

        val importer =
            importer(
                pfdFactory = recordingPfd,
                sessionFactory = recordingFactory,
            )

        val result =
            importer.import(
                source = PdfImportSource.FileDescriptor(pfdContaining(nonPdf)),
                displayLabel = "spoofed.pdf",
                persist = { _, _, _, _ -> error("persist must not be invoked when header sniff fails") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf))
        assertThat(recordingFactory.connectCalls).isEqualTo(0)
        // The memfd PFD is allocated only once the bytes have cleared the header sniff,
        // so a failing sniff must skip it. This is the structural lock the issue calls
        // out: "renderer service is NOT bound yet at this point."
        assertThat(recordingPfd.calls).isEqualTo(0)
    }

    @Test
    fun shorterThanHeaderRejectsAsNotAPdf() = runTest {
        val tinyBytes = "%PDF".encodeToByteArray()
        val recordingFactory = RecordingSessionFactory()

        val result =
            importer(sessionFactory = recordingFactory).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(tinyBytes)),
                displayLabel = "tiny.pdf",
                persist = { _, _, _, _ -> error("persist must not run") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf))
        assertThat(recordingFactory.connectCalls).isEqualTo(0)
    }

    @Test
    fun oversizedSourceFailsFastBeforeFullDrain() = runTest {
        val cap = 8L
        val cfg = PdfImportConfig(maxBytes = cap)
        val recordingFactory = RecordingSessionFactory()
        // 10 bytes of plausible PDF prefix + filler — exceeds the cap of 8.
        val oversized = "%PDF-1.4XX".encodeToByteArray()

        val result =
            importer(config = cfg, sessionFactory = recordingFactory).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(oversized)),
                displayLabel = "big.pdf",
                persist = { _, _, _, _ -> error("persist must not run for oversized") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.OversizedAtImport))
        assertThat(recordingFactory.connectCalls).isEqualTo(0)
    }

    @Test
    fun probeRejectionRoundsTripsThroughImport() = runTest {
        // Encrypted is one of probe's three rejection arms; the other two
        // (TooManyPages, RendererFailed) flow through the same code path.
        val arms = listOf(
            DocumentRejectedKind.Encrypted,
            DocumentRejectedKind.TooManyPages,
            DocumentRejectedKind.RendererFailed,
        )
        for (kind in arms) {
            val factory =
                RecordingSessionFactory(
                    binder = StaticBinder(probeResult = ProbeResult.Rejected(kind)),
                )
            val result =
                importer(sessionFactory = factory).import(
                    source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                    displayLabel = "x.pdf",
                    persist = { _, _, _, _ -> error("persist must not run on probe rejection") },
                )
            assertThat(result).isEqualTo(PdfImportResult.Rejected(kind))
            assertThat(factory.connectCalls).isEqualTo(1)
            assertThat(factory.lastSession?.closed).isTrue()
        }
    }

    @Test
    fun renderRejectionRoundsTripsThroughImport() = runTest {
        for (kind in listOf(DocumentRejectedKind.RendererFailed)) {
            val factory =
                RecordingSessionFactory(
                    binder = StaticBinder(
                        probeResult = ProbeResult.Ok(pageCount = 3),
                        renderResult = RenderResult.Rejected(kind),
                    ),
                )
            val result =
                importer(sessionFactory = factory).import(
                    source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                    displayLabel = "x.pdf",
                    persist = { _, _, _, _ -> error("persist must not run on render rejection") },
                )
            assertThat(result).isEqualTo(PdfImportResult.Rejected(kind))
            assertThat(factory.lastSession?.closed).isTrue()
        }
    }

    @Test
    fun successPathInvokesPersistExactlyOnceAndReturnsImported() = runTest {
        val sm = SharedMemory.create("walt-test-import", DEFAULT_THUMB_PIXEL_BYTES)
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(
                    probeResult = ProbeResult.Ok(pageCount = 4),
                    renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
                ),
            )
        val persists = mutableListOf<PersistArgs>()
        val result =
            importer(sessionFactory = factory).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                displayLabel = "boarding.pdf",
                persist = { label, bytes, pages, thumb ->
                    persists += PersistArgs(label, bytes.size, pages, thumb.size)
                },
            )

        assertThat(result).isInstanceOf(PdfImportResult.Imported::class.java)
        val imported = result as PdfImportResult.Imported
        assertThat(imported.doc.displayLabel).isEqualTo("boarding.pdf")
        assertThat(imported.doc.pageCount).isEqualTo(4)
        assertThat(imported.doc.byteCount).isEqualTo(VALID_PDF_BYTES.size.toLong())
        assertThat(persists).hasSize(1)
        assertThat(persists.single().label).isEqualTo("boarding.pdf")
        assertThat(persists.single().byteSize).isEqualTo(VALID_PDF_BYTES.size)
        assertThat(persists.single().pages).isEqualTo(4)
        assertThat(persists.single().thumbSize).isGreaterThan(0)
        assertThat(factory.lastSession?.closed).isTrue()
    }

    @Test
    fun persistThrowFoldsToStorageHandoffFailedAndUnbinds() = runTest {
        val sm = SharedMemory.create("walt-test-persist-throw", DEFAULT_THUMB_PIXEL_BYTES)
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(
                    probeResult = ProbeResult.Ok(pageCount = 2),
                    renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
                ),
            )

        val result =
            importer(sessionFactory = factory).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                displayLabel = "x.pdf",
                persist = { _, _, _, _ -> error("downstream storage exploded") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.StorageHandoffFailed))
        assertThat(factory.lastSession?.closed).isTrue()
    }

    @Test
    fun encoderThrowFoldsToEncoderFailedAndUnbinds() = runTest {
        val sm = SharedMemory.create("walt-test-encoder-throw", DEFAULT_THUMB_PIXEL_BYTES)
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(
                    probeResult = ProbeResult.Ok(pageCount = 2),
                    renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
                ),
            )
        val throwingEncoder =
            object : ThumbnailEncoder {
                override fun encode(render: RenderResult.Ok): ByteArray {
                    runCatching { render.sharedMemory.close() }
                    error("encoder blew up")
                }
            }

        val result =
            importer(sessionFactory = factory, thumbnailEncoder = throwingEncoder).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                displayLabel = "x.pdf",
                persist = { _, _, _, _ -> error("persist must not run on encoder failure") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.EncoderFailed))
        assertThat(factory.lastSession?.closed).isTrue()
    }

    @Test
    fun persistCancellationPropagatesAndPreservesStructuredConcurrency() = runTest {
        val sm = SharedMemory.create("walt-test-persist-cancel", DEFAULT_THUMB_PIXEL_BYTES)
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(
                    probeResult = ProbeResult.Ok(pageCount = 1),
                    renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
                ),
            )

        val thrown =
            runCatching {
                importer(sessionFactory = factory).import(
                    source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                    displayLabel = "x.pdf",
                    persist = { _, _, _, _ -> throw CancellationException("parent scope cancelled") },
                )
            }.exceptionOrNull()

        // CancellationException must propagate out of `import` rather than being folded
        // onto StorageHandoffFailed; otherwise the parent scope sees "import finished
        // with rejection" instead of "import was cancelled."
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(factory.lastSession?.closed).isTrue()
    }

    @Test
    fun encoderCancellationPropagatesAndPreservesStructuredConcurrency() = runTest {
        val sm = SharedMemory.create("walt-test-encoder-cancel", DEFAULT_THUMB_PIXEL_BYTES)
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(
                    probeResult = ProbeResult.Ok(pageCount = 1),
                    renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
                ),
            )
        val cancellingEncoder =
            object : ThumbnailEncoder {
                override fun encode(render: RenderResult.Ok): ByteArray {
                    runCatching { render.sharedMemory.close() }
                    throw CancellationException("scope cancelled mid-encode")
                }
            }

        val thrown =
            runCatching {
                importer(sessionFactory = factory, thumbnailEncoder = cancellingEncoder).import(
                    source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                    displayLabel = "x.pdf",
                    persist = { _, _, _, _ -> Unit },
                )
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(factory.lastSession?.closed).isTrue()
    }

    @Test
    fun nonContentSchemeUriRejectsAsNotAPdfWithoutBindingService() = runTest {
        // file:// is the canonical escape-hatch shape the scheme allowlist closes:
        // ContentResolver.openInputStream would happily walk a file path otherwise.
        val factory = RecordingSessionFactory()
        val fileUri = Uri.parse("file:///data/data/example/downloads/x.pdf")

        val result =
            importer(sessionFactory = factory).import(
                source = PdfImportSource.ContentUri(fileUri, context.contentResolver),
                displayLabel = "x.pdf",
                persist = { _, _, _, _ -> error("persist must not run for non-content scheme") },
            )

        assertThat(result).isEqualTo(PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf))
        assertThat(factory.connectCalls).isEqualTo(0)
    }

    @Test
    fun unbindRunsOnEveryRejectionAfterSuccessfulConnect() = runTest {
        // Rejections returned by the binder *after* connect happens must still see the
        // session closed. This parameterizes over every post-connect rejection arm.
        val arms = listOf(
            DocumentRejectedKind.Encrypted,
            DocumentRejectedKind.TooManyPages,
            DocumentRejectedKind.RendererFailed,
        )
        for (kind in arms) {
            val factory =
                RecordingSessionFactory(binder = StaticBinder(probeResult = ProbeResult.Rejected(kind)))
            importer(sessionFactory = factory).import(
                source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
                displayLabel = "x.pdf",
                persist = { _, _, _, _ -> Unit },
            )
            assertThat(factory.lastSession?.closed).isTrue()
        }
    }

    @Test
    fun telemetryFiresStartAndSuccessOnHappyPath() = runTest {
        val sm = SharedMemory.create("walt-test-tel-success", DEFAULT_THUMB_PIXEL_BYTES)
        val factory = RecordingSessionFactory(
            binder = StaticBinder(
                probeResult = ProbeResult.Ok(pageCount = 1),
                renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
            ),
        )
        val telemetry = RecordingTelemetry()
        val cfg = PdfImportConfig(telemetryGuard = telemetry)

        importer(config = cfg, sessionFactory = factory).import(
            source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
            displayLabel = "x.pdf",
            persist = { _, _, _, _ -> Unit },
        )

        assertThat(telemetry.events).containsExactly("started", "succeeded:1").inOrder()
    }

    @Test
    fun telemetryFiresFailedWithoutStartedOnSdkGate() = runTest {
        val telemetry = RecordingTelemetry()
        val cfg = PdfImportConfig(telemetryGuard = telemetry)

        importer(config = cfg, sdkInt = 30).import(
            source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
            displayLabel = "x.pdf",
            persist = { _, _, _, _ -> Unit },
        )

        assertThat(telemetry.events).containsExactly("failed:UnsupportedAndroidVersion")
    }

    @Test
    fun telemetryFiresStartedThenFailedOnPostStartRejection() = runTest {
        val factory =
            RecordingSessionFactory(
                binder = StaticBinder(probeResult = ProbeResult.Rejected(DocumentRejectedKind.Encrypted)),
            )
        val telemetry = RecordingTelemetry()
        val cfg = PdfImportConfig(telemetryGuard = telemetry)

        importer(config = cfg, sessionFactory = factory).import(
            source = PdfImportSource.FileDescriptor(pfdContaining(VALID_PDF_BYTES)),
            displayLabel = "x.pdf",
            persist = { _, _, _, _ -> Unit },
        )

        assertThat(telemetry.events).containsExactly("started", "failed:Encrypted").inOrder()
    }

    @Test
    fun contentUriSourceDrainsThroughResolver() = runTest {
        val sm = SharedMemory.create("walt-test-uri", DEFAULT_THUMB_PIXEL_BYTES)
        val factory = RecordingSessionFactory(
            binder = StaticBinder(
                probeResult = ProbeResult.Ok(pageCount = 1),
                renderResult = RenderResult.Ok(sm, DEFAULT_THUMB_W, DEFAULT_THUMB_H, 1f),
            ),
        )
        val u = uri("content://walt-test/import.pdf")
        Shadows.shadowOf(context.contentResolver).registerInputStream(u, ByteArrayInputStream(VALID_PDF_BYTES))

        val persisted = mutableListOf<Int>()
        val result =
            importer(sessionFactory = factory).import(
                source = PdfImportSource.ContentUri(u, context.contentResolver),
                displayLabel = "from-uri.pdf",
                persist = { _, bytes, _, _ -> persisted += bytes.size },
            )

        assertThat(result).isInstanceOf(PdfImportResult.Imported::class.java)
        assertThat(persisted).containsExactly(VALID_PDF_BYTES.size)
    }

    /**
     * Reflection-based surface lock for [PdfImporter]. Mirrors the spirit of the
     * binder's `PublicApiSurfaceTest`: declaring a `getMetadata` / `extractText` /
     * `getAttachments` helper on the importer interface is a structural-test failure,
     * so a future contributor cannot grow the surface without re-review.
     */
    @Test
    fun importerInterfaceHasOnlyImportAsAMethodPlusDefaultBridge() {
        val methodNames =
            PdfImporter::class.java
                .declaredMethods
                .map { it.name }
                .toSet()
        // Allowlist: only `import` (the suspend method becomes a single Java method
        // taking a Continuation). No `extract*`, no `getMetadata`, no `getText`.
        assertThat(methodNames).containsExactly("import")
    }

    // --------------------------------------------------------------------- helpers

    private fun importer(
        config: PdfImportConfig = PdfImportConfig(),
        sdkInt: Int = 34,
        pfdFactory: PdfPfdFactory = PipePfdFactory(),
        sessionFactory: RendererSessionFactory = RecordingSessionFactory(),
        thumbnailEncoder: ThumbnailEncoder = StubThumbnailEncoder(),
    ): DefaultPdfImporter =
        DefaultPdfImporter(
            context = context,
            config = config,
            sdkInt = sdkInt,
            deps = DefaultPdfImporter.Deps(
                pfdFactory = pfdFactory,
                sessionFactoryFor = { sessionFactory },
                thumbnailEncoder = thumbnailEncoder,
                now = { 0L },
                idGenerator = { "test-id" },
            ),
        )

    private fun pfdContaining(bytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        // ParcelFileDescriptor.AutoCloseOutputStream is the documented test-side
        // construction; the importer itself reads the *read* end via Os.read, the
        // path the production code exercises.
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        return pipe[0]
    }

    private fun uri(s: String): Uri = Uri.parse("content://walt-test/$s")

    /**
     * Pipe-backed PFD factory: returns a fresh pipe[0] for any byte payload. The bytes
     * are written into the pipe so test paths that actually pass the PFD onward (none
     * of the unit tests today do; the binder is faked) still see plausible content.
     */
    private class PipePfdFactory : PdfPfdFactory {
        override fun fromBytes(bytes: ByteArray): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            }
            return pipe[0]
        }
    }

    private class RecordingPfdFactory : PdfPfdFactory {
        var calls: Int = 0

        override fun fromBytes(bytes: ByteArray): ParcelFileDescriptor {
            calls++
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching { pipe[1].close() }
            return pipe[0]
        }
    }

    private class RecordingSessionFactory(
        private val binder: PdfRendererBinder = StaticBinder(),
    ) : RendererSessionFactory {
        var connectCalls: Int = 0
        var lastSession: RecordingSession? = null

        override suspend fun connect(): RendererSession {
            connectCalls++
            val s = RecordingSession(binder)
            lastSession = s
            return s
        }
    }

    private class RecordingSession(override val client: PdfRendererBinder) : RendererSession {
        var closed: Boolean = false

        override fun close() {
            closed = true
        }
    }

    private class StubThumbnailEncoder(
        private val bytes: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
    ) : ThumbnailEncoder {
        override fun encode(render: RenderResult.Ok): ByteArray {
            // Close the SharedMemory the same way production does so the test process
            // doesn't leak handles.
            runCatching { render.sharedMemory.close() }
            return bytes
        }
    }

    private class StaticBinder(
        private val probeResult: ProbeResult = ProbeResult.Rejected(DocumentRejectedKind.RendererFailed),
        private val renderResult: RenderResult = RenderResult.Rejected(DocumentRejectedKind.RendererFailed),
    ) : PdfRendererBinder {
        override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult = probeResult

        override suspend fun render(
            pdf: ParcelFileDescriptor,
            page: Int,
            widthPx: Int,
            heightPx: Int,
            sourceRect: RenderSourceRect,
        ): RenderResult = renderResult
    }

    private class RecordingTelemetry : DocumentTelemetryGuard {
        val events: MutableList<String> = mutableListOf()

        override fun onImportStarted() {
            events += "started"
        }

        override fun onImportSucceeded(event: DocumentImportSucceededEvent) {
            events += "succeeded:${event.pageCount}"
        }

        override fun onImportFailed(event: DocumentImportFailedEvent) {
            events += "failed:${event.outcome.name}"
        }

        override fun onConsumerRenderFailed(reason: `is`.walt.passes.pdf.ConsumerRenderFailure) = Unit
    }

    private data class PersistArgs(
        val label: String,
        val byteSize: Int,
        val pages: Int,
        val thumbSize: Int,
    )

    private companion object {
        // Minimum legal PDF header. The body after the magic is irrelevant to the
        // unit suite — the renderer is faked, so PdfRenderer never actually parses it.
        val VALID_PDF_BYTES: ByteArray = "%PDF-1.4\n%¥±ë\n1 0 obj".encodeToByteArray()

        const val DEFAULT_THUMB_W: Int = DefaultPdfImporter.THUMB_WIDTH_PX
        const val DEFAULT_THUMB_H: Int = DefaultPdfImporter.THUMB_HEIGHT_PX

        // 600 x 800 ARGB_8888 → 1 920 000 bytes. Matches the importer's THUMB constants.
        const val DEFAULT_THUMB_PIXEL_BYTES: Int = DEFAULT_THUMB_W * DEFAULT_THUMB_H * 4
    }
}
