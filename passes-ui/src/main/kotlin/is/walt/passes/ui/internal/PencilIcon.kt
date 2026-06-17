package `is`.walt.passes.ui.internal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-authored pencil glyph used by `ScannableCardTile` and `ScannableCardTrustCaption`
 * to signal "user-created, editable artifact" — the second of the redundant visual
 * distinguishers required by C2 in `docs/SCANNABLE_CARD_THREAT_MODEL.md`.
 *
 * Kept in-module on purpose: `passes-ui` does NOT depend on
 * `androidx.compose.material:material-icons-extended` (a multi-megabyte artifact for a
 * single 24dp path). Mirrors the same dependency-light stance as
 * `passes-document-ui::InfoOutlineIcon`. Geometry is the standard Material "edit" pencil on
 * a 24×24 viewport.
 *
 * The [SolidColor] fill is `Color.Black` only because the builder requires a fill; the
 * actual on-screen colour comes from the `tint` passed to `Icon(...)` at the call site
 * (sourced from `UnverifiedArtifactStyle.captionIconTint`). Changing the literal here
 * has no rendered effect.
 */
internal val PencilIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pencil",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Pencil body + tip: a diagonal stroke from upper-right toward lower-left,
            // closing at the bottom-left "nub" rectangle.
            moveTo(3f, 17.25f)
            verticalLineTo(21f)
            horizontalLineToRelative(3.75f)
            lineTo(17.81f, 9.94f)
            lineToRelative(-3.75f, -3.75f)
            lineTo(3f, 17.25f)
            close()
            // Ferrule / eraser cap at the upper-right end of the pencil.
            moveTo(20.71f, 7.04f)
            curveTo(21.1f, 6.65f, 21.1f, 6.02f, 20.71f, 5.63f)
            lineToRelative(-2.34f, -2.34f)
            curveTo(18.18f, 3.1f, 17.92f, 3f, 17.66f, 3f)
            curveTo(17.4f, 3f, 17.15f, 3.1f, 16.96f, 3.29f)
            lineToRelative(-1.83f, 1.83f)
            lineToRelative(3.75f, 3.75f)
            lineToRelative(1.83f, -1.83f)
            close()
        }
    }.build()
}
