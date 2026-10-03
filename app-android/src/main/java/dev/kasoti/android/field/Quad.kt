package dev.kasoti.android.field

import dev.kasoti.factory.GrayImage
import kotlin.math.abs

/**
 * Four normalised corners of a document, clockwise from top-left.
 *
 * Normalised to 0..1 of the frame so a crop recipe survives a re-capture at a different
 * resolution or aspect ratio — the same reason `:platform`'s `NormRect` is normalised.
 */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {
    data class Point(val x: Float, val y: Float)

    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Clockwise, in the order the deskew sampler walks them. */
    fun asPairs(): List<Pair<Float, Float>> = corners.map { it.x to it.y }

    val areaFraction: Float
        get() = 0.5f * abs(
            shoelace(topLeft, topRight) + shoelace(topRight, bottomRight) +
                shoelace(bottomRight, bottomLeft) + shoelace(bottomLeft, topLeft),
        )

    /** Width/height ratio, measured on the horizontal edges. An ID-1 card is 85.6 × 54 mm. */
    val aspect: Float
        get() {
            val w = distance(topLeft, topRight)
            val d = distance(bottomLeft, bottomRight)
            val h = distance(topLeft, bottomLeft) + distance(topRight, bottomRight)
            val width = (w + d) / 2f
            val height = h / 2f
            return if (height <= 0f) 0f else width / height
        }

    /**
     * Whether the corners form a usable, non-degenerate, non-self-intersecting quad.
     *
     * A malformed quad is not a rendering problem — it produces a smeared crop whose OCR output
     * is garbage, and garbage MRZ output is indistinguishable from a forger's bad MRZ. So the
     * manual 4-drag-handle fallback validates before it commits, and an invalid shape snaps
     * back rather than being accepted "and we'll see".
     */
    /**
     * The order matters: structural faults are diagnosed before size.
     *
     * A self-intersecting quad has a shoelace area near zero, so an area check first reports
     * `TOO_SMALL` for a shape that is actually inverted — and the operator is told to move the
     * handles closer together, which is not the problem. Likewise a handle dragged off the
     * frame should be named as such, not as "too large". Most-specific-first is the only order
     * in which the verdict is useful to whoever has to fix the crop.
     */
    fun validate(policy: QuadPolicy = QuadPolicy()): QuadVerdict {
        if (corners.any { it.x < -EDGE_SLACK || it.x > 1f + EDGE_SLACK || it.y < -EDGE_SLACK || it.y > 1f + EDGE_SLACK }) {
            return QuadVerdict.OUTSIDE_FRAME
        }
        if (!isConvex()) return QuadVerdict.NOT_CONVEX
        if (areaFraction < policy.minAreaFraction) return QuadVerdict.TOO_SMALL
        if (aspect < policy.minAspect || aspect > policy.maxAspect) return QuadVerdict.BAD_ASPECT
        return QuadVerdict.OK
    }

    fun isConvex(): Boolean = crossProducts().all { it > 0f } || crossProducts().all { it < 0f }

    private fun crossProducts(): List<Float> = corners.indices.map { i ->
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        val c = corners[(i + 2) % 4]
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }

    private fun shoelace(a: Point, b: Point): Float = a.x * b.y - b.x * a.y

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** A handle may sit this far outside the frame before the quad is rejected. */
        const val EDGE_SLACK = 0.02f

        val FULL_FRAME = Quad(
            Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f),
        )
    }
}

enum class QuadVerdict { OK, TOO_SMALL, BAD_ASPECT, NOT_CONVEX, OUTSIDE_FRAME }

/**
 * The bounds a document quad must satisfy.
 *
 * The aspect window is wide on purpose: ID-1 cards (1.586), passports (1.42), a folded A4 sheet
 * photographed at an angle, and a paper ID of no standard shape all have to be accepted, and a
 * narrow window would reject the exact documents the field app exists for. The area bounds
 * exist to stop a quad that is one corner of the frame — which "works", produces a plausible
 * crop, and OCRs nothing.
 */
/**
 * @param minAreaFraction the floor that stops a one-corner "crop" being accepted. There is
 *   deliberately no maximum: a quad cannot cover more than the whole frame, and a full-frame
 *   quad is a legitimate answer — it is what the manual fallback starts from when the detector
 *   finds nothing. Whether the *auto* path should ever propose it is [AutoQuadDetector]'s
 *   question, not the validator's.
 */
data class QuadPolicy(
    val minAreaFraction: Float = 0.06f,
    val minAspect: Float = 0.35f,
    val maxAspect: Float = 3.2f,
)

/**
 * The auto quad proposer (FR-C2's "auto" half).
 *
 * ## What it does and does not claim
 *
 * It segments the frame, takes the largest connected foreground region, and fits a
 * quadrilateral to it by the four *diagonal extremes* — the classic rotating-calipers result
 * that for a convex, roughly axis-aligned shape the minimum-area enclosing quad has its corners
 * at the extremes of `x+y`, `x−y`, `−x+y`, `−x−y`.
 *
 * It is therefore exact for a rectangle held square-ish to the frame and **approximate for a
 * document held at a real angle** — which is the normal case. That is acceptable precisely
 * because FR-C2 requires a manual 4-drag-handle fallback, and because the proposal is scored
 * and the operator is shown the result *before* it is committed. It does not claim to be
 * OpenCV's contour-approximation; DESIGN.md D4 rules OpenCV out of the build and this is the
 * ~80 lines that decision costs.
 *
 * When the proposal is not confident enough it returns `null` and the UI opens in manual mode
 * directly. A wrong auto-crop that the operator has to notice and fix is worse than being
 * asked to drag four handles.
 */
object AutoQuadDetector {

    /**
     * @param gray a downscaled grayscale frame.
     * @return the proposed quad, or `null` when nothing document-like was found.
     */
    fun propose(gray: GrayImage, policy: QuadPolicy = QuadPolicy()): Quad? {
        val mask = segment(gray) ?: return null
        val quad = diagonalExtremes(gray, mask) ?: return null
        // The extremes are the true minimum-area quad only for a convex, roughly axis-aligned
        // blob. Anything the validator rejects is exactly the case a human should be asked
        // about, so the proposal is dropped rather than shown as a bad crop.
        return if (quad.validate(policy) == QuadVerdict.OK) quad else null
    }

    /**
     * Otsu threshold, then keep the class the frame corners are *not* in.
     *
     * The document is found by elimination rather than by absolute brightness: on a table it is
     * lighter, on a dark cloth it is darker, and a rule that assumed either would fail half the
     * deployments. The corners vote, and if the histogram splits badly the proposal is refused
     * rather than guessed at.
     */
    internal fun segment(gray: GrayImage): BooleanArray? {
        val bins = HISTOGRAM_BINS
        val histogram = IntArray(bins)
        for (p in gray.pixels) {
            val bin = (p * (bins - 1)).toInt().coerceIn(0, bins - 1)
            histogram[bin]++
        }
        val level = otsu(histogram, gray.pixels.size)
        val total = gray.pixels.size
        val dark = histogram.take(level + 1).sum()
        // Reject when either class is a sliver: a sliver means the threshold landed inside the
        // document rather than on its boundary, and the "largest region" would be a shadow.
        if (dark < total * MIN_CLASS_FRACTION || dark > total * (1f - MIN_CLASS_FRACTION)) return null

        // The background class is whichever one the frame corners fall in. Compare *bin
        // indices*, not the 0..1 sample values: mixing the two scales makes a light table
        // look like a dark one and silently segments the background instead of the document.
        val cornerBin = (cornerMean(gray) * (bins - 1)).toInt().coerceIn(0, bins - 1)
        val documentIsDark = cornerBin > level
        return BooleanArray(total) { i ->
            val bin = (gray.pixels[i] * (bins - 1)).toInt().coerceIn(0, bins - 1)
            if (documentIsDark) bin <= level else bin > level
        }
    }

    /** Mean of the four frame corners: the most reliable sample of "the background". */
    private fun cornerMean(gray: GrayImage): Float {
        val w = gray.width
        val h = gray.height
        val points = listOf(0, w - 1, (h - 1) * w, (h - 1) * w + (w - 1))
        return points.sumOf { gray.pixels[it].toDouble() }.toFloat() / points.size
    }

    /** Otsu's between-class-variance threshold, over a histogram of 0..255 bins. */
    internal fun otsu(histogram: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in histogram.indices) sum += i.toDouble() * histogram[i]
        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0
        var bestVariance = -1.0
        for (t in 0 until (histogram.size - 1)) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toDouble() * histogram[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val delta = meanBackground - meanForeground
            val between = weightBackground.toDouble() * weightForeground * delta * delta
            if (between > bestVariance) {
                bestVariance = between
                best = t
            }
        }
        return best
    }

    /**
     * The four diagonal extremes of the largest connected foreground region.
     *
     * Picking the largest *connected* region first is what keeps a shadow, a hand, or a printed
     * logo from becoming the document. The extremes themselves are exact for a convex,
     * roughly axis-aligned shape, and approximate for one held at a real angle — which is the
     * normal case and the reason the manual fallback exists.
     *
     * @return corners clockwise from top-left, normalised to 0..1, or `null` when no region is
     *   document-shaped.
     */
    internal fun diagonalExtremes(gray: GrayImage, mask: BooleanArray): Quad? {
        val region = largestComponent(gray, mask) ?: return null
        var minSum = Float.MAX_VALUE
        var minSumX = 0f
        var minSumY = 0f
        var maxSum = -Float.MAX_VALUE
        var maxSumX = 0f
        var maxSumY = 0f
        var minDiff = Float.MAX_VALUE
        var minDiffX = 0f
        var minDiffY = 0f
        var maxDiff = -Float.MAX_VALUE
        var maxDiffX = 0f
        var maxDiffY = 0f

        for (index in region) {
            val x = (index % gray.width).toFloat() / (gray.width - 1).coerceAtLeast(1)
            val y = (index / gray.width).toFloat() / (gray.height - 1).coerceAtLeast(1)
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; minSumX = x; minSumY = y }
            if (sum > maxSum) { maxSum = sum; maxSumX = x; maxSumY = y }
            if (diff < minDiff) { minDiff = diff; minDiffX = x; minDiffY = y }
            if (diff > maxDiff) { maxDiff = diff; maxDiffX = x; maxDiffY = y }
        }

        val spanX = maxOf(maxSumX, maxDiffX) - minOf(minSumX, minDiffX)
        val spanY = maxOf(maxSumY, maxDiffY) - minOf(minSumY, minDiffY)
        if (spanX < MIN_SPAN || spanY < MIN_SPAN) return null
        if (spanX > MAX_SPAN || spanY > MAX_SPAN) return null

        // Clockwise from top-left. `x−y` min is top-left, `x+y` max is top-right,
        // `x−y` max is bottom-right, `x+y` min is bottom-left.
        return Quad(
            Quad.Point(minDiffX, minDiffY),
            Quad.Point(maxSumX, maxSumY),
            Quad.Point(maxDiffX, maxDiffY),
            Quad.Point(minSumX, minSumY),
        )
    }

    /**
     * The largest 4-connected foreground component, as indices.
     *
     * An explicit growable stack rather than recursion: a 640x480 analysis frame is 300k
     * pixels, and a recursive flood fill on a mid-range Android device is a stack-overflow
     * crash on a perfectly plausible input — which is not an acceptable failure mode for the
     * auto-crop of somebody's passport.
     */
    private fun largestComponent(gray: GrayImage, mask: BooleanArray): IntArray? {
        val width = gray.width
        val height = gray.height
        val seen = BooleanArray(mask.size)
        var best: IntArray? = null
        var bestSize = 0
        var stack = IntArray(INITIAL_STACK)
        var region = IntArray(INITIAL_STACK)

        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            var used = 0
            var size = 0
            stack[top++] = start
            seen[start] = true

            while (top > 0) {
                val index = stack[--top]
                if (used == region.size) region = region.copyOf(region.size * 2)
                region[used++] = index
                size++

                val x = index % width
                val y = index / width
                // Four neighbours means at most four pushes, so reserving the headroom up
                // front makes the per-neighbour path a bounds-free store.
                if (top + 4 > stack.size) stack = stack.copyOf(stack.size * 2)
                top += if (x > 0 && claim(index - 1, mask, seen, stack, top)) 1 else 0
                top += if (x < width - 1 && claim(index + 1, mask, seen, stack, top)) 1 else 0
                top += if (y > 0 && claim(index - width, mask, seen, stack, top)) 1 else 0
                top += if (y < height - 1 && claim(index + width, mask, seen, stack, top)) 1 else 0
            }

            if (size > bestSize) {
                bestSize = size
                best = region.copyOf(used)
            }
        }
        if (bestSize < gray.pixels.size * MIN_REGION_FRACTION) return null
        return best
    }

    /**
     * Marks a neighbour and stores it in [slot] when it is foreground and unvisited.
     *
     * @return `true` when the pixel was claimed, so the caller can advance the stack top.
     */
    private fun claim(neighbour: Int, mask: BooleanArray, seen: BooleanArray, stack: IntArray, slot: Int): Boolean {
        if (!mask[neighbour] || seen[neighbour]) return false
        seen[neighbour] = true
        stack[slot] = neighbour
        return true
    }

    /** Initial flood-fill stack and region buffer, in pixels. Doubled on demand. */
    const val INITIAL_STACK = 4_096

    /**
     * Above this area fraction an auto proposal is treated as "no document found".
     *
     * A card fills most of a close-up photo, but not all of it, and a proposal that swallows
     * the frame has segmented the table rather than the card.
     */
    const val AUTO_MAX_AREA = 0.95f

    /** A region must cover this much of the frame to be a document and not a sticker. */
    const val MIN_REGION_FRACTION = 0.05f

    /** Histogram resolution. 256 is the input's own quantisation, so nothing is lost. */
    const val HISTOGRAM_BINS = 256

    /** Neither Otsu class may be a sliver, or the threshold cut through the document. */
    const val MIN_CLASS_FRACTION = 0.04f

    /** Region span on each axis, as a fraction of the frame. */
    const val MIN_SPAN = 0.20f
    const val MAX_SPAN = 0.98f
}
