package `is`.walt.passes.barcode.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import `is`.walt.passes.core.BarcodeDecodeResult

/**
 * The isolated-process decode service (wpass-zrt.2) — THE security gate for hostile-image
 * decoding. Declared in this module's manifest with `android:isolatedProcess="true"`,
 * `android:exported="false"`, and zero `uses-permission` entries, it runs under an isolated
 * UID with no INTERNET, no storage, no clipboard, no Keystore, no DPAN/card material — and
 * cannot reach any of them even after a codec compromise (the contractual go/no-go boundary
 * inherited from walt-android wlt-58a.1). A decoder use-after-free here brings down this
 * process, not the wallet, and the isolated sandbox guarantees the compromised process has
 * nothing useful to reach for in the moment before it dies.
 *
 * The service exposes exactly one binder transaction ([BarcodeDecodeBinder.decode]); there
 * is intentionally no method to extract a `Bitmap`, the source bytes, or image metadata.
 * [BarcodeDecodeBinderSurfaceTest] asserts the surface by reflection.
 *
 * The image reaches this process only as a [ParcelFileDescriptor] handed across the bind by
 * the shared `passes-isolation` facade — never as a path (an isolated UID has no filesystem
 * to wander) and never through the caller's main-process heap. The bytes are read only
 * here, inside the sandbox.
 *
 * The actual bounded codec decode (wpass-zrt.3) and ZXing symbol decode (wpass-zrt.4) land
 * behind this surface. Until then [decode] closes the handed-over fd and returns the empty
 * arm, which exercises the full bind / PFD-handoff / teardown path without decoding any
 * bytes — the plumbing this bead delivers, proven without the decoder it hosts.
 */
public class BarcodeDecodeService : Service() {
    override fun onBind(intent: Intent): IBinder = BarcodeDecodeBinderProxy(buildImpl())

    private fun buildImpl(): BarcodeDecodeBinder =
        object : BarcodeDecodeBinder {
            override suspend fun decode(image: ParcelFileDescriptor): BarcodeDecodeResult {
                // wpass-zrt.3 (bounded bitmap decode) + wpass-zrt.4 (ZXing) replace this
                // body with the real decode, which reads `image` before closing it. The
                // stub closes the fd it owns so the round-trip path leaks nothing.
                runCatching { image.close() }
                return BarcodeDecodeResult.NoBarcodeFound
            }
        }
}
