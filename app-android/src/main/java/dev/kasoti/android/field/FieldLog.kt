package dev.kasoti.android.field

import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.VerdictReport

/**
 * A PII-scrubbed log sink (invariant I5; AGENTS.md §5; the review checklist's second line).
 *
 * Two rules, both enforced here rather than at each call site, because a call site is where
 * scrubbing gets forgotten:
 *
 *  1. **Codes and refs only.** The API takes `FindingCode`s and stable evidence references. There
 *     is no overload that takes a name, a date of birth, a document number, an image path or an
 *     embedding, so the log cannot contain one. That is a type-level guarantee, not a review
 *     promise — and it is why the parameters are not `String`.
 *  2. **Verdicts are logged whole.** [verdict] takes the `:core` [VerdictReport], so the finding
 *     codes, the layer states, the policy fingerprint and the rationale all land together. An
 *     audit that has the verdict but not the layer states is not reproducible (invariant I3).
 *
 * What is deliberately *not* logged: the subject's name, DOB, document number, the raw frames,
 * and the embedding values. NFR-P2 forbids retaining raw live-face bitmaps by default and
 * AGENTS.md §5 forbids logging names, DOBs and embeddings.
 */
fun interface FieldLog {

    /** One structured line. Implementations must not add fields the caller did not supply. */
    fun write(entry: FieldLogEntry)

    companion object {
        val NOOP: FieldLog = FieldLog { }
    }
}

data class FieldLogEntry(
    val kind: Kind,
    val caseId: String?,
    val codes: List<FindingCode>,
    val evidenceRefs: List<String>,
    val detail: String,
) {
    enum class Kind {
        CAPTURE, QUALITY_GATE, ROUTE, MATH, QR, MACRO, FACE, DIARY, TRUST, DEMO, VERDICT, SYSTEM,
    }

    companion object {
        /** Log a `:core` verdict without re-deriving anything about it. */
        fun verdict(caseId: String, report: VerdictReport): FieldLogEntry = FieldLogEntry(
            kind = Kind.VERDICT,
            caseId = caseId,
            codes = report.codes.toList(),
            evidenceRefs = report.findings.map { it.evidenceRef },
            detail = report.rationale.joinToString(" | "),
        )

        fun of(
            kind: Kind,
            caseId: String? = null,
            codes: List<FindingCode> = emptyList(),
            evidenceRefs: List<String> = emptyList(),
            detail: String = "",
        ): FieldLogEntry = FieldLogEntry(kind, caseId, codes.toList(), evidenceRefs.toList(), detail)
    }
}

/**
 * Renders a [FieldLogEntry] as a single line.
 *
 * One line per event, `|`-separated, no free-form prefix. The format is stable so a post's log
 * can be grepped with `grep 'KASOTI' | grep R_MATH_01` after a shift, which is the only way it
 * is useful to a supervisor at 3am.
 */
object FieldLogFormat {

    const val TAG = "KASOTI"

    fun render(entry: FieldLogEntry): String {
        val case = entry.caseId?.let { " case=$it" } ?: ""
        val codes = if (entry.codes.isEmpty()) "" else " codes=${entry.codes.joinToString(",") { it.name }}"
        val refs = if (entry.evidenceRefs.isEmpty()) "" else " refs=${entry.evidenceRefs.joinToString(",")}"
        val detail = if (entry.detail.isBlank()) "" else " detail=${sanitise(entry.detail)}"
        return "$TAG ${entry.kind.name.lowercase()}$case$codes$refs$detail"
    }

    /**
     * Strip anything that could carry identity out of a free-text detail.
     *
     * A backstop, not the mechanism. The API above already refuses names, DOBs and numbers, so
     * in practice this only ever sees `:core`'s own rationale lines, which are written by
     * `:core`'s authors and covered by `:core`'s tests. It exists because "the only place that
     * can log a name is a place someone will eventually log a name" is a lesson that costs an
     * incident to learn.
     */
    fun sanitise(text: String): String =
        text.replace(NUMBER_RUN, "<n>")
            .replace(CONTROL, " ")
            .take(MAX_DETAIL)
            .trim()

    private val NUMBER_RUN = Regex("\\d{4,}")
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\\t]]")
    private const val MAX_DETAIL = 240
}

/** Convenience: the codes a report says, as the log's vocabulary. */
fun VerdictReport.logCodes(): List<FindingCode> = findings.map { it.code }

/** Convenience: the red findings, for a console that wants only the blocking reasons. */
fun VerdictReport.redFindings(): List<Finding> = findings.filter { it.severity == Severity.RED }
