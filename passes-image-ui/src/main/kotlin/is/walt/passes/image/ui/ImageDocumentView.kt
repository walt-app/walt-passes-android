package `is`.walt.passes.image.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ui.theme.LocalImageSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Full-detail view for a single [ImageDocument]. Displays the decoded [image] at fit
 * resolution above the non-suppressible [ImageTrustCaption].
 *
 * [image] is supplied by the consumer (decoded by `BitmapFactory` in the host
 * ViewModel); this composable does not trigger any decode work. A null [image] renders
 * a placeholder box at the image's stored aspect ratio, preserving layout stability
 * while the host awaits the decoded bitmap.
 *
 * The trust caption is composed below the image and cannot be hidden; there is no
 * parameter to suppress it. [ImageSurfaceLockTest] enforces the parameter arity of this
 * function so a future `showCaption: Boolean` cannot be quietly added.
 */
@Composable
public fun ImageDocumentView(
    doc: ImageDocument,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val semantics = LocalImageSemantics.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = semantics.tileBackground.toComposeColor(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
    ) {
        Column {
            val aspectRatio = if (doc.heightPx > 0) {
                doc.widthPx.toFloat() / doc.heightPx.toFloat()
            } else {
                DEFAULT_ASPECT_RATIO
            }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .background(
                            semantics.laneBackground.toComposeColor(),
                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        ),
                )
            }
            ImageTrustCaption(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val DEFAULT_ASPECT_RATIO: Float = 4f / 3f
