package dev.kasoti.desktop

import dev.kasoti.platform.collector.MacroCaptureRequest
import dev.kasoti.platform.collector.MacroCollector
import dev.kasoti.platform.collector.MacroLight
import dev.kasoti.platform.collector.MacroProcess
import dev.kasoti.platform.imaging.NormRect
import java.nio.file.Files
import java.nio.file.Path

/**
 * `kasoti wipe` — the one-tap wipe, supervisor-PIN gated (FR-S3, invariant I5).
 *
 * Two controls, and both have to pass. The PIN, compared in constant time over a SHA-256
 * digest — a plain string compare would leak the PIN one character at a time to anyone who
 * can time the process, which on a laptop an operator has walked away from is not a
 * hypothetical. And an explicit `--yes`, because a one-tap action that destroys an audit
 * chain should still require the operator to have read what it is about to do.
 */
fun Console.wipe(line: CommandLine): CommandResult {
    line.rejectUnknown(setOf("pin", "yes"))
    val supplied = line.option("pin") ?: System.getenv(ConsolePolicy.SUPERVISOR_PIN_ENV)
        ?: return CommandResult(
            ConsolePolicy.Exit.REFUSED,
            "no supervisor PIN supplied; pass --pin or set ${ConsolePolicy.SUPERVISOR_PIN_ENV}",
        )
    if (!SupervisorPin.matches(supplied, loadPin())) {
        return CommandResult(ConsolePolicy.Exit.REFUSED, "supervisor PIN rejected; nothing was wiped")
    }
    if (!line.flag("yes")) {
        return CommandResult(
            ConsolePolicy.Exit.USAGE,
            "this deletes every case, every audit record and the PIN file at ${home.root}; " +
                "re-run with --yes to confirm",
        )
    }

    val removedRecords = if (Files.exists(home.auditFile)) audit.size else 0
    val removedCases = if (Files.isDirectory(home.casesDir)) {
        Files.list(home.casesDir).use { it.count().toInt() }
    } else {
        0
    }
    if (Files.isDirectory(home.casesDir)) home.casesDir.toFile().deleteRecursively()
    val receipt = audit.wipe(clock.nowIso(), removedRecords)
    Files.deleteIfExists(home.pinFile)
    home.ensure()

    view.banner("Wipe complete")
    view.table(
        listOf("field", "value"),
        listOf(
            listOf("at", receipt.atUtc),
            listOf("audit records removed", removedRecords.toString()),
            listOf("cases removed", removedCases.toString()),
            listOf("chainTip", receipt.tipBefore),
            listOf("PIN file", if (Files.exists(home.pinFile)) "still present" else "removed"),
        ),
    )
    return CommandResult(ConsolePolicy.Exit.OK, "console wiped")
}

private fun Console.loadPin(): String? =
    if (Files.isRegularFile(home.pinFile)) Files.readString(home.pinFile).trim() else null

/**
 * `kasoti macro` — collect one labelled macro patch (DATA.md §3).
 *
 * Exposed through the console rather than only as a library because the operator holding a
 * genuine card in front of them is the only person who can supply the label, and the
 * fastest way to supply it is to answer four flags. Getting the label wrong is the single
 * most expensive mistake in the corpus, so the vocabulary is closed and the counter is
 * derived from the filesystem rather than from memory.
 */
fun Console.macro(line: CommandLine): CommandResult {
    line.rejectUnknown(
        setOf("image", "source-id", "process", "light", "clip", "no-clip", "device", "calib", "root", "rect"),
    )
    val rect = line.option("rect", "0,0,1,1").split(',').map { it.trim().toFloatOrNull() }
    if (rect.size != 4 || rect.any { it == null }) {
        return CommandResult(ConsolePolicy.Exit.USAGE, "--rect expects x,y,w,h as fractions of the frame")
    }
    val process = runCatching { MacroProcess.require(line.required("process")) }
        .getOrElse { return CommandResult(ConsolePolicy.Exit.USAGE, it.message.orEmpty()) }
    val light = runCatching { MacroLight.require(line.option("light", "sun")) }
        .getOrElse { return CommandResult(ConsolePolicy.Exit.USAGE, it.message.orEmpty()) }

    val request = MacroCaptureRequest(
        sourceImage = Path.of(line.required("image")),
        sourceId = line.required("source-id"),
        process = process,
        light = light,
        clip = !line.flag("no-clip"),
        deviceId = line.option("device", "desktop-console"),
        calibId = line.option("calib", "uncalibrated"),
        region = NormRect(rect[0]!!, rect[1]!!, rect[2]!!, rect[3]!!),
    )
    val sample = MacroCollector(Path.of(line.option("root", "eval/data/macro")), imaging).capture(request)

    view.banner("Macro sample collected")
    view.table(
        listOf("field", "value"),
        listOf(
            listOf("path", sample.relativePath),
            listOf("patch", "${sample.patchWidth}x${sample.patchHeight} gray"),
            listOf("mean", "%.4f".format(sample.grayMean)),
            listOf("std dev", "%.4f".format(sample.grayStdDev)),
            listOf("manifest", sample.manifestRow),
        ),
    )
    return CommandResult(ConsolePolicy.Exit.OK, "collected ${sample.relativePath}")
}
