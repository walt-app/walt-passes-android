package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.decodeBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes [bytes] using Android `ImageDecoder` with explicit dimension caps applied via
 * [ImageDecoder.OnHeaderDecodedListener]. The header listener fires before the decoder
 * allocates a backing bitmap, so a hostile pass archive cannot OOM the host process via
 * a multi-gigabyte PNG.
 *
 * Residual risk: `OnHeaderDecodedListener` does not bound the transient memory the
 * platform decoder uses while parsing the file header itself. For pathological inputs
 * (e.g. PNGs with very large ancillary chunks), header parsing can allocate proportional
 * memory before the listener fires. `ImageDecoder` is the safest decode primitive
 * Android offers, but it is not a hard guarantee against all decompression-bomb shapes.
 * `BoundedImage` rejects the bitmap once dimensions are known; the implementation bead's
 * follow-on work is an instrumentation test that exercises the residual-risk surface.
 *
 * The implementation deliberately does NOT use Coil's default loader: bounds enforcement
 * is the trust claim, and a third-party loader would have to be re-audited for that
 * property on every dependency upgrade. The decoder pipeline here is the only path
 * `passes-ui` ever uses to produce a bitmap from an `ImageBytes`.
 *
 * Decode runs on `Dispatchers.IO`, so a multi-megapixel image does not stutter the
 * host's UI thread. Decode failures (including bounds violations) render as an empty
 * placeholder and fire [UiTelemetryGuard.onImageDecodeRejected] with the categorized
 * reason. The composable never throws.
 */
@Composable
public fun BoundedImage(
    bytes: ImageBytes,
    @Suppress("UNUSED_PARAMETER") role: ImageRole,
    contentDescription: String?,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
) {
    var bitmap by remember(bytes, bounds) { mutableStateOf<Bitmap?>(null) }
    var rejection by remember(bytes, bounds) { mutableStateOf<ImageDecodeRejection?>(null) }

    LaunchedEffect(bytes, bounds) {
        val (decoded, reason) = withContext(Dispatchers.IO) {
            decodeBoundedBitmap(bytes.bytes, bounds)
        }
        bitmap = decoded
        rejection = reason
        reason?.let { telemetry.onImageDecodeRejected(it) }
    }

    val current = bitmap
    if (current != null) {
        Image(
            painter = BitmapPainter(current.asImageBitmap()),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Spacer(modifier)
    }
}

/**
 * Visible-for-tests JVM-pure decoder driver. Returns the produced bitmap (or `null` on
 * failure) plus the rejection reason (or `null` on success). Splitting this from the
 * composable lets the implementation bead's instrumentation tests assert decode behavior
 * without having to render anything.
 */
internal fun decodeBoundedBitmap(
    rawBytes: ByteArray,
    bounds: ImageRenderBounds,
): Pair<Bitmap?, ImageDecodeRejection?> =
    when (
        val decoded =
            decodeBounded(
                rawBytes = rawBytes,
                policy =
                    BoundedDecodePolicy(
                        // Platform default: a hardware bitmap is fine here — this path is
                        // display-only and never reads pixels back, unlike the symbol decoder.
                        allocator = ImageDecoder.ALLOCATOR_DEFAULT,
                        gate = { _, w, h ->
                            when {
                                w > bounds.maxWidthPx -> ImageDecodeRejection.ExceedsWidth
                                h > bounds.maxHeightPx -> ImageDecodeRejection.ExceedsHeight
                                w.toLong() * h.toLong() > bounds.maxAreaPx -> ImageDecodeRejection.ExceedsArea
                                else -> null
                            }
                        },
                        onMalformed = { ImageDecodeRejection.Malformed },
                        onRuntimeFailure = { ImageDecodeRejection.Other },
                        // In-process display: no rejection bucket for an OOM, so let it
                        // propagate rather than silently swallow it (preserves prior posture).
                        onOutOfMemory = null,
                    ),
            )
    ) {
        is BoundedBitmap.Decoded -> decoded.bitmap to null
        is BoundedBitmap.Rejected -> null to decoded.reason
    }
