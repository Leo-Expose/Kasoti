package dev.kasoti.desktop

import dev.kasoti.audit.DecisionRecord
import dev.kasoti.desktop.screen.ConsoleView
import dev.kasoti.desktop.screen.CoreFusionEngine
import dev.kasoti.desktop.screen.DemoSvm
import dev.kasoti.desktop.screen.LocalRuleEngine
import dev.kasoti.desktop.screen.ScreeningCascade
import dev.kasoti.desktop.screen.ScreeningRequest
import dev.kasoti.desktop.screen.ScreeningResult
import dev.kasoti.desktop.screen.Sidecar
import dev.kasoti.desktop.screen.SvmModelFile
import dev.kasoti.desktop.screen.VerdictEngine
import dev.kasoti.factory.LoadedSvmModel
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.fusion.Track
import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset

/**
 * The console's home on disk.
 *
 * Deliberately one directory with three things in it — the audit log, the case store and
 * the supervisor PIN file. A post machine that has to be wiped has to have exactly one
 * place to wipe, and an operator has to be able to say where that is without asking.
 */
class ConsoleHome(val root: Path) {
    val auditFile: Path get() = root.resolve("audit.log")
    val casesDir: Path get() = root.resolve("cases")
    val pinFile: Path get() = root.resolve("supervisor.pin")

    fun caseDir(caseId: String): Path = casesDir.resolve(caseId)

    fun ensure() {
        Files.createDirectories(root)
        Files.createDirectories(casesDir)
    }

    companion object {
        const val DEFAULT_DIR = ".kasoti-console"
        fun default(): ConsoleHome = ConsoleHome(Path.of(DEFAULT_DIR))
    }
}

/** A command's outcome: an exit code and the one line worth printing. */
data class CommandResult(val exitCode: Int, val summary: String)

/**
 * The Post-Console command surface.
 *
 * Every command returns a [CommandResult] carrying an exit code rather than calling
 * `exitProcess`, so the whole surface is testable in-process. `Main` is the only place
 * that knows about the JVM's exit convention.
 *
 * The commands themselves live in sibling files as extensions on this class, so each one
 * is readable on its own and the shared wiring — home, clock, audit chain, view — is stated
 * exactly once.
 */
class Console(
    val home: ConsoleHome,
    val clock: ConsoleClock = ConsoleClock(),
    val imaging: ImageIoImaging = ImageIoImaging(),
    val view: ConsoleView = ConsoleView { println(LogScrubber.scrub(it)) },
) {

    val audit: AuditStore by lazy { AuditStore(home.auditFile) }

    fun run(line: CommandLine): CommandResult = when (line.command) {
        "screen" -> screen(line)
        "override" -> override(line)
        "verify" -> verify(line)
        "export" -> export(line)
        "wipe" -> wipe(line)
        "macro" -> macro(line)
        "sample" -> sample(line)
        "help", "--help", "-h" -> help()
        else -> CommandResult(ConsolePolicy.Exit.USAGE, "unknown command '${line.command}'; try `kasoti help`")
    }

    // ------------------------------------------------------------------ screen

    /**
     * Screen a document image and record the decision.
     *
     * The console takes a *file*, not a camera. That is what makes a screening
     * reproducible: the same image can be put back through the cascade after a threshold
     * change, which is the only way to know whether a tuning actually helped (EVAL.md §6).
     */
    fun screen(line: CommandLine): CommandResult {
        line.rejectUnknown(
            setOf(
                "image", "track", "fields", "mrz", "today", "device", "svm", "demo",
                "no-clip", "grey-streak", "fusion", "no-rationale",
            ),
        )
        val image = Path.of(line.required("image"))
        if (!Files.isRegularFile(image)) {
            return CommandResult(ConsolePolicy.Exit.USAGE, "no such image: $image")
        }
        val track = Track.entries.firstOrNull { it.name.equals(line.option("track", "PASSPORT"), true) }
            ?: return CommandResult(
                ConsolePolicy.Exit.USAGE,
                "unknown track; expected one of ${Track.entries.joinToString(",") { it.name }}",
            )
        val sidecar = line.option("fields")?.let { Sidecar.load(Path.of(it)) } ?: Sidecar.EMPTY
        val today = Console.isoDateOfOrNull(line.option("today") ?: "")
            ?: Console.isoDateOf(clock.now())
        val demoMode = line.flag("demo")
        val deviceId = line.option("device", "desktop-console")

        val classifier = resolveClassifier(line, demoMode)
        val caseId = Ids.caseId(clock.millis())
        val result = ScreeningCascade(engine = resolveFusionEngine(line)).screen(
            ScreeningRequest(
                image = image,
                track = track,
                sidecar = sidecar,
                today = today,
                deviceId = deviceId,
                demoMode = demoMode,
                consecutiveGreyCount = line.int("grey-streak", 0),
                manualMrz = line.paths("mrz"),
                classifier = classifier,
                clipUsed = !line.flag("no-clip"),
                showRationale = !line.flag("no-rationale"),
            ),
            caseId,
        )

        val record = DecisionRecord(
            id = "rec_${Ids.ulid(clock.millis())}",
            timestampUtc = clock.nowIso(),
            caseId = caseId,
            deviceId = deviceId,
            verdict = result.decision.verdict.name,
            findingCodes = result.decision.findings.map { it.code.name },
            thresholdVersion = result.thresholdVersion,
            fusionRuleVersion = result.fusionRuleVersion,
            embeddingModel = null,
        )
        val hash = audit.append(record)
        persistCase(result, record)

        printResult(result, hash)
        if (demoMode) {
            view.line()
            view.note("DEMO RUN — the decision is stamped demoMode and is not evidence of anything (invariant I7)")
        }
        return CommandResult(ConsolePolicy.Exit.OK, "screened $caseId: ${result.decision.verdict}")
    }

    /**
     * The macro classifier for this run, or `null` when none was provisioned.
     *
     * `null` is a legitimate state, not an error: without an SVM the macro layer reports
     * `UNAVAILABLE` and the other layers still produce a decision. What is *not* legitimate is
     * silently substituting a model, which is why [SvmModelFile.requireUsable] throws on the demo
     * stub unless `--demo` was passed.
     *
     * ## The default lookup, and why the order is what it is
     *
     * `--svm` wins, then the D-MACRO model at [SvmModelFile.DEFAULT_PATH], then the committed
     * synthetic model, then the `--demo` stub, then nothing. The synthetic model is what makes the
     * demo classify instead of abstaining on every patch, and it is checked *after* the real one
     * so that a console cannot keep reporting the synthetic number after real data has landed —
     * the most likely way this goes wrong in practice. A `--svm` path that does not exist is an
     * error rather than a fall-through, because an operator who named a file and did not get it
     * must be told so.
     */
    private fun resolveFusionEngine(line: CommandLine): VerdictEngine =
        when (line.option("fusion", "core").lowercase()) {
            "core" -> CoreFusionEngine()
            "local" -> LocalRuleEngine()
            else -> throw UsageException("--fusion expects core or local, got '${line.option("fusion")}'")
        }

    private fun resolveClassifier(line: CommandLine, demoMode: Boolean): ProcessClassifier? {
        val explicit = line.option("svm")
        if (explicit != null) {
            val path = Path.of(explicit)
            if (!Files.isRegularFile(path)) {
                throw UsageException("--svm named $path and there is no such file")
            }
            val model = SvmModelFile.loadWithProvenance(path)
            SvmModelFile.requireUsable(model.model, demoMode)
            return describe(model, SvmModelFile.classifierFor(model.model))
        }
        val real = SvmModelFile.defaultPath()
        if (Files.isRegularFile(real)) {
            val model = SvmModelFile.loadWithProvenance(real)
            SvmModelFile.requireUsable(model.model, demoMode)
            return describe(model, SvmModelFile.classifierFor(model.model))
        }
        val synthetic = Path.of(SvmModelFile.SYNTHETIC_PATH)
        if (Files.isRegularFile(synthetic)) {
            val model = SvmModelFile.loadWithProvenance(synthetic)
            return describe(model, SvmModelFile.classifierFor(model.model))
        }
        if (demoMode) {
            val stub = DemoSvm.untrained()
            SvmModelFile.requireUsable(stub, demoMode)
            return SvmModelFile.classifierFor(stub)
        }
        return null
    }

    /**
     * Bind the classifier and say, on the console, what it is.
     *
     * The line is not decoration. A model fitted on generated textures produces exactly the same
     * shape of output as a real one — a process label and a margin — and the only thing that
     * distinguishes them is a word somebody has to type, so the word is printed before the
     * verdict rather than in a model card three files away.
     */
    private fun describe(model: LoadedSvmModel, classifier: ProcessClassifier): ProcessClassifier {
        view.note("macro model: ${model.provenance}")
        if (model.synthetic) {
            view.note(
                "SYNTHETIC MACRO MODEL — trained on generated textures by eval/tools/synth_macros.py. " +
                    "It is not evidence about any print process and is not the D-MACRO gate (SPEC §7).",
            )
        }
        return classifier
    }

    private fun printResult(result: ScreeningResult, hash: String) {
        view.banner("KASOTI screening ${result.decision.verdict}")
        view.field("case", result.caseId)
        view.field("track", result.track.name)
        view.field("image", result.image.fileName.toString())
        view.field("auditTip", hash)
        view.field("clock", clock.provenance())
        view.layers(result.layers)
        view.verdictTable(result.decision.verdict, result.decision.findings)
        view.headline(result.decision.verdict, result.decision.findings)
        view.policy(result.thresholdVersion, result.fusionRuleVersion, "console")
        view.warnings(result.warnings)
    }

    /**
     * Store the case so `override` and `export` can find it later.
     *
     * Only non-identifying material: the findings, the layer statuses, the policy versions
     * and the demo flag. The MRZ text and any printed name stay out of the console's own
     * store — the bundle is the only artefact that leaves, and it is written under the same
     * rule.
     */
    private fun persistCase(result: ScreeningResult, record: DecisionRecord) {
        val dir = home.caseDir(result.caseId)
        Files.createDirectories(dir)
        val summary = buildString {
            append("case: ${result.caseId}\n")
            append("verdict: ${result.decision.verdict}\n")
            append("track: ${result.track}\n")
            append("threshold: ${record.thresholdVersion}\n")
            append("fusion: ${record.fusionRuleVersion}\n")
            // The demo flag lives here because `DecisionRecord` has no field for it and
            // the bundle is built from the record. Without this line a demo case exports
            // looking exactly like a real one (invariant I7).
            append("demo: ${result.evidence.demoMode}\n")
            append("findings:\n")
            result.decision.findings.forEach { append("  ${it.code.name} ${it.evidenceRef} ${it.message}\n") }
            append("layers:\n")
            result.layers.forEach { append("  ${it.name} ${it.state} ${it.detail}\n") }
        }
        // Scrubbed line by line. The scrubber neutralises newlines — it has to, so that a
        // document field cannot forge a second log line — which means handing it a whole
        // multi-line document would flatten it into one unreadable row.
        val scrubbed = summary.lines().joinToString("\n") { LogScrubber.scrub(it) }
        Files.writeString(dir.resolve("summary.txt"), scrubbed)
    }

    /** @return whether the stored case was produced by a demo run, defaulting to `false`. */
    internal fun storedDemoFlag(caseId: String): Boolean {
        val summary = home.caseDir(caseId).resolve("summary.txt")
        if (!Files.isRegularFile(summary)) return false
        return Files.readAllLines(summary).any { it.trim().startsWith("demo:") && it.endsWith("true") }
    }

    // -------------------------------------------------------------------- help

    fun help(): CommandResult {
        view.banner("KASOTI post-console")
        view.line("  usage: kasoti <command> [options]")
        view.line()
        view.table(
            listOf("command", "options", "what it does"),
            listOf(
                listOf("screen", "--image F --track T [--fields s.json] [--demo]", "screen a document image"),
                listOf("override", "--case C --verdict V --reason R --by OP [--note N]", "override with a reason code"),
                listOf("verify", "[--verbose]", "recompute the audit chain"),
                listOf("export", "--case C [--out bundle.zip]", "write case.json + crops/ + audit.txt"),
                listOf("wipe", "--pin P --yes", "one-tap wipe, supervisor PIN gated"),
                listOf("macro", "--image F --source-id S --process P", "collect a labelled macro patch"),
                listOf("sample", "--out DIR", "write a zero-PII demo specimen and its sidecar"),
            ),
        )
        view.line()
        view.note("override reasons: ${ConsolePolicy.OVERRIDE_REASONS.joinToString(", ")}")
        view.note("supervisor PIN: --pin or ${ConsolePolicy.SUPERVISOR_PIN_ENV}")
        view.note("default home: ${ConsoleHome.DEFAULT_DIR}")
        return CommandResult(ConsolePolicy.Exit.OK, "help")
    }

    companion object {
        fun isoDateOf(instant: java.time.Instant): IsoDate {
            val utc = instant.atZone(ZoneOffset.UTC)
            return IsoDate(utc.year, utc.monthValue, utc.dayOfMonth)
        }

        fun isoDateOfOrNull(text: String): IsoDate? {
            val parts = text.split('-')
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            return CalendarDate.of(year, month, day)
        }
    }
}
