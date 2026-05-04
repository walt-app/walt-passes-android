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
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes [bytes] using Android `ImageDecoder` with explicit dimension caps applied via
 * [ImageDecoder.OnHeaderDecodedListener]. The header listener fires before the decoder
 * allocates a backing bitmap, so a hostile pass archive cannot OOM the host process via
 * a multi-gigabyte PNG.
 *
 * The implementation deliberately does NOT use Coil's default loader: bounds enforcement
 * is the trust claim, and a third-party loader would have to be re-audited for that
 * property on every dependency upgrade. The decoder pipeline here is the only path
 * `passes-ui` ever uses to produce a bitmap from an `ImageBytes`.
 *
 * Decode failures (including bounds violations) render as an empty placeholder and fire
 * [UiTelemetryGuard.onImageDecodeRejected] with the categorized reason. The composable
 * never throws.
 */
@Composable
public fun BoundedImage(
    bytes: ImageBytes,
    @Suppress("UNUSED_PARAMETER") role: ImageRole,
    contentDescription: String?,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(bytes, bounds) { mutableStateOf<Bitmap?>(null) }
    var rejection by remember(bytes, bounds) { mutableStateOf<ImageDecodeRejection?>(null) }

    LaunchedEffect(bytes, bounds) {
        val (decoded, reason) = decodeBoundedBitmap(bytes.bytes, bounds)
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
): Pair<Bitmap?, ImageDecodeRejection?> {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(rawBytes))
    var rejection: ImageDecodeRejection? = null
    return try {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            rejection = when {
                w > bounds.maxWidthPx -> ImageDecodeRejection.ExceedsWidth
                h > bounds.maxHeightPx -> ImageDecodeRejection.ExceedsHeight
                w.toLong() * h.toLong() > bounds.maxAreaPx -> ImageDecodeRejection.ExceedsArea
                else -> null
            }
            if (rejection != null) {
                // Force a 1x1 decode so the underlying allocation stays trivially small.
                // The composable discards the bitmap below.
                decoder.setTargetSize(1, 1)
            }
        }
        if (rejection != null) {
            null to rejection
        } else {
            bitmap to null
        }
    } catch (e: IOException) {
        null to ImageDecodeRejection.Malformed
    } catch (e: IllegalArgumentException) {
        null to ImageDecodeRejection.Malformed
    } catch (e: RuntimeException) {
        null to (rejection ?: ImageDecodeRejection.Other)
    }
}
