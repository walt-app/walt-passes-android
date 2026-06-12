package `is`.walt.passes.image.ui.internal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-authored "info outline" glyph used by [is.walt.passes.image.ui.ImageTrustCaption].
 *
 * Kept in-module on purpose: `passes-image-ui` does NOT depend on
 * `androidx.compose.material:material-icons-extended` (a multi-megabyte artifact for a
 * single 24dp path). The geometry is the standard Material "info outline" path on a
 * 24×24 viewport, identical to the copy in `passes-pdf-ui::internal.InfoOutlineIcon`.
 * Both modules are independent peers and cannot share internal code.
 *
 * The path's [SolidColor] fill is [Color.Black] only because the builder requires a
 * fill; the actual on-screen colour comes from the `tint` passed to `Icon(...)` at the
 * call site. Built once via `lazy`.
 */
internal val InfoOutlineIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InfoOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 7f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(11f, 11f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
            reflectiveCurveToRelative(3.59f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.59f, 8f, 8f)
            reflectiveCurveToRelative(-3.59f, 8f, -8f, 8f)
            close()
        }
    }.build()
}
