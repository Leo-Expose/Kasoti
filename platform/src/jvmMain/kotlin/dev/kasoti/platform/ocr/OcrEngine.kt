package dev.kasoti.platform.ocr

/**
 * One recognised token with its geometry and confidence.
 *
 * Geometry is kept because MRZ post-correction (DESIGN.md §5) is conf-weighted per field,
 * and because an officer comparing the OCR line against the card needs to see *which* line
 * the engine read. Confidence is Tess4J's 0..100 on the native side, normalised to 0..1 here
 * so the rest of the system never has to remember which convention it is holding.
 */
data class OcrWord(
    val text: String,
    /** 0..1, already normalised from whichever engine produced it. */
    val confidence: Float,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * The outcome of one OCR pass over a crop.
 *
 * [manual] is not cosmetic. Manual MRZ entry is P0-acceptable (D5) and the officer-confirmed
 * flag has to survive into the decision record, because "the officer read the passport" and
 * "Tesseract read the passport with 61% confidence" are different evidence and only one of
 * them is greppable in an audit six months later.
 */
data class OcrResult(
    val text: String,
    val words: List<OcrWord>,
    val engineId: String,
    val manual: Boolean,
) {
    val meanConfidence: Float
        get() = if (words.isEmpty()) 0f else words.sumOf { it.confidence.toDouble() }.toFloat() / words.size

    companion object {
        val EMPTY = OcrResult("", emptyList(), "none", manual = false)
    }
}

/** Whether an engine can actually run on this machine right now. */
data class OcrAvailability(val available: Boolean, val detail: String)

/**
 * The OCR seam (DESIGN.md §3).
 *
 * An engine that cannot run must report so through [availability] rather than throwing from
 * [recognize]: the desktop console has to keep screening on a machine where Tesseract was
 * never installed, which is the normal state of a fresh post machine (BUILD.md §5).
 */
interface OcrEngine {
    /** Stable identifier recorded alongside the result, e.g. `tess4j-mrz` or `manual`. */
    val id: String

    fun availability(): OcrAvailability

    /** @return the recognised text, or [OcrResult.EMPTY] when the engine cannot run. */
    fun recognize(image: java.awt.image.BufferedImage): OcrResult
}
