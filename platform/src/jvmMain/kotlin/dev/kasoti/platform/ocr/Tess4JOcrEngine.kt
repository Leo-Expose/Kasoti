package dev.kasoti.platform.ocr

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage

/**
 * Desktop MRZ OCR via Tess4J (BUILD.md §5, D5).
 *
 * The engine is configured once, for one job: reading machine-readable zones. Two settings
 * do all the work.
 *
 * * `--psm 6` (`PSM_SINGLE_BLOCK`) treats the crop as a single uniform block of text. MRZ
 *   lines are a fixed-pitch grid, and the default page-layout analysis spends most of a
 *   44-character crop trying to decide whether it is a page. Forcing a single block both
 *   matches the real layout and removes a segmentation heuristic from the failure surface.
 * * The `A-Z0-9<` whitelist removes the character classes an MRZ can never contain. Without
 *   it a speck of dust becomes a `€`, the whitelist rejects it, and the check digit that
 *   followed it shifts by one — producing a *failed check digit on a perfectly valid
 *   passport*, which is the single most alarming wrong answer this layer can give.
 *
 * Tesseract is an optional native dependency. A post machine without `libtesseract` must
 * still screen, so [availability] probes once and [recognize] degrades to [OcrResult.EMPTY]
 * instead of throwing; the caller falls back to [ManualOcrEngine].
 */
class Tess4JOcrEngine(
    /** Directory holding `tessdata/`. Read from `tess.data=` in `local.properties` (BUILD.md §1). */
    private val dataPath: String? = null,
    private val language: String = DEFAULT_LANGUAGE,
) : OcrEngine {

    override val id: String get() = ID

    /** The exact `--psm` / whitelist pair from BUILD.md §5, exposed so the console can print it. */
    val configurationLine: String get() = "--psm $PAGE_SEG_MODE -c tessedit_char_whitelist=$MRZ_WHITELIST"

    private var probe: OcrAvailability? = null

    /**
     * Whether this machine can run Tesseract at all.
     *
     * The result is memoised, and for a good reason beyond speed: once `TessAPI`'s static
     * initialiser has failed, the JVM marks the class erroneous and *every* later access
     * throws `NoClassDefFoundError`. Probing on each call would therefore turn one missing
     * `libtesseract` into a fresh failure on every single document, with a stack trace
     * pointing at a class the operator has never heard of.
     *
     * The catch is on [Throwable], not [Exception], and that is not sloppiness: the failure
     * modes of a missing JNI library surface as `UnsatisfiedLinkError`,
     * `NoClassDefFoundError` and `ExceptionInInitializerError`, none of which are
     * `RuntimeException`. Catching only the checked-looking ones would let the first failure
     * escape into the screening loop — which is the exact outcome D5 exists to prevent.
     */
    override fun availability(): OcrAvailability {
        probe?.let { return it }
        val result = try {
            val api = Tesseract()
            dataPath?.let { api.setDatapath(it) }
            api.setLanguage(language)
            api.doOCR(PROBE_IMAGE)
            OcrAvailability(
                available = true,
                detail = "tesseract ready (datapath=${dataPath ?: "default"}, lang=$language, $configurationLine)",
            )
        } catch (e: Throwable) {
            OcrAvailability(false, "${e.javaClass.simpleName}: ${e.message ?: "tesseract unavailable"}")
        }
        probe = result
        return result
    }

    override fun recognize(image: BufferedImage): OcrResult {
        if (!availability().available) return OcrResult.EMPTY
        return try {
            val api = Tesseract()
            dataPath?.let { api.setDatapath(it) }
            api.setLanguage(language)
            api.setPageSegMode(PAGE_SEG_MODE)
            api.setVariable(WHITELIST_VARIABLE, MRZ_WHITELIST)

            val text = api.doOCR(image)
            val words = api.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)
                .orEmpty()
                .mapNotNull { word ->
                    val value = word.text?.trim().orEmpty()
                    if (value.isEmpty()) return@mapNotNull null
                    val box = word.boundingBox
                    OcrWord(
                        text = value,
                        confidence = (word.confidence / 100f).coerceIn(0f, 1f),
                        x = box?.x ?: 0,
                        y = box?.y ?: 0,
                        width = box?.width ?: 0,
                        height = box?.height ?: 0,
                    )
                }
            OcrResult(text = text.trim(), words = words, engineId = ID, manual = false)
        } catch (e: Throwable) {
            // A malformed crop is a bad capture, not a crash: the caller turns this into
            // G_OCRLOW and asks for a retake. Anything the JNI layer can throw lands here.
            OcrResult(text = "", words = emptyList(), engineId = ID, manual = false)
        }
    }

    companion object {
        const val ID = "tess4j-mrz"

        /** `--psm 6` (BUILD.md §5). */
        const val PAGE_SEG_MODE = ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK

        const val DEFAULT_LANGUAGE = "eng"

        /**
         * The MRZ alphabet. `<` is the filler that makes every field a fixed width, so it
         * is a *legal* character and excluding it would silently shorten every name.
         */
        const val MRZ_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"

        const val WHITELIST_VARIABLE = "tessedit_char_whitelist"

        /**
         * A 1×1 image used to force native initialisation during the availability probe.
         *
         * Probing on construction alone would be cheaper, but the Tesseract constructor
         * does not touch the native library — the first `doOCR` does. Probing with a real
         * call is the only way to find out honestly whether this machine can OCR at all.
         */
        private val PROBE_IMAGE: BufferedImage =
            BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY)
    }
}
