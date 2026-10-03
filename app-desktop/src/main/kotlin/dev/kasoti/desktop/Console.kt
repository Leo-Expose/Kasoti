package dev.kasoti.desktop

import dev.kasoti.audit.DecisionRecord
import dev.kasoti.desktop.screen.ConsoleView
import dev.kasoti.desktop.screen.CoreFusionEngine
import dev.kasoti.desktop.screen.DemoSvm
import dev.kasoti.desktop.screen.LocalRuleEngine
import dev.kasoti.desktop.screen.MacroModel
import dev.kasoti.desktop.screen.ModelLocator
import dev.kasoti.desktop.screen.ModelLocation
import dev.kasoti.desktop.screen.ModelOrigin
import dev.kasoti.desktop.screen.ScreeningCascade
import dev.kasoti.desktop.screen.ScreeningRequest
import dev.kasoti.desktop.screen.ScreeningResult
import dev.kasoti.desktop.screen.Sidecar
import dev.kasoti.desktop.screen.SvmModelFile
import dev.kasoti.desktop.screen.VerdictEngine
import dev.kasoti.factory.LoadedSvmModel
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
 *
 * [source] records *why* this directory was chosen and is printed by `help`, because the
 * answer used to be "whatever directory the operator was standing in", and a console home
 * that moves with the shell is an audit chain that forks.
 */
class ConsoleHome(val root: Path, val source: String = "given explicitly") {
    val auditFile: Path get() = root.resolve("audit.log")
    val casesDir: Path get() = root.resolve("cases")
    val pinFile: Path get() = root.resolve("supervisor.pin")

    fun caseDir(caseId: String): Path = casesDir.resolve(caseId)

    fun ensure() {
        Files.createDirectories(root)
        Files.createDirectories(casesDir)
    }

    companion object {
        const val DIR_NAME = ".kasoti-console"

        /**
         * The home for a console started with no argument.
         *
         * Anchored to the user's home directory, not the working directory. A `wipe` is
         * defined as "delete this one directory"; a home that follows `cd` means the same
         * command wipes a different store depending on where it was typed, and the console
         * scatters an audit chain into every directory an operator ever screens from — which
         * is how the distribution came to be shipped with a `.kasoti-console` already inside
         * it. The environment variable is there for a deployment that provisions elsewhere.
         *
         * With no home directory discoverable at all, falling back to the working directory
         * is the only option left, and [source] says so, because a silent fallback is how this
         * bug happened the first time.
         */
        fun default(
            env: Map<String, String> = System.getenv(),
            userHome: Path? = defaultUserHome(env),
            workingDir: Path = Path.of("").toAbsolutePath(),
        ): ConsoleHome {
            val override = env[ConsolePolicy.CONSOLE_HOME_ENV]?.takeIf { it.isNotBlank() }
            if (override != null) return ConsoleHome(Path.of(override), ConsolePolicy.CONSOLE_HOME_ENV)
            val home = userHome
            return if (home != null) {
                ConsoleHome(home.resolve(DIR_NAME), "\$HOME/$DIR_NAME")
            } else {
                ConsoleHome(workingDir.resolve(DIR_NAME), "$DIR_NAME in the working directory (no home directory found)")
            }
        }

        private fun defaultUserHome(env: Map<String, String>): Path? =
            env["HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

        /** Kept for the help text and for the tests that assert the name is stable. */
        const val DEFAULT_DIR = DIR_NAME
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
    /**
     * How the macro model is found. A constructor parameter so a test can pin the whole
     * search — no environment, no installation, an empty working directory — and assert on
     * what the console says when a provisioned console has no model. Without that seam the
     * missing-model path is only reachable by unpacking a real distribution, which is exactly
     * the situation it exists for and the one that ships broken.
     */
    private val modelLocator: ModelLocator = ModelLocator(),
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

        val macroModel = resolveMacroModel(line, demoMode)
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
                macroModel = macroModel,
                modelSearchNote = modelSearchNote(),
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

    private fun resolveFusionEngine(line: CommandLine): VerdictEngine =
        when (line.option("fusion", "core").lowercase()) {
            "core" -> CoreFusionEngine()
            "local" -> LocalRuleEngine()
            else -> throw UsageException("--fusion expects core or local, got '${line.option("fusion")}'")
        }

    /**
     * The macro model for this run, or `null` when none was provisioned.
     *
     * `null` is a legitimate state, not an error: without a model the macro layer reports
     * `UNAVAILABLE` and the other layers still produce a decision. What is *not* legitimate is
     * silently substituting a model, which is why [SvmModelFile.requireUsable] throws on the demo
     * stub unless `--demo` was passed, and why the untrained stub is never reached by accident:
     * it is the *last* thing tried, and only when `--demo` was given on purpose.
     *
     * ## The order, and what each step is for
     *
     * [ModelLocator] owns the search and its reasons; this method owns what to do with the
     * answer. `--svm` wins, then the environment, then the model shipped inside the
     * installation, then a source checkout. The packaged path is what makes a provisioned
     * console give the same answer as the repository, and it is checked before the
     * repository-relative one for the same reason the real model is checked before the
     * synthetic: a console that keeps reading a checkout's file after being copied off it is
     * not reading the model it was shipped with.
     */
    private fun resolveMacroModel(line: CommandLine, demoMode: Boolean): MacroModel? {
        val explicit = line.option("svm")
        if (explicit != null) {
            val path = Path.of(explicit)
            if (!Files.isRegularFile(path)) {
                // Named a file and did not get it: an error, never a fall-through. A silent
                // fall-through here is how `--svm /typo/path.json` ends up screening on the
                // packaged model while the operator believes they are testing theirs.
                throw UsageException("--svm named $path and there is no such file")
            }
            return bind(SvmModelFile.loadWithProvenance(path), ModelOrigin.EXPLICIT_FLAG, demoMode)
        }
        when (val located = modelLocator.locate()) {
            is ModelLocation.Missing -> {
                reportMissingModel(located)
                return if (demoMode) untrainedStub() else null
            }
            is ModelLocation.Found -> {
                return bind(SvmModelFile.loadWithProvenance(located.path), located.origin, demoMode)
            }
        }
    }

    /**
     * Attach provenance to a loaded model and say, on the console, what it is.
     *
     * The lines are not decoration. A model fitted on generated textures produces exactly the
     * same shape of output as a real one — a process label and a margin — and the only thing
     * that distinguishes them is a word somebody has to type, so the word is printed before
     * the verdict rather than in a model card three files away.
     */
    private fun bind(model: LoadedSvmModel, origin: ModelOrigin, demoMode: Boolean): MacroModel {
        val bound = MacroModel(
            classifier = SvmModelFile.classifierFor(model.model),
            source = model.origin,
            provenance = model.provenance,
            origin = origin,
            synthetic = model.synthetic,
            discriminative = SvmModelFile.isDiscriminative(model.model),
        )
        SvmModelFile.requireUsable(model.model, demoMode)
        view.note("macro model: ${bound.provenance}")
        view.note("  from ${bound.source} (${bound.origin.how}) — standing: ${bound.standing}")
        if (bound.synthetic) {
            view.note(
                "SYNTHETIC MACRO MODEL — trained on generated textures by eval/tools/synth_macros.py. " +
                    "It is not evidence about any print process and is not the D-MACRO gate (SPEC §7).",
            )
        }
        return bound
    }

    /**
     * The untrained stub, reached only when `--demo` was given and nothing better exists.
     *
     * Announced like any other model, because it is one. The `--demo` fallback used to bind
     * the stub *silently* — no provenance line at all — which is how a run with no model on
     * disk printed `model=OFFSET, margin=0.000` and read like a print-process opinion.
     */
    private fun untrainedStub(): MacroModel {
        val stub = DemoSvm.untrained()
        val bound = MacroModel(
            classifier = SvmModelFile.classifierFor(stub),
            source = "(built in, no file)",
            provenance = stub.version,
            origin = ModelOrigin.EXPLICIT_FLAG,
            synthetic = false,
            discriminative = false,
        )
        view.note("macro model: ${bound.provenance} (${bound.source}) — standing: ${bound.standing}")
        view.note("NO MACRO READING — the demo stub's weights are all zero, so it has no opinion on any patch")
        return bound
    }

    /** The one-line remedy printed into the `macro` layer's `UNAVAILABLE` row. */
    private fun modelSearchNote(): String =
        "--svm ${SvmModelFile.DEFAULT_FILE_NAME}, or ${ConsolePolicy.SVM_MODEL_ENV}"

    /**
     * Say the model is missing, loudly, and say where to put it.
     *
     * This is the failure the distribution shipped with. The console abstained on the macro
     * layer, the cascade produced a different verdict than the same document got in the
     * repository, and the only trace was one warning line among thirty that an operator
     * scanning a RED at a counter would never read. A layer that did not run is now named in
     * the layer table *and* here, with the full search path, before any verdict is printed.
     */
    private fun reportMissingModel(missing: ModelLocation.Missing) {
        view.line()
        view.line("  !! MACRO MODEL NOT FOUND — the print-process layer will NOT run on this screening")
        missing.searched.forEach { view.line("       not at ${it.path}  (${it.origin.how})") }
        missing.notes.forEach { view.line("       $it") }
        view.line("       provision it with: --svm ${SvmModelFile.DEFAULT_FILE_NAME}")
        view.line("                     or: export ${ConsolePolicy.SVM_MODEL_ENV}=/path/to/model.json")
        view.line("       until then the macro layer is reported UNAVAILABLE and the verdict may be weaker.")
        view.line()
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
        view.note("default home: ${home.root} (${home.source})")
        reportModelStatus()
        return CommandResult(ConsolePolicy.Exit.OK, "help")
    }

    /**
     * `help` doubles as the provisioning check.
     *
     * A judge handed an archive types `help` first, and "did it find its model" is the first
     * question that decides whether the next ten minutes are worth spending. Burying the
     * answer in a screening run means the first thing they see is a verdict, produced
     * possibly without the print-process layer, and the only way to notice is to compare it
     * with a checkout they do not have.
     */
    private fun reportModelStatus() {
        when (val located = modelLocator.locate()) {
            is ModelLocation.Found -> view.note("macro model: ${located.path} (${located.origin.how})")
            is ModelLocation.Missing -> reportMissingModel(located)
        }
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
