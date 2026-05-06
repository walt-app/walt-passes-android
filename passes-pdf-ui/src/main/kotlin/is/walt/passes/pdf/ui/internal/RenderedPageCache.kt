package `is`.walt.passes.pdf.ui.internal

import `is`.walt.passes.pdf.PdfDocumentId

/**
 * Bounded access-ordered LRU keyed by `(PdfDocumentId, page)` for the rasterised pages
 * displayed by `DocumentView`. The production wiring stores [android.graphics.Bitmap]
 * values and supplies `Bitmap::recycle` as [onEvict] so native pixel memory is released
 * the moment a page falls out of the window — the renderer service has already
 * pre-recycled its source-side copy (see ADR 0005 D3 / `PdfRendererService`), so the
 * UI consumer is the sole owner of the bitmap on this side of the binder.
 *
 * The class is generic over the value type so the eviction-order contract is testable
 * without the Android framework. Tests pass `String` (or any other token) and observe
 * the [onEvict] callback to lock the access-order semantics.
 *
 * Thread-safety: callers are expected to invoke this from the Compose main thread (the
 * same scope that drives `LaunchedEffect`); no synchronization is added here. A future
 * background prefetch path would introduce its own synchronization wrapper rather than
 * mutating this cache from off-main.
 */
internal class RenderedPageCache<V>(
    private val maxSize: Int,
    private val onEvict: (V) -> Unit = {},
) {
    init {
        require(maxSize > 0) { "maxSize must be positive (was $maxSize)" }
    }

    private val map: LinkedHashMap<Pair<PdfDocumentId, Int>, V> =
        // accessOrder = true makes get() reorder the entry to most-recently-used.
        LinkedHashMap(maxSize, LOAD_FACTOR, /* accessOrder = */ true)

    public val size: Int get() = map.size

    public fun get(documentId: PdfDocumentId, page: Int): V? = map[documentId to page]

    public fun put(documentId: PdfDocumentId, page: Int, value: V) {
        val key = documentId to page
        // remove() ensures put() reinserts at the access-order tail rather than retaining
        // the previous insertion position; LinkedHashMap.put on an existing key reorders
        // when accessOrder is true, but being explicit keeps the eviction contract
        // independent of that subtle JDK behaviour.
        map.remove(key)?.let(onEvict)
        map[key] = value
        while (map.size > maxSize) {
            val eldestKey = map.keys.iterator().next()
            val evicted = map.remove(eldestKey) ?: continue
            onEvict(evicted)
        }
    }

    /**
     * Drop every cached entry, firing [onEvict] for each. The host calls this when the
     * `DocumentView` composable leaves composition or when the displayed document
     * changes — every retained bitmap is a native pixel allocation and we want them
     * freed as eagerly as possible.
     */
    public fun clear() {
        if (map.isEmpty()) return
        // Snapshot before clearing so onEvict callbacks cannot reenter and observe a
        // partially-cleared map.
        val snapshot = map.values.toList()
        map.clear()
        snapshot.forEach(onEvict)
    }

    private companion object {
        const val LOAD_FACTOR: Float = 0.75f
    }
}
