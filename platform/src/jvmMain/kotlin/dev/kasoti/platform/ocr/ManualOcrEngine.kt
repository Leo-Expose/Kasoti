package dev.kasoti.platform.ocr

import java.awt.image.BufferedImage

/**
 * Officer-typed OCR (BUILD.md §5, D5).
 *
 * This is the reason the desktop build is shippable: the system must not require
 * Tesseract to be installed, and a post operator can always read an MRZ off a passport.
 * The engine reports `manual = true` on everything it returns so the audit record can
 * distinguish a human read from a machine one.
 */
class ManualOcrEngine(
    private val suppliedText: String = "",
    /** Lines the officer typed, one per MRZ row. Normalised to `[A-Z0-9<]` here. */
    private val suppliedLines: List<String> = emptyList(),
) : OcrEngine {

    override val id: String get() = ID

    private val lines: List<String> = when {
        suppliedLines.isNotEmpty() -> suppliedLines
        suppliedText.isNotBlank() -> suppliedText.split('\n')
        else -> emptyList()
    }

    override fun availability(): OcrAvailability = OcrAvailability(
        available = true,
        detail = "manual entry always available (no native dependency)",
    )

    override fun recognize(image: BufferedImage): OcrResult = OcrResult(
        text = lines.joinToString("\n") { normaliseMrz(it) },
        words = lines.map { OcrWord(text = normaliseMrz(it), confidence = OFFICER_CONFIRMED_CONFIDENCE) },
        engineId = ID,
        manual = true,
    )

    companion object {
        const val ID = "manual"

        /**
         * An officer who has just read the row off the document is a stronger evidence
         * source than a 61%-confident OCR pass, which is why the flag is 1.0 rather than
         * something middling. The `manual` flag, not this number, is what the audit reads.
         */
        const val OFFICER_CONFIRMED_CONFIDENCE = 1f

        /**
         * Fold an officer's typing into the MRZ alphabet.
         *
         * Tesseract's whitelist is applied to its own output, but nothing constrains what
         * lands on a console keyboard — so the same normalisation has to happen here or a
         * hand-typed line silently differs from a machine-read one for a reason that has
         * nothing to do with the document.
         */
        fun normaliseMrz(line: String): String = buildString {
            for (ch in line.uppercase()) {
                when {
                    ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
                    ch == '<' -> append('<')
                    ch.isWhitespace() -> append('<')
                    else -> Unit
                }
            }
        }
    }
}
