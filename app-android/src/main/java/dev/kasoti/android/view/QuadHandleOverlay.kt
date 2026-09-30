package dev.kasoti.android.view

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kasoti.android.field.Quad
import kotlin.math.roundToInt

/**
 * The camera surface, and FR-C2's four-drag-handle manual crop overlay.
 *
 * ## Why the overlay is a `Canvas` over a `PreviewView` rather than a crop the camera does
 *
 * The manual quad is an *app* decision, not a camera one. CameraX can hand back a cropped image
 * from a crop rect, but the quad is normalised to 0..1 of the frame and the operator is dragging
 * it over a *live* preview at a different aspect ratio from the captured frame. So the preview
 * shows the raw stream, the overlay draws the quad over it, and the rectification happens once,
 * in [dev.kasoti.android.platform.AndroidImaging.cropQuad], on the captured bitmap. Doing the
 * crop in the camera would make the operator's drag subject to the camera's own crop geometry,
 * and the mismatch between what they dragged and what got cropped is exactly the bug FR-C2's
 * "manual corner fallback" is there to avoid.
 *
 * ## The four handles
 *
 * A handle is 44dp — larger than [ComposeFieldView.MIN_TOUCH] because it is the one control that
 * needs to be *missed* rather than hit accidentally, and because the finger is on glass at an
 * angle while the hand is not in frame. Each has an accessibility description, so a
 * screen-reader user is told which corner they are on rather than hearing "drag handle".
 */
@Composable
fun CameraSurface(
    onPreviewReady: (PreviewView) -> Unit,
    quad: Quad?,
    interactive: Boolean,
    onHandleMoved: (index: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("camera-preview"),
            factory = { context ->
                PreviewView(context).apply {
                    // PERFORMANCE: FILL_CENTRE with letterboxing, because the crop is done later
                    // in normalised coordinates and a cropped preview would make the operator
                    // drag against a different geometry from the one that will be used.
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreviewReady(this)
                }
            },
        )

        if (interactive && quad != null) {
            QuadHandleOverlay(quad = quad, onHandleMoved = onHandleMoved)
        }
    }
}

@Composable
private fun QuadHandleOverlay(quad: Quad, onHandleMoved: (Int, x: Float, y: Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var local by remember(quad) { mutableStateOf(quad) }

    val radius = HANDLE_RADIUS
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("quad-overlay")
            .semantics { contentDescription = "Document crop with four draggable corner handles" }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    if (size.width == 0 || size.height == 0) return@detectDragGestures
                    val dx = change.position.x / size.width
                    val dy = change.position.y / size.height
                    val corners = local.corners
                    // Nearest handle wins. Nearest rather than "first within radius" because a
                    // gloved finger lands imprecisely and the operator's intent is unambiguous
                    // even when the touch is not.
                    val nearest = corners.indices.minByOrNull { index ->
                        val c = corners[index]
                        val hx = HANDLE_CAPTURE
                        val hy = HANDLE_CAPTURE
                        val cx = if (c.x < hx) 0f else if (c.x > 1f - hx) 1f else c.x
                        val cy = if (c.y < hy) 0f else if (c.y > 1f - hy) 1f else c.y
                        (dx - cx) * (dx - cx) + (dy - cy) * (dy - cy)
                    } ?: return@detectDragGestures
                    val moved = local.withCorner(nearest, clamp(dx), clamp(dy))
                    local = moved
                    onHandleMoved(nearest, clamp(dx), clamp(dy))
                    change.consume()
                }
            },
        ) {
            size = this.size.toIntSize()
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val path = Path().apply {
                moveTo(local.topLeft.x * w, local.topLeft.y * h)
                lineTo(local.topRight.x * w, local.topRight.y * h)
                lineTo(local.bottomRight.x * w, local.bottomRight.y * h)
                lineTo(local.bottomLeft.x * w, local.bottomLeft.y * h)
                close()
            }
            drawPath(path, color = RETAKE_EDGE, style = Stroke(width = 3f))
            // The dimmed outside: the operator can see at a glance how much of the frame will be
            // kept, which is the whole reason a manual crop needs to be shown before it commits.
            drawPath(path, color = RETAKE_EDGE.copy(alpha = 0.12f))

            for (point in local.corners) {
                drawCircle(
                    color = ON_RETAKE,
                    radius = radius.toPx(),
                    center = Offset(point.x * w, point.y * h),
                )
                drawCircle(
                    color = RETAKE_EDGE,
                    radius = radius.toPx(),
                    center = Offset(point.x * w, point.y * h),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

/**
 * Replace one corner, keeping the quad's clockwise corner order.
 *
 * Public because the activity's composable slot records the drag as well as the overlay drawing
 * it — the overlay holds a *local* copy while the finger is down, and the pipeline needs the
 * accepted value. Keeping the corner order in one function is what stops a caller reordering the
 * points and producing a bowtie that `Quad.validate` would (correctly) then reject.
 */
fun Quad.withCorner(index: Int, x: Float, y: Float): Quad {
    val c = corners.toMutableList()
    c[index] = Quad.Point(x, y)
    return Quad(c[0], c[1], c[2], c[3])
}

private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)

private fun androidx.compose.ui.geometry.Size.toIntSize(): IntSize =
    IntSize(width.roundToInt(), height.roundToInt())

private val HANDLE_RADIUS = 16.dp

/**
 * The fraction of the frame around a corner that counts as "grabbing" it.
 *
 * Generous, because the alternative is an operator who cannot grab the handle they are looking
 * at. A wrong grab moves a corner, which the validator catches and the operator can undo; an
 * un-grabbable handle has no recovery at all.
 */
private const val HANDLE_CAPTURE = 0.12f
