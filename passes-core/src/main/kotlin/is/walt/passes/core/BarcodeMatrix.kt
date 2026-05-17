package `is`.walt.passes.core

/**
 * Encoded barcode as a grid of monochrome modules. `true` means "dark module"
 * (printed/black); `false` means "light module" (background/white). Coordinates are
 * zero-based with the origin at the top-left; `(0, 0)` is the top-left module.
 *
 * Opaque wrapper: the underlying storage layout is an implementation detail so the encoder
 * can swap libraries (or move to a packed-bitset representation) without forcing every
 * renderer to re-learn the matrix. Consumers iterate via [isSet] only.
 *
 * Construction is [internal] so this type is mintable only by the kernel's encoder path —
 * external callers cannot fabricate a matrix that misrepresents what ZXing produced. The
 * value type is structurally compared on `(width, height, modules)`; two encoders that
 * produced byte-identical output compare equal even when the underlying arrays differ.
 *
 * Pure data, no behavior. ZXing's `BitMatrix` is intentionally NOT exposed on this surface
 * — that would put a third-party type on the kernel's public API and complicate any future
 * encoder swap.
 */
public class BarcodeMatrix internal constructor(
    public val width: Int,
    public val height: Int,
    private val modules: BooleanArray,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(modules.size == width * height) {
            "modules size ${modules.size} does not match width * height = ${width * height}"
        }
    }

    public fun isSet(
        x: Int,
        y: Int,
    ): Boolean {
        require(x in 0 until width) { "x=$x out of bounds [0, $width)" }
        require(y in 0 until height) { "y=$y out of bounds [0, $height)" }
        return modules[y * width + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BarcodeMatrix) return false
        return width == other.width && height == other.height && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + modules.contentHashCode()
        return result
    }

    override fun toString(): String = "BarcodeMatrix(width=$width, height=$height)"
}
