package dev.kasoti.mrz

/** Machine-readable-zone document format (ICAO 9303 Part 4). */
enum class MrzFormat(val lineCount: Int, val lineLength: Int) {
    /** Travel document: 2 lines x 44 characters. */
    TD3(lineCount = 2, lineLength = 44),

    /** ID card: 3 lines x 30 characters. */
    TD1(lineCount = 3, lineLength = 30),

    /** Unrecognised shape — parse cannot proceed. */
    UNKNOWN(lineCount = 0, lineLength = 0);

    companion object {
        fun detect(lines: List<String>): MrzFormat = when {
            lines.size == 2 && lines.all { it.length == 44 } -> TD3
            lines.size == 3 && lines.all { it.length == 30 } -> TD1
            else -> UNKNOWN
        }
    }
}

/** A named field that carries its own check digit, so failures attribute precisely. */
enum class MrzField {
    DOCUMENT_NUMBER,
    BIRTH_DATE,
    EXPIRY_DATE,
    PERSONAL_NUMBER,
    OPTIONAL_DATA,
    GIVEN_NAMES,
    NAME,
    COMPOSITE,
}

/**
 * One check-digit assertion: what we parsed, what the arithmetic says, whether it agrees.
 * [expected] is `null` when the span contained a non-MRZ character (undecidable -> fail).
 */
data class MrzCheck(
    val field: MrzField,
    val span: String,
    val expected: Char?,
    val observed: Char?,
) {
    val ok: Boolean get() = expected != null && expected == observed
}

/** Structured name, split on the ICAO `<<` separator. */
data class MrzName(
    val surname: String,
    val givenNames: String,
) {
    /** Display form: fillers become spaces, runs collapse, trimmed. */
    val normalized: String
        get() = listOf(surname, givenNames)
            .joinToString(" ") { it.replace('<', ' ').trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        val EMPTY = MrzName("", "")
    }
}

/**
 * Outcome of parsing a machine-readable zone.
 *
 * Parsing is total: a bad check digit does not abort the parse, it is recorded in [checks]
 * so the fusion layer can attribute it (FR-M2, FUSION.md `R-MATH-01`). Callers must check
 * [allChecksPassed] — a parse that returned fields does not mean the document is sound.
 */
data class MrzResult(
    val format: MrzFormat,
    val rawLines: List<String>,
    val documentNumber: String? = null,
    val nationality: String? = null,
    val birthDate: String? = null,
    val sex: String? = null,
    val expiryDate: String? = null,
    val personalNumber: String? = null,
    val name: MrzName = MrzName.EMPTY,
    val checks: List<MrzCheck> = emptyList(),
    /** Non-fatal structural problems: bad length, illegal character, bad date shape. */
    val structuralErrors: List<String> = emptyList(),
) {
    val allChecksPassed: Boolean get() = checks.all { it.ok }
    val failedChecks: List<MrzCheck> get() = checks.filterNot { it.ok }
    val usable: Boolean get() = format != MrzFormat.UNKNOWN && structuralErrors.isEmpty()
}
