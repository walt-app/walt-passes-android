package `is`.walt.passes.pdf.android

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import `is`.walt.passes.document.DocumentRejectedKind
import `is`.walt.passes.document.PdfImportConfig
import java.io.IOException
import kotlin.math.roundToInt

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
        /** Mirrors [PdfImportConfig.DEFAULT_MAX_BYTES] and storage's `DocumentBounds.MAX_BYTES`. */
        public const val MAX_BYTES: Long = PdfImportConfig.DEFAULT_MAX_BYTES

        /** Mirrors [PdfImportConfig.DEFAULT_MAX_PAGES] and storage's `DocumentBounds.MAX_PAGES`. */
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

@Suppress("LongParameterList")
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

/**
 * Output bitmap dimensions for a sub-rect render. The sub-rect is rasterised at a single
 * uniform [scale] so the page region keeps its native aspect ratio; the output is the
 * largest such bitmap fitting within the requested [maxWidthPx] x [maxHeightPx] bound
 * (already clamped under the 4 MP cap by the caller). [widthPx] / [heightPx] therefore
 * carry the region's own aspect, never the slot's — drawing them undistorted is what
 * makes the wpass-fdh stretch impossible by construction.
 */
internal data class SubRectOutputDims(val widthPx: Int, val heightPx: Int, val scale: Float)

internal fun subRectOutputDims(
    sourceRect: RenderSourceRect.SubRect,
    pageWidth: Int,
    pageHeight: Int,
    maxWidthPx: Int,
    maxHeightPx: Int,
): SubRectOutputDims {
    val srcWidth = (sourceRect.right - sourceRect.left) * pageWidth
    val srcHeight = (sourceRect.bottom - sourceRect.top) * pageHeight
    val scale = minOf(maxWidthPx / srcWidth, maxHeightPx / srcHeight)
    val w = (srcWidth * scale).roundToInt().coerceIn(1, maxWidthPx)
    val h = (srcHeight * scale).roundToInt().coerceIn(1, maxHeightPx)
    return SubRectOutputDims(w, h, scale)
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
    // Output dimensions and transform both derive from the source rect. FullPage fills
    // the requested bitmap (letterbox bars are the caller's eraseColor). SubRect sizes
    // the bitmap to the region's own aspect ratio and scales it uniformly, so the page
    // can never be stretched at the source (wpass-fdh).
    val outWidth: Int
    val outHeight: Int
    val transform: Matrix
    when (sourceRect) {
        is RenderSourceRect.FullPage -> {
            outWidth = widthPx
            outHeight = heightPx
            transform = fullPageMatrix(p.width, p.height, widthPx, heightPx)
        }
        is RenderSourceRect.SubRect -> {
            val dims = subRectOutputDims(sourceRect, p.width, p.height, widthPx, heightPx)
            outWidth = dims.widthPx
            outHeight = dims.heightPx
            transform = subRectMatrix(sourceRect, p.width, p.height, dims.scale)
        }
    }
    val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    try {
        // PdfRenderer.Page.render() draws PDF content on top of existing pixels without
        // clearing them. Pages that rely on the implicit white page background otherwise
        // rasterise as content-on-transparent and compose against the host's dark surface,
        // hiding white-on-page artwork (GitHub #92: QR codes vanish in dark mode).
        bitmap.eraseColor(Color.WHITE)
        p.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val pageAspect = p.width.toFloat() / p.height.toFloat()
        return RenderResult.Ok(
            sharedMemory = packIntoSharedMemory(bitmap, outWidth, outHeight),
            widthPx = outWidth,
            heightPx = outHeight,
            pageAspect = pageAspect,
        )
    } finally {
        bitmap.recycle()
    }
}

// Aspect-preserving fit of the whole page into the requested bitmap; letterbox bars come
// from the caller's eraseColor, matching the consumer's pageRectInSlot assumption.
private fun fullPageMatrix(pageWidth: Int, pageHeight: Int, widthPx: Int, heightPx: Int): Matrix {
    val scale = minOf(
        widthPx.toFloat() / pageWidth.toFloat(),
        heightPx.toFloat() / pageHeight.toFloat(),
    )
    val dx = (widthPx - pageWidth * scale) / 2f
    val dy = (heightPx - pageHeight * scale) / 2f
    return Matrix().apply {
        setScale(scale, scale)
        postTranslate(dx, dy)
    }
}

// Single uniform scale (the same factor on both axes) maps the sub-rect onto a bitmap
// sized to the region's aspect ratio. The pre-wpass-fdh code scaled X and Y
// independently to fill a fixed bitmap, which stretched the page whenever the visible
// region's aspect differed from the requested bitmap's.
private fun subRectMatrix(
    sourceRect: RenderSourceRect.SubRect,
    pageWidth: Int,
    pageHeight: Int,
    scale: Float,
): Matrix {
    val srcLeft = sourceRect.left * pageWidth
    val srcTop = sourceRect.top * pageHeight
    return Matrix().apply {
        setScale(scale, scale)
        preTranslate(-srcLeft, -srcTop)
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
