package dev.kasoti.desktop

import dev.kasoti.audit.DecisionRecord
import dev.kasoti.desktop.bundle.CaseBundleWriter
import dev.kasoti.desktop.screen.Decision
import dev.kasoti.desktop.screen.LayerStatus
import dev.kasoti.desktop.screen.ScreeningResult
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import java.nio.file.Files
import java.nio.file.Path

/**
 * `kasoti export` — the case bundle: `case.json` + `crops/` + `audit.txt` (NFR-P2).
 *
 * Two things happen here that are worth stating plainly.
 *
 * The bundle is written from the *audit record*, not from a cached copy of the screening.
 * The chain is the authoritative account of what was decided, so an export that could
 * disagree with it would be a second source of truth.
 *
 * And the live face crop is not in the default path. NFR-P2 says raw live-face data does not
 * leave the device unless a supervisor decides it does, so the flag exists, is named
 * `--include-live-face`, and prints as an explicit override in the console output rather
 * than blending into a list of entries.
 */
fun Console.export(line: CommandLine): CommandResult {
    line.rejectUnknown(setOf("case", "out", "include-live-face"))
    val caseId = line.required("case")
    val out = Path.of(line.option("out", "cases/$caseId.zip"))
    if (!Files.isDirectory(home.caseDir(caseId))) {
        return CommandResult(ConsolePolicy.Exit.USAGE, "unknown case '$caseId' (looked in ${home.caseDir(caseId)})")
    }
    val records = audit.all().filter { it.caseId == caseId }
    if (records.isEmpty()) {
        return CommandResult(ConsolePolicy.Exit.USAGE, "no audit records for case '$caseId'")
    }
    val latest = records.last()

    val liveFace = if (line.flag("include-live-face")) {
        val stored = home.caseDir(caseId).resolve("live-face.bin")
        if (Files.isRegularFile(stored)) {
            Files.readAllBytes(stored)
        } else {
            view.line()
            view.note("--include-live-face was passed but this case holds no live-face capture; nothing added")
            null
        }
    } else {
        null
    }

    // TODO(M2,@vision): attach the macro crops. A case does not yet store its photo/text
    // zones as images. The bundle already reserves crops/ so the layout will not change
    // when they arrive, and an empty directory is a smaller lie than a wrong one.
    val receipt = CaseBundleWriter(imaging).write(
        result = replay(latest, caseId),
        record = latest,
        auditExcerpt = audit.excerpt(),
        target = out,
        includeLiveFace = liveFace,
    )

    view.banner("Case bundle")
    view.table(
        listOf("field", "value"),
        listOf(
            listOf("case", caseId),
            listOf("records in chain", records.size.toString()),
            listOf("written", receipt.path.toString()),
            listOf("size", "${receipt.sizeBytes} bytes (budget ${ConsolePolicy.BUNDLE_SIZE_BUDGET_BYTES})"),
            listOf("entries", receipt.entries.joinToString(", ")),
            listOf(
                "live face",
                if (receipt.containsLiveFace) "INCLUDED (explicit override, NFR-P2)" else "excluded (NFR-P2 default)",
            ),
        ),
    )
    return CommandResult(ConsolePolicy.Exit.OK, "bundle written to ${receipt.path}")
}

/**
 * Rebuild the minimum [ScreeningResult] a bundle needs, from the audit record.
 *
 * Findings are re-derived from the recorded *codes* rather than replayed from stored prose,
 * so a bundle always resolves through the current i18n table. A bundle that embedded the
 * English and Hindi strings as they were at screening time would be half-unreadable after
 * a wording change, and there would be no way to tell that from a translation bug.
 */
private fun Console.replay(record: DecisionRecord, caseId: String): ScreeningResult {
    val findings = record.findingCodes.map { code ->
        Finding(
            code = runCatching { FindingCode.valueOf(code) }.getOrElse { FindingCode.SYS_CAPTURE_FAILED },
            severity = Severity.INFO,
            evidenceRef = "audit/$caseId",
            message = "replayed from the audit record",
        )
    }
    val verdict = runCatching { Verdict.valueOf(record.verdict) }.getOrDefault(Verdict.GREY)
    return ScreeningResult(
        caseId = caseId,
        track = Track.UNKNOWN,
        image = Path.of("."),
        evidence = Evidence(track = Track.UNKNOWN, demoMode = storedDemoFlag(caseId)),
        decision = Decision(verdict, findings),
        thresholdVersion = record.thresholdVersion,
        fusionRuleVersion = record.fusionRuleVersion,
        layers = listOf(LayerStatus("replay", LayerStatus.State.SKIPPED, "bundle export replays the audit record")),
        mrz = null,
        warnings = listOf("crops/ is empty in this build — see TODO(M2,@vision)"),
    )
}
