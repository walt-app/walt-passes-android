package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The single audited bounded-bitmap decode shared by every Walt path that turns untrusted
 * compressed image bytes into a [Bitmap]: the in-process pkpass display decode
 * (`passes-ui`'s `BoundedImage`), the isolated still-image symbol decode
 * (`passes-barcode`), and the wpass-i9x isolated image-document service. The
 * `ImageDecoder` + `OnHeaderDecodedListener` lever, the pre-allocation header gate, the
 * 1x1-on-reject downsize, and the full `Throwable` containment all live here ONCE, so that
 * security property is audited in one place instead of re-proven per call site.
 *
 * Mechanism only; the caller's [policy] carries the allocator, the header gate, and the
 * thrown-failure folds. A rejection already raised by the gate always wins over a fold,
 * matching the `rejection ?: fallback` idiom both original call sites used.
 *
 * Never throws except a propagated [OutOfMemoryError] when [BoundedDecodePolicy.onOutOfMemory]
 * is null.
 */
public fun <R : Any> decodeBounded(
    rawBytes: ByteArray,
    policy: BoundedDecodePolicy<R>,
): BoundedBitmap<R> {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(rawBytes))
    var rejection: R? = null
    return try {
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = policy.allocator
                rejection = policy.gate.evaluate(info.mimeType, info.size.width, info.size.height)
                if (rejection != null) {
                    // Force a 1x1 decode so the rejected path allocates nothing of size; the
                    // bitmap is recycled below.
                    decoder.setTargetSize(1, 1)
                }
            }
        rejection?.let {
            bitmap.recycle()
            BoundedBitmap.Rejected(it)
        } ?: BoundedBitmap.Decoded(bitmap)
    } catch (_: IOException) {
        // Genuine parse/IO failure, unless a header rejection was already in flight.
        BoundedBitmap.Rejected(rejection ?: policy.onMalformed())
    } catch (_: IllegalArgumentException) {
        // setTargetSize(1, 1) can throw for formats that refuse arbitrary sizing; the
        // rejection that triggered it already fired in the gate, so preserve it.
        BoundedBitmap.Rejected(rejection ?: policy.onMalformed())
    } catch (oom: OutOfMemoryError) {
        // A canvas that slipped the header caps must not take the process down uncontained —
        // unless the caller has no bucket for it (onOutOfMemory == null), in which case the
        // caller's posture is to let it propagate.
        val fold = policy.onOutOfMemory ?: throw oom
        BoundedBitmap.Rejected(rejection ?: fold())
    } catch (_: RuntimeException) {
        BoundedBitmap.Rejected(rejection ?: policy.onRuntimeFailure())
    }
}

/**
 * A call site's bounded-decode posture, passed once to [decodeBounded]. Groups the levers
 * that vary between the in-process display path and the isolated hostile-input paths so each
 * site declares its decode contract as one auditable object.
 *
 * [allocator] picks software (when pixels must be read back, e.g. a symbol decode) versus the
 * platform default (display). [gate] decides accept/reject from the not-yet-allocated header
 * (the caller's caps and format allowlist) and names the caller's own rejection type [R]. The
 * [onMalformed] / [onRuntimeFailure] / [onOutOfMemory] folds map a thrown failure to [R].
 *
 * [onOutOfMemory] is nullable on purpose. A caller decoding hostile bytes in a sandbox passes
 * a fold so an OOM is contained as a rejection; the in-process display caller passes null so
 * an OOM propagates unchanged (the host has no rejection bucket for it). That keeps each
 * path's exact containment posture.
 */
public class BoundedDecodePolicy<R : Any>(
    public val allocator: Int,
    public val gate: ImageHeaderGate<R>,
    public val onMalformed: () -> R,
    public val onRuntimeFailure: () -> R,
    public val onOutOfMemory: (() -> R)?,
)

/**
 * The pre-allocation accept/reject decision a caller supplies in its [BoundedDecodePolicy].
 * Invoked from the `OnHeaderDecodedListener` with the container [mimeType] and the
 * decoded-but-not-allocated [width]/[height]; returns a caller-typed rejection reason to
 * refuse the image, or null to allocate and return it.
 */
public fun interface ImageHeaderGate<out R> {
    public fun evaluate(mimeType: String?, width: Int, height: Int): R?
}

/**
 * Outcome of [decodeBounded]: either a [Bitmap] that cleared the [ImageHeaderGate] and
 * decoded, or a caller-typed [reason] the decode was refused. The caller owns and must
 * release the [Bitmap].
 */
public sealed interface BoundedBitmap<out R> {
    public data class Decoded(val bitmap: Bitmap) : BoundedBitmap<Nothing>

    public data class Rejected<out R>(val reason: R) : BoundedBitmap<R>
}
