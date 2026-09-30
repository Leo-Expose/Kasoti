package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.Verdict
import dev.kasoti.i18n.Language
import dev.kasoti.i18n.Messages

/**
 * The Post-Console's text view.
 *
 * Two properties matter more than looks. First, the finding table shows the *evidence
 * reference* next to every code, because a secondary reviewer has to be able to go and
 * look at the thing that caused the finding without leaving the terminal. Second, both
 * the threshold version and the fusion rule version are printed on every screening
 * (invariant I3): a verdict that cannot be reproduced against the policy that produced it
 * is not a verdict, it is an anecdote.
 *
 * Every line goes out through [sink], which is what applies the log scrubber.
 */
class ConsoleView(private val sink: (String) -> Unit) {

    fun line(text: String = "") = sink(text)

    fun banner(title: String) {
        line()
        line("=== $title ".padEnd(WIDTH, '='))
    }

    fun field(label: String, value: String) = line("  ${label.padEnd(LABEL_WIDTH)}$value")

    /** The layer table, printed before the verdict so the verdict is never read alone. */
    fun layers(statuses: List<LayerStatus>) {
        line()
        line("  LAYERS")
        line("  " + "layer".padEnd(20) + "state".padEnd(14) + "detail")
        for (status in statuses) {
            line("  " + status.name.padEnd(20) + status.state.name.padEnd(14) + status.detail)
        }
    }

    /**
     * The finding table.
     *
     * Column widths are measured from the data rather than fixed. `FindingCode` names run
     * from `M_OK` to `SYS_UNSUPPORTED_TRACK`, and a hardcoded width long enough for the
     * shortest is useless while a width long enough for the longest wastes half the line —
     * so the table sizes itself and stays aligned whatever the code vocabulary grows into.
     */
    fun verdictTable(verdict: Verdict, findings: List<Finding>) {
        line()
        line("  FINDINGS (${findings.size})")
        if (findings.isEmpty()) {
            line("    (none — every layer that ran agreed)")
            return
        }
        val codeWidth = maxOf("code".length, findings.maxOf { it.code.name.length }) + 2
        val severityWidth = maxOf("severity".length, findings.maxOf { it.severity.name.length }) + 2
        line("  " + "severity".padEnd(severityWidth) + "code".padEnd(codeWidth) + "evidence".padEnd(EVIDENCE_WIDTH) + "detail")
        for (finding in findings) {
            line(
                "  " + finding.severity.name.padEnd(severityWidth) +
                    finding.code.name.padEnd(codeWidth) +
                    finding.evidenceRef.take(EVIDENCE_WIDTH).padEnd(EVIDENCE_WIDTH) +
                    finding.message,
            )
        }
    }

    /**
     * The officer-facing sentence.
     *
     * Both languages are always printed, never just one. A post operator reading a RED at a
     * counter may be more comfortable in either, and choosing between them at print time is
     * a way of failing in front of the person being screened.
     */
    fun headline(verdict: Verdict, findings: List<Finding>) {
        val worst = findings.maxByOrNull { it.severity.ordinal }?.code
        line()
        line("  VERDICT : ${verdict.name}")
        if (verdict.requiresSecondary) {
            line("  ACTION  : SECONDARY REVIEW REQUIRED before any decision")
        } else if (verdict.isRetake) {
            line("  ACTION  : RETAKE — this is not an accusation")
        } else {
            line("  ACTION  : no further action")
        }
        if (worst != null) {
            line("  EN      : ${Messages.of(worst, Language.ENGLISH)}")
            line("  HI      : ${Messages.of(worst, Language.HINDI)}")
        }
    }

    fun policy(thresholdVersion: String, fusionRuleVersion: String, thresholdRunId: String) {
        line()
        line("  POLICY")
        field("thresholds", "$thresholdVersion (run $thresholdRunId)")
        field("fusion rules", fusionRuleVersion)
    }

    fun warnings(warnings: List<String>) {
        if (warnings.isEmpty()) return
        line()
        line("  WARNINGS")
        warnings.forEach { line("    ! $it") }
    }

    fun note(text: String) = line("  · $text")

    fun table(headers: List<String>, rows: List<List<String>>) {
        val widths = headers.indices.map { column ->
            maxOf(
                headers[column].length,
                rows.maxOfOrNull { it.getOrNull(column)?.length ?: 0 } ?: 0,
            )
        }
        line("  " + headers.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString("  ").trimEnd())
        line("  " + widths.joinToString("  ") { "-".repeat(it) })
        for (row in rows) {
            line("  " + row.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString("  ").trimEnd())
        }
    }

    private companion object {
        const val WIDTH = 78
        const val LABEL_WIDTH = 16
        const val EVIDENCE_WIDTH = 22
    }
}

/** A one-line severity badge, used where a table is too wide. */
fun Severity.badge(): String = when (this) {
    Severity.RED -> "RED  "
    Severity.AMBER -> "AMBER"
    Severity.GREY -> "GREY "
    Severity.INFO -> "info "
}
