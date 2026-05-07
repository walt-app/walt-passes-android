package `is`.walt.passes.pdf.ui.internal

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.pdf.PdfDocumentId
import org.junit.Test

/**
 * Pure-JVM lock on the LRU access-order eviction contract for the rasterised-page
 * cache feeding `DocumentView`. The production wiring stores [android.graphics.Bitmap]
 * and supplies `Bitmap::recycle` as `onEvict`; this test substitutes `String` so the
 * contract is exercisable without Robolectric or the Android framework.
 *
 * The contract:
 *
 *  - Inserting up to `maxSize` entries does not evict.
 *  - Inserting one more evicts the least-recently-used.
 *  - `get()` updates the access-order, so a subsequent insert evicts the *oldest by
 *    access*, not the oldest by insertion.
 *  - `put()` replacing an existing key fires [onEvict] for the previous value.
 *  - `clear()` fires [onEvict] for every retained value once.
 */
class RenderedPageCacheTest {
    private val docId = PdfDocumentId("doc-1")

    @Test
    fun atCapacityNoEvictionFires() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        cache.put(docId, 0, "a")
        cache.put(docId, 1, "b")
        cache.put(docId, 2, "c")
        assertThat(evicted).isEmpty()
        assertThat(cache.size).isEqualTo(3)
    }

    @Test
    fun pastCapacityEvictsLeastRecentlyInserted() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        cache.put(docId, 0, "a")
        cache.put(docId, 1, "b")
        cache.put(docId, 2, "c")
        cache.put(docId, 3, "d")
        assertThat(evicted).containsExactly("a")
        assertThat(cache.get(docId, 0)).isNull()
        assertThat(cache.get(docId, 3)).isEqualTo("d")
        assertThat(cache.size).isEqualTo(3)
    }

    @Test
    fun getUpdatesAccessOrder() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        cache.put(docId, 0, "a")
        cache.put(docId, 1, "b")
        cache.put(docId, 2, "c")
        // Touch "a" so it becomes most-recently-used; "b" is now eldest.
        assertThat(cache.get(docId, 0)).isEqualTo("a")
        cache.put(docId, 3, "d")
        assertThat(evicted).containsExactly("b")
        assertThat(cache.get(docId, 1)).isNull()
        assertThat(cache.get(docId, 0)).isEqualTo("a")
    }

    @Test
    fun replacingValueForSameKeyEvictsThePreviousValue() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        cache.put(docId, 0, "first")
        cache.put(docId, 0, "second")
        assertThat(evicted).containsExactly("first")
        assertThat(cache.get(docId, 0)).isEqualTo("second")
        assertThat(cache.size).isEqualTo(1)
    }

    @Test
    fun multiplePagesPastWindowEvictsInAccessOrder() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        // Walk a 6-page document at the same render budget; eviction order must
        // match the user's pager swipes — oldest-by-access falls out first.
        listOf("a", "b", "c", "d", "e", "f").forEachIndexed { page, value ->
            cache.put(docId, page, value)
        }
        assertThat(evicted).containsExactly("a", "b", "c").inOrder()
        assertThat(cache.size).isEqualTo(3)
    }

    @Test
    fun keysAreScopedByDocumentId() {
        val a = PdfDocumentId("doc-a")
        val b = PdfDocumentId("doc-b")
        val cache = RenderedPageCache<String>(maxSize = 4)
        cache.put(a, 0, "a0")
        cache.put(b, 0, "b0")
        assertThat(cache.get(a, 0)).isEqualTo("a0")
        assertThat(cache.get(b, 0)).isEqualTo("b0")
    }

    @Test
    fun clearEvictsEveryRetainedValue() {
        val evicted = mutableListOf<String>()
        val cache = RenderedPageCache<String>(maxSize = 3, onEvict = evicted::add)
        cache.put(docId, 0, "a")
        cache.put(docId, 1, "b")
        cache.put(docId, 2, "c")
        cache.clear()
        assertThat(evicted).containsExactly("a", "b", "c").inOrder()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test
    fun maxSizeMustBePositive() {
        try {
            RenderedPageCache<String>(maxSize = 0)
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("maxSize")
        }
    }
}
