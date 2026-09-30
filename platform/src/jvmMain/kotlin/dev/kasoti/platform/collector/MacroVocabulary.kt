package dev.kasoti.platform.collector

import java.nio.file.Path

/**
 * The D-MACRO collection vocabulary (DATA.md §3).
 *
 * These labels are the SVM's classes in `dev.kasoti.factory.ProcessLabel`, duplicated here
 * as a *file-system* enum so the collector can validate an operator's `--process` argument
 * before it creates a directory tree that the eval corpus will not recognise. The mapping
 * is by name; there is no third vocabulary.
 */
enum class MacroProcess(val label: String) {
    OFFSET("OFFSET"),
    INKJET("INKJET"),
    LASER("LASER"),
    DYESUB("DYESUB"),
    SCREEN("SCREEN"),
    PHOTOCOPY("PHOTOCOPY"),

    /** The reject pile. Kept, not deleted: negatives are training data too (DATA.md §3). */
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        private val byLabel = entries.associateBy { it.label }

        /** @return the process for [raw], or `null` when it is not one of the seven labels. */
        fun fromLabelOrNull(raw: String): MacroProcess? = byLabel[raw.trim().uppercase()]

        fun require(raw: String): MacroProcess = fromLabelOrNull(raw)
            ?: throw IllegalArgumentException(
                "unknown process label '$raw'; expected one of ${entries.joinToString(",") { it.label }}",
            )
    }
}

/** Capture lighting (DATA.md §3). Not free text: it is a path segment. */
enum class MacroLight(val label: String) {
    SUN("sun"),
    SHADE("shade"),
    TORCH("torch"),
    ;

    companion object {
        fun fromLabelOrNull(raw: String): MacroLight? =
            entries.firstOrNull { it.label == raw.trim().lowercase() }

        fun require(raw: String): MacroLight = fromLabelOrNull(raw)
            ?: throw IllegalArgumentException(
                "unknown light '$raw'; expected one of ${entries.joinToString(",") { it.label }}",
            )
    }
}

/** What the collector was told about the capture, before it looks at any pixels. */
data class MacroCaptureRequest(
    /** Source image. A file path is the supported and preferred source (DATA.md §1: no PII pixels). */
    val sourceImage: Path,
    /**
     * Stable operator-chosen identifier for the *physical item* the texture came from — a
     * teammate's own card, or `specimen-laser-a`. It must not encode a person, because it
     * becomes a path segment and a manifest column.
     */
    val sourceId: String,
    val process: MacroProcess,
    val light: MacroLight,
    /** Whether the shroud clip was seated. A `false` is a legitimate label, not a defect. */
    val clip: Boolean,
    /** Post device id, `post3-ph1` style (DESIGN.md §4). */
    val deviceId: String,
    /** `device_calib.json` id from the DATA.md §5 calibration routine. */
    val calibId: String,
    /** Region of the frame holding the texture, in normalised coordinates. */
    val region: dev.kasoti.platform.imaging.NormRect,
)

/** What the collector produced, for the manifest row and the eval harness. */
data class MacroSample(
    /** Path relative to the corpus root, in DATA.md §3 layout. */
    val relativePath: String,
    val absolutePath: Path,
    val fileName: String,
    /** The single corpus-wide index this sample appended a row to. */
    val manifestFile: Path,
    val manifestRow: String,
    val patchWidth: Int,
    val patchHeight: Int,
    val grayMean: Float,
    val grayStdDev: Float,
)
