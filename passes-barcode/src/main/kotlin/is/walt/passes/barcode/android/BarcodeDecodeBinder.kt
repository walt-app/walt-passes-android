package `is`.walt.passes.barcode.android

import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The internal binder contract for the isolated-process decode service (wpass-zrt.2). One
 * method: [decode] takes the candidate image as a [ParcelFileDescriptor] and returns only a
 * pure [BarcodeDecodeResult] — `{payload, format}`, a no-symbol marker, or a bucketed
 * failure. There is intentionally no method returning a `Bitmap`, the source bytes, image
 * metadata, or EXIF: the hostile image never crosses back into the caller's address space.
 * [BarcodeDecodeBinderSurfaceTest] enforces the surface is exactly this one method by
 * reflection, so adding an extraction backdoor is a structural compile-and-test failure
 * rather than a code-review judgement call.
 *
 * Not AIDL: AIDL generates a Stub that exposes its own reflectable surface and a parser of
 * arbitrary remote-supplied data. A hand-rolled [android.os.Binder] subclass
 * ([BarcodeDecodeBinderProxy]) keeps the IPC surface to exactly the one transaction here,
 * mirroring the [is.walt.passes.pdf.android.PdfRendererBinder] discipline.
 *
 * The image arrives as a [ParcelFileDescriptor] rather than a path or a byte array. The
 * decode process never sees a filesystem path (it has none to wander to in an isolated
 * UID), and the bytes are read only inside the sandbox — the main process passes a
 * descriptor, never the image content.
 *
 * PFD ownership: the caller retains ownership of the [ParcelFileDescriptor] it passed to
 * the facade. The binder duplicates the underlying fd on the way across; the decode
 * service owns and closes its received copy.
 */
public interface BarcodeDecodeBinder {
    public suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult
}
