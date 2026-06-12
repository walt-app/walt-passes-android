package `is`.walt.passes.image.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ImageDocumentId
import `is`.walt.passes.image.ui.theme.LocalImageSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * The Images lane that sits in the wallet root screen alongside the passes and documents
 * lanes. Renders nothing when [images] is empty — absence-of-images is not worth chrome.
 *
 * The lane has a sticky-style header reading "Images" followed immediately by the
 * non-suppressible [ImageTrustCaption]. Composing the caption inside the lane means the
 * user sees the trust signal before any tile and cannot scroll past it. There is no
 * caller-supplied flag to omit the caption; [ImageSurfaceLockTest] enforces this.
 */
@Composable
public fun ImageDocumentsLane(
    images: List<ImageDocument>,
    thumbnails: Map<ImageDocumentId, ImageBitmap>,
    onImageClick: (ImageDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val semantics = LocalImageSemantics.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(semantics.laneBackground.toComposeColor())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = LANE_HEADER_TEXT,
            style = MaterialTheme.typography.titleMedium,
            color = semantics.tileForeground.toComposeColor(),
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp)),
        )
        ImageTrustCaption(modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(images, key = { it.id.value }) { image ->
                ImageDocumentTile(
                    doc = image,
                    thumbnail = thumbnails[image.id],
                    onClick = { onImageClick(image) },
                )
            }
        }
    }
}

internal const val LANE_HEADER_TEXT: String = "Images"
