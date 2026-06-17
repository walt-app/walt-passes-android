package `is`.walt.passes.document.ui

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
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.core.toComposeColor

/**
 * A single document entry in the Documents lane. Visually distinct from `PassFront`
 * by its smaller corner radius (8 dp vs the pass's 16 dp) and its persistent
 * "Document" badge — the user reads "this is a saved file, not a signed pass" before
 * they tap.
 *
 * The displayed [PdfDocument.displayLabel] is supplied by the consumer at import time
 * (typically the source filename) and is treated as user-controlled text. The label
 * is wrapped in U+2068/U+2069 (FSI/PDI) by `passes-ui-core::isolated` so a malicious
 * filename carrying directional-format characters cannot reorder surrounding chrome
 * glyphs. Same defense the security sheets apply to organisation names and verbatim
 * URLs.
 *
 * No `share`, no `export`, no overflow menu, no metadata. ADR 0005 D8 (no share-out)
 * is enforced both behaviourally — there's nowhere on this tile to invoke
 * `Intent.ACTION_SEND` — and structurally by `DocumentPublicApiSurfaceTest`.
 */
@Composable
public fun DocumentTile(
    doc: PdfDocument,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantics = LocalDocumentSemantics.current
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
                        // Decorative — the displayLabel underneath carries the
                        // navigational name. TalkBack reads the label.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // The outer Box already constrains aspect via its own
                        // .aspectRatio(...) modifier; setting it here too is redundant.
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
                    // displayLabel is user-controlled (filename or import-time
                    // string). FSI/PDI wrap is required defense-in-depth even
                    // though the import path strips Cf/Cc characters; a future
                    // change there cannot silently weaken this surface.
                    text = isolated(doc.displayLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.tileForeground.toComposeColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DocumentBadge()
            }
        }
    }
}

@Composable
private fun DocumentBadge() {
    val semantics = LocalDocumentSemantics.current
    Text(
        text = DOCUMENT_BADGE_TEXT,
        style = MaterialTheme.typography.labelSmall,
        color = semantics.documentBadgeForeground.toComposeColor(),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(semantics.documentBadgeBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp)),
    )
}

internal const val DOCUMENT_BADGE_TEXT: String = "Document"
private const val THUMBNAIL_ASPECT_RATIO: Float = 4f / 3f
