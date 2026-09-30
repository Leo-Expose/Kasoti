package dev.kasoti.platform.ocr

import java.awt.image.BufferedImage

/**
 * Chooses an OCR engine, preferring the machine one and never failing.
 *
 * The fallback is a policy, not an error path: D5 makes manual MRZ entry P0-acceptable, and
 * a post that cannot screen because a package manager was forgotten is not a working post.
 * Callers get a result either way and the `engineId` on the result records which was used,
 * so an audit six months later can tell a human read from a machine one.
 */
class OcrEngines(
    private val preferred: OcrEngine,
    private val fallback: OcrEngine = ManualOcrEngine(),
) {

    /** The engine that will actually be used for the next [recognize]. */
    fun active(): OcrEngine = if (preferred.availability().available) preferred else fallback

    fun recognize(image: BufferedImage): OcrResult = active().recognize(image)

    /** A one-line description for the console banner, e.g. `tess4j-mrz` or `manual (fallback)`. */
    fun describe(): String {
        val status = preferred.availability()
        return if (status.available) {
            "${preferred.id} (${status.detail})"
        } else {
            "${fallback.id} (fallback: ${status.detail})"
        }
    }
}
