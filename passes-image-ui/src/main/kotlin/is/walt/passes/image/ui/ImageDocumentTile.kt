package `is`.walt.passes.image.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ui.theme.LocalImageSemantics
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.core.toComposeColor

/**
 * A single image entry in the Images lane. Visually distinct from `PassFront` and
 * `DocumentTile` by its "Image" badge — the user reads "this is a saved image file"
 * before they tap.
 *
 * The displayed [ImageDocument.displayLabel] is user-controlled text wrapped in
 * U+2068/U+2069 (FSI/PDI) by `passes-ui-core::isolated` so a malicious filename
 * carrying directional-format characters cannot reorder surrounding chrome glyphs.
 *
 * No `share`, no `export`, no overflow menu, no metadata display.
 */
@Composable
public fun ImageDocumentTile(
    doc: ImageDocument,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantics = LocalImageSemantics.current
    Surface(
        modifier = modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        color = semantics.tileBackground.toComposeColor(),
        contentColor = semantics.tileForeground.toComposeColor(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(THUMBNAIL_ASPECT_RATIO)
                    .clip(RoundedCornerShape(4.dp))
                    .background(semantics.laneBackground.toComposeColor()),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(semantics.tileLabelForeground.toComposeColor()),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = isolated(doc.displayLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.tileForeground.toComposeColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ImageBadge()
            }
        }
    }
}

@Composable
private fun ImageBadge() {
    val semantics = LocalImageSemantics.current
    Text(
        text = IMAGE_BADGE_TEXT,
        style = MaterialTheme.typography.labelSmall,
        color = semantics.imageBadgeForeground.toComposeColor(),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(semantics.imageBadgeBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp)),
    )
}

internal const val IMAGE_BADGE_TEXT: String = "Image"
private const val THUMBNAIL_ASPECT_RATIO: Float = 4f / 3f
