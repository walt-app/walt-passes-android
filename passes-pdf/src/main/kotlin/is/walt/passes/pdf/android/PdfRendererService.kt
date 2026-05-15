package `is`.walt.passes.pdf.android

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.PdfImportConfig
import java.io.IOException

/**
 * The isolated-process renderer service. Declared in this module's manifest with
 * `android:isolatedProcess="true"` and zero `uses-permission` entries, the service runs
 * under an isolated UID with no INTERNET, no storage permission, no clipboard, no
 * anything (ADR 0005 D3). A PDFium use-after-free here brings down the renderer, not
 * the wallet, and the isolated sandbox guarantees the compromised process has no useful
 * capabilities to reach for in the moment before it dies.
 *
 * The service exposes exactly two binder transactions ([PdfRendererBinder.probe] and
 * [PdfRendererBinder.render]). There is intentionally no method to extract text,
 * metadata, annotations, attachments, or form fields; [PublicApiSurfaceTest] asserts
 * the surface is exactly those two by reflection.
 *
 * Caps in [PdfRendererService] mirror `passes-storage`'s `DocumentBounds` (25 MB total /
 * 10 pages). Until `wpass-kej` lands the cross-module parity test, both numbers must be
 * kept in lockstep manually; reviewers should reject any change to one without a
 * matching change to the other.
 */
public class PdfRendererService : Service() {
    private val config: PdfImportConfig = PdfImportConfig()
    private val watchdog: RenderWatchdog = RenderWatchdog(config.renderTimeoutMs)

    override fun onBind(intent: Intent): IBinder = PdfRendererBinderProxy(buildImpl())

    private fun buildImpl(): PdfRendererBinder =
        object : PdfRendererBinder {
            override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult = doProbe(pdf, config.maxPages)

            override suspend fun render(
                pdf: ParcelFileDescriptor,
                page: Int,
                widthPx: Int,
                heightPx: Int,
                sourceRect: RenderSourceRect,
            ): RenderResult = doRender(pdf, page, widthPx, heightPx, sourceRect, watchdog)
        }

    public companion object {
        /** Mirrors [is.walt.passes.pdf.PdfImportConfig.DEFAULT_MAX_BYTES] and storage's `DocumentBounds.MAX_BYTES`. */
        public const val MAX_BYTES: Long = PdfImportConfig.DEFAULT_MAX_BYTES

        /** Mirrors [is.walt.passes.pdf.PdfImportConfig.DEFAULT_MAX_PAGES] and storage's `DocumentBounds.MAX_PAGES`. */
        public const val MAX_PAGES: Int = PdfImportConfig.DEFAULT_MAX_PAGES

        /**
         * Bound on render output dimensions. 4 MP at ARGB_8888 is 16 MB of pixel data,
         * comfortably below the SharedMemory caller can map. The cap is a defence against
         * a malicious caller asking for an arbitrarily large bitmap on the renderer side;
         * the *design* expectation is that the consumer's UI layer renders at view-port
         * resolution and never approaches this number.
         */
        public const val MAX_PIXELS: Long = 4L * 1024 * 1024
    }
}

internal suspend fun doProbe(pdf: ParcelFileDescriptor, maxPages: Int): ProbeResult =
    runCatching {
        PdfRenderer(pdf).use { renderer ->
            val pages = renderer.pageCount
            if (pages > maxPages) {
                ProbeResult.Rejected(DocumentRejectedKind.TooManyPages)
            } else {
                ProbeResult.Ok(pages)
            }
        }
    }.getOrElse { t -> ProbeResult.Rejected(rejectedKindForOpenFailure(t)) }

internal suspend fun doRender(
    pdf: ParcelFileDescriptor,
    page: Int,
    widthPx: Int,
    heightPx: Int,
    sourceRect: RenderSourceRect,
    watchdog: RenderWatchdog,
): RenderResult {
    val dimsOk = widthPx > 0 && heightPx > 0 && widthPx.toLong() * heightPx.toLong() <= PdfRendererService.MAX_PIXELS
    val rectOk = isSourceRectValid(sourceRect)
    return if (!dimsOk || !rectOk) {
        RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
    } else {
        runCatching {
            watchdog.guard { renderToSharedMemory(pdf, page, widthPx, heightPx, sourceRect) }
        }.getOrElse { RenderResult.Rejected(DocumentRejectedKind.RendererFailed) }
    }
}

// Strict ordering rules out zero-area rects (would produce a degenerate Matrix); the
// unit-square bound keeps the consumer's failures visible instead of silently blank.
internal fun isSourceRectValid(sourceRect: RenderSourceRect): Boolean =
    when (sourceRect) {
        is RenderSourceRect.FullPage -> true
        is RenderSourceRect.SubRect -> {
            val l = sourceRect.left
            val t = sourceRect.top
            val r = sourceRect.right
            val b = sourceRect.bottom
            val finite = l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()
            finite && l in 0f..1f && t in 0f..1f && r in 0f..1f && b in 0f..1f && l < r && t < b
        }
    }

private fun renderToSharedMemory(
    pdf: ParcelFileDescriptor,
    page: Int,
    widthPx: Int,
    heightPx: Int,
    sourceRect: RenderSourceRect,
): RenderResult =
    PdfRenderer(pdf).use { renderer ->
        // Defense-in-depth: doProbe also enforces MAX_PAGES, but the binder does not
        // require probe before render and there is no per-document state. Re-checking
        // here means a 5 000-page PDF cannot be rasterised even if the consumer skips
        // probe or asks for a page beyond MAX_PAGES.
        val pageCountOk = renderer.pageCount in 0..PdfRendererService.MAX_PAGES
        val pageOk = page in 0 until renderer.pageCount && page < PdfRendererService.MAX_PAGES
        if (!pageCountOk || !pageOk) {
            RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
        } else {
            renderer.openPage(page).use { p -> rasterise(p, widthPx, heightPx, sourceRect) }
        }
    }

private fun rasterise(
    p: PdfRenderer.Page,
    widthPx: Int,
    heightPx: Int,
    sourceRect: RenderSourceRect,
): RenderResult.Ok {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    try {
        val transform = matrixFor(sourceRect, p.width, p.height, widthPx, heightPx)
        p.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return RenderResult.Ok(packIntoSharedMemory(bitmap, widthPx, heightPx), widthPx, heightPx)
    } finally {
        bitmap.recycle()
    }
}

// FullPage keeps the legacy `null` transform (fit page to bitmap). SubRect maps the
// sub-rect's page-point coords onto the full destination bitmap, sharp under zoom.
private fun matrixFor(
    sourceRect: RenderSourceRect,
    pageWidth: Int,
    pageHeight: Int,
    widthPx: Int,
    heightPx: Int,
): Matrix? =
    when (sourceRect) {
        is RenderSourceRect.FullPage -> null
        is RenderSourceRect.SubRect -> {
            val srcLeft = sourceRect.left * pageWidth
            val srcTop = sourceRect.top * pageHeight
            val srcWidth = (sourceRect.right - sourceRect.left) * pageWidth
            val srcHeight = (sourceRect.bottom - sourceRect.top) * pageHeight
            Matrix().apply {
                setScale(widthPx / srcWidth, heightPx / srcHeight)
                preTranslate(-srcLeft, -srcTop)
            }
        }
    }

private fun packIntoSharedMemory(bitmap: Bitmap, widthPx: Int, heightPx: Int): SharedMemory {
    val byteCount = widthPx * heightPx * BYTES_PER_PIXEL
    val sm = SharedMemory.create("walt-pdf-render", byteCount)
    val buf = sm.mapReadWrite()
    try {
        bitmap.copyPixelsToBuffer(buf)
    } finally {
        SharedMemory.unmap(buf)
    }
    sm.setProtect(OsConstants.PROT_READ)
    return sm
}

private fun rejectedKindForOpenFailure(t: Throwable): DocumentRejectedKind =
    when (t) {
        // PdfRenderer signals an encrypted PDF by throwing SecurityException at open time.
        is SecurityException -> DocumentRejectedKind.Encrypted
        is IOException -> DocumentRejectedKind.NotAPdf
        else -> DocumentRejectedKind.NotAPdf
    }

private const val BYTES_PER_PIXEL = 4
