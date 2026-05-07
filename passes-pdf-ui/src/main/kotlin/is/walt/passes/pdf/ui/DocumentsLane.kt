package `is`.walt.passes.pdf.ui

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
import `is`.walt.passes.pdf.PdfDocument
import `is`.walt.passes.pdf.PdfDocumentId
import `is`.walt.passes.pdf.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * The Documents lane that sits below the passes list on the wallet root screen. The
 * lane renders nothing when [documents] is empty — there is no empty-state placeholder,
 * because absence-of-PDFs is not a state worth chrome.
 *
 * The lane has a sticky-style header reading "Documents" followed immediately by the
 * non-suppressible [DocumentTrustCaption]. Composing the caption inside the lane means
 * the user sees the trust signal before any tile and cannot scroll past it. There is no
 * caller-supplied flag to omit the caption; the structural lock in
 * `DocumentSurfaceLockTest` and the surface assertion in `DocumentTrustSurfaceTest`
 * enforce that a caller cannot skip past it.
 *
 * Visual distinction from the passes list comes from the lane's own background tone
 * (separate token in [is.walt.passes.pdf.ui.theme.DocumentSemantics.laneBackground]) so
 * a glance distinguishes a document from a signed pass without reading the badge.
 */
@Composable
public fun DocumentsLane(
    documents: List<PdfDocument>,
    thumbnails: Map<PdfDocumentId, ImageBitmap>,
    onDocumentClick: (PdfDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (documents.isEmpty()) return
    val semantics = LocalDocumentSemantics.current
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
        DocumentTrustCaption(modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(documents, key = { it.id.value }) { doc ->
                DocumentTile(
                    doc = doc,
                    thumbnail = thumbnails[doc.id],
                    onClick = { onDocumentClick(doc) },
                )
            }
        }
    }
}

internal const val LANE_HEADER_TEXT: String = "Documents"
