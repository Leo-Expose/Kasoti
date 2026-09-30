package dev.kasoti.desktop

import java.nio.file.Files

/**
 * `kasoti override` — record a supervisor's decision over the system's (FR-S4).
 *
 * The reason vocabulary is closed and enforced here rather than left to the operator's
 * discretion, because "a supervisor said it was fine" is not a reason code and the audit
 * needs to be able to count how often each one is used. `SUP_OTHER_JUSTIFIED` additionally
 * demands a written note, which is the escape hatch kept honest by having to justify itself
 * in prose.
 *
 * Note what the record does *not* contain: a free-text justification field, and any way to
 * overwrite the original decision. The chain is append-only, so an override adds a record
 * rather than editing one, and the original verdict stays reconstructable.
 */
fun Console.override(line: CommandLine): CommandResult {
    line.rejectUnknown(setOf("case", "verdict", "reason", "note", "by", "device"))
    val caseId = line.required("case")
    val verdict = line.required("verdict").uppercase()
    if (verdict !in listOf("GREEN", "AMBER", "RED", "GREY")) {
        return CommandResult(ConsolePolicy.Exit.USAGE, "verdict must be GREEN, AMBER, RED or GREY")
    }
    val reason = line.required("reason").uppercase()
    if (reason !in ConsolePolicy.OVERRIDE_REASONS) {
        return CommandResult(
            ConsolePolicy.Exit.REFUSED,
            "reason '$reason' is not an accepted code; expected one of " +
                ConsolePolicy.OVERRIDE_REASONS.joinToString(", "),
        )
    }
    val note = line.option("note").orEmpty()
    if (reason == ConsolePolicy.REASON_REQUIRING_NOTE && note.isBlank()) {
        return CommandResult(
            ConsolePolicy.Exit.REFUSED,
            "$reason requires --note explaining what the supervisor actually checked",
        )
    }
    val operator = line.required("by")

    home.ensure()
    val prior = audit.all().lastOrNull { it.caseId == caseId }
        ?: return CommandResult(
            ConsolePolicy.Exit.REFUSED,
            "no decision recorded for case '$caseId'; screen it first or check the case id",
        )

    val record = prior.copy(
        id = "rec_${Ids.ulid(clock.millis())}",
        timestampUtc = clock.nowIso(),
        verdict = verdict,
        deviceId = line.option("device", prior.deviceId),
        overriddenBy = operator,
        overrideReasonCode = reason,
    )
    val hash = audit.append(record)
    storeOverrideNote(caseId, reason, note, operator, hash)

    view.banner("Override recorded")
    view.table(
        listOf("field", "value"),
        listOf(
            listOf("case", caseId),
            listOf("previous", prior.verdict),
            listOf("new", verdict),
            listOf("operator", operator),
            listOf("reason", reason),
            listOf("note", if (note.isBlank()) "-" else LogScrubber.scrub(note)),
            listOf("auditTip", hash),
        ),
    )
    return CommandResult(ConsolePolicy.Exit.OK, "override recorded for $caseId")
}

/**
 * Keep the written note beside the case, scrubbed.
 *
 * A note is free text typed by a person, which makes it the one place in the console where
 * a name or a phone number can be typed straight into storage. It goes through the same
 * scrubber as every log line (invariant I5).
 */
private fun Console.storeOverrideNote(caseId: String, reason: String, note: String, operator: String, hash: String) {
    if (note.isBlank()) return
    val dir = home.caseDir(caseId)
    Files.createDirectories(dir)
    val body = buildString {
        append("override: $reason\n")
        append("operator: ${LogScrubber.scrub(operator)}\n")
        append("at: ${clock.nowIso()}\n")
        append("audit tip: $hash\n")
        append("note: ${LogScrubber.scrub(note)}\n")
    }
    Files.writeString(dir.resolve("override.txt"), body)
}

/** `kasoti verify` — recompute the chain from the stored log (invariant I6). */
fun Console.verify(line: CommandLine): CommandResult {
    line.rejectUnknown(setOf("verbose"))
    if (!Files.exists(home.auditFile)) {
        return CommandResult(ConsolePolicy.Exit.OK, "no audit log at ${home.auditFile}; nothing to verify")
    }
    val result = audit.verify()
    view.banner("Audit chain")
    view.field("records", result.length.toString())
    view.field("auditTip", audit.tip())
    view.field("result", if (result.valid) "VERIFIED" else "BROKEN at index ${result.brokenAtIndex}")
    if (!result.valid) {
        view.field("reason", result.reason)
    }
    if (line.flag("verbose")) {
        view.line()
        view.line(audit.excerpt())
    }
    return CommandResult(
        if (result.valid) ConsolePolicy.Exit.OK else ConsolePolicy.Exit.INTEGRITY,
        if (result.valid) "chain verified" else "CHAIN BROKEN: ${result.reason}",
    )
}
