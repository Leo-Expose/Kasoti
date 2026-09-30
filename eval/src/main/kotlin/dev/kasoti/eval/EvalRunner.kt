package dev.kasoti.eval

import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.device.AttachedDevice
import dev.kasoti.eval.device.DeviceProbe
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.output.JsonMetricSink
import dev.kasoti.eval.output.MetricSink
import dev.kasoti.eval.run.FailureCase
import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.OperatingPoint
import dev.kasoti.eval.run.RunRecord
import dev.kasoti.eval.run.RunStatus
import dev.kasoti.eval.run.SuiteReport
import dev.kasoti.eval.run.TableData
import dev.kasoti.eval.run.ThresholdProvenance
import dev.kasoti.eval.split.DatasetManifest
import dev.kasoti.eval.split.ManifestStore
import dev.kasoti.eval.split.SplitDiscipline
import dev.kasoti.eval.split.SplitVerdict
import dev.kasoti.eval.split.ThresholdLedger
import dev.kasoti.eval.suites.CrosscheckVectors
import dev.kasoti.eval.suites.DiarySuite
import dev.kasoti.eval.suites.FaceSuite
import dev.kasoti.eval.suites.LatencySuite
import dev.kasoti.eval.suites.MacroSuite
import dev.kasoti.eval.suites.MrzSuite
import dev.kasoti.eval.suites.ParitySuite
import dev.kasoti.eval.suites.QrSuite
import dev.kasoti.eval.suites.SpecimenFixtures
import dev.kasoti.eval.suites.SvmModelLoader
import dev.kasoti.eval.suites.UnitParitySuite
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The orchestrator.
 *
 * Order of operations is itself a requirement, not a style choice:
 *
 * 1. Resolve the repository and load the manifests. A missing manifest aborts the run —
 *    see [ManifestStore] for why an absent manifest may not be read as an empty one.
 * 2. Run the split-discipline check over the thresholds this run will actually consult.
 *    A violation invalidates the run *before* any number is computed, so an invalid run
 *    cannot even produce the metrics it would otherwise produce.
 * 3. Execute the suites, accumulating gates, metrics, tables, histograms and failures.
 * 4. Fold the gates into one status, with INVALID taking precedence over FAIL and FAIL
 *    over INCOMPLETE: a split violation is a statement about the run, and it is not
 *    softened by the run also having failed a gate.
 * 5. Serialise. `metrics.json` and `summary.md` are written even for a failed run — a gate
 *    that fires without leaving evidence behind is a gate nobody can debug.
 */
class EvalRunner(private val sink: MetricSink = JsonMetricSink()) {

    fun run(argv: Array<String>): Int {
        val args = try {
            EvalArgs.parse(argv)
        } catch (e: IllegalArgumentException) {
            System.err.println("eval: ${e.message}")
            return ExitCode.USAGE.code
        }

        // `--emit-crosscheck` is a side door for the Python cross-check: it needs :core's
        // feature vectors for the shared synthetic recipes and nothing else, so it does not
        // need a suite, a manifest or a device.
        args.emitCrosscheck?.let { target ->
            return emitCrosscheck(args.repoRoot ?: System.getProperty("user.dir"), target)
        }
        args.emitMrzCorpus?.let { target ->
            return emitMrzCorpus(args.repoRoot ?: System.getProperty("user.dir"), target, args.macRows)
        }

        val started = Instant.now()
        val repoRoot = resolveRepoRoot(args.repoRoot)
        if (repoRoot == null) {
            System.err.println(
                "eval: cannot locate the repository root. Run from the repo, or pass " +
                    "--repo-root=<dir>. Looked for settings.gradle.kts walking up from " +
                    "${System.getProperty("user.dir")}.",
            )
            return ExitCode.USAGE.code
        }

        return try {
            execute(args, repoRoot, started)
        } catch (e: Exception) {
            System.err.println("eval: internal error: ${e.message}")
            e.printStackTrace()
            ExitCode.INTERNAL.code
        }
    }

    private fun execute(args: EvalArgs, repoRoot: Path, started: Instant): Int {
        val log = { message: String -> if (args.verbose) println("eval: $message") }
        val runId = if (args.runIdWasAuto) autoRunId(args.suite, started) else args.runId
        val outDir = repoRoot.resolve(args.outDir.removeSuffix("/")).resolve(runId)

        val store = ManifestStore(repoRoot.resolve("eval/data/manifests"))
        val datasetManifest = store.loadDatasetManifest()
        val ledger = store.loadLedger()
        log("manifests loaded: ${datasetManifest.datasets.size} datasets, ledger v${ledger.version}")

        // Thresholds this run will consult. Recorded up front so the split check covers
        // exactly the set the metrics depend on — a threshold never read cannot have
        // contaminated a number, and gating on it anyway would invalidate honest runs.
        val thresholdsInUse = thresholdsFor(args.suite)
        val splitVerdict = SplitDiscipline.check(ledger, datasetManifest, thresholdsInUse)
        if (!splitVerdict.isValid) {
            log("SPLIT DISCIPLINE VIOLATED — the run is invalid before any metric is computed")
        }

        val registry = ThresholdRegistry.defaults(version = "v1", runId = runId)
        val devices = DeviceProbe.find(args.deviceSerial)
        val hostDevice = HostDeviceId
        val calibration = store.evaluateCalibration(hostDevice, started.epochSecond)

        val accumulator = Accumulator()

        // ---- suites ----------------------------------------------------------
        val wantMrz = args.suite.suites.contains("mrz")
        if (wantMrz) {
            val result = timed { MrzSuite.run(args.macRows) }
            accumulator.absorb("mrz", result.gates, result.metrics, result.tables, result.histograms, result.failures, result.notes)
        }

        if (args.suite.suites.contains("qr")) {
            val result = timed { QrSuite.run() }
            accumulator.absorb("qr", result.gates, result.metrics, result.tables, emptyList(), result.failures, result.notes)
        }

        if (args.suite.suites.contains("diary")) {
            val result = timed { DiarySuite.run(registry) }
            accumulator.absorb("diary", result.gates, result.metrics, result.tables, emptyList(), result.failures, result.notes)
            // Per-scenario outcomes and the retrieval curve, verbatim from :core's metrics.
            accumulator.evalmetricsExtras["Diary scenarios"] = result.outcomes.joinToString("\n") { outcome ->
                "- `${outcome.id}` expected ${outcome.expected} ${outcome.expectedCodes.sorted()}, " +
                    "got ${outcome.actual} ${outcome.codes.sorted()} — ${if (outcome.passed) "PASS" else "FAIL"}" +
                    if (outcome.note.isNotEmpty()) " (${outcome.note})" else ""
            }
            accumulator.evalmetricsExtras["Diary 1:N retrieval"] =
                "gallery ${result.retrieval.gallerySize}, probes ${result.retrieval.probes}, " +
                    "rank-1 %.2f%%, actionable %.2f%%, median rank %.1f".format(
                        result.retrieval.rank1Rate * 100, result.retrieval.actionableRate * 100,
                        result.retrieval.medianRank,
                    )
        }

        if (args.suite.suites.contains("unit-parity")) {
            val result = timed { UnitParitySuite.run() }
            accumulator.absorb("unit-parity", result.gates, result.metrics, emptyList(), emptyList(), result.failures, result.notes)
        }

        if (args.suite.suites.contains("latency")) {
            val result = timed { LatencySuite.run() }
            accumulator.absorb("latency", result.gates, result.metrics, result.tables, result.histograms, result.failures, result.notes)
        }

        if (args.suite.suites.contains("macro")) {
            // Real model first, committed synthetic second, and the reason recorded either way —
            // see SvmModelLoader.resolve. A run that keeps reporting the synthetic number after
            // real data has landed is the most likely way this goes wrong in practice.
            val resolution = timed { SvmModelLoader.resolve(repoRoot) }
            val specimens = timed { SpecimenFixtures.loadIfPresent(repoRoot) }
            log("macro model: ${resolution.which.label} (${resolution.path})")
            val result = timed { MacroSuite.run(registry, calibration, resolution.model, specimens) }
            accumulator.absorb("macro", result.gates, result.metrics, result.tables, result.histograms, emptyList(), result.notes)
        }

        if (args.suite.suites.contains("face")) {
            val result = timed { FaceSuite.run(registry, trials = null, datasetNote = NO_FACE_DATA_NOTE) }
            accumulator.absorb("face", result.gates, result.metrics, result.tables, result.histograms, emptyList(), result.notes)
        }

        if (args.suite.isDeviceGated) {
            val result = timed {
                ParitySuite.run(
                    devices = devices,
                    registry = registry,
                    calibration = calibration,
                    desktopVerdicts = readVerdicts(outDir.resolve("desktop_verdicts.jsonl")),
                    deviceVerdicts = readVerdicts(outDir.resolve("device_verdicts.jsonl")),
                )
            }
            accumulator.absorb("parity", result.gates, result.metrics, result.tables, emptyList(), emptyList(), result.notes)
        }

        // ---- fold gates into a status ----------------------------------------
        val status = statusFor(splitVerdict, accumulator)
        val exit = exitFor(status)

        val finished = Instant.now()
        val record = RunRecord(
            runId = runId,
            suite = args.suite.id,
            startedAtUtc = iso(started),
            finishedAtUtc = iso(finished),
            commit = gitCommit(repoRoot),
            commitDirty = gitDirty(repoRoot),
            host = hostDescription(),
            jvm = System.getProperty("java.version") ?: "unknown",
            devices = devices.map {
                dev.kasoti.eval.run.DeviceInfo(
                    id = it.serial,
                    model = it.model,
                    osVersion = "Android ${it.androidVersion} (SDK ${it.sdkInt})",
                    buildFingerprint = it.buildFingerprint,
                    role = it.role,
                )
            },
            thresholdsInUse = thresholdsInUse.sorted(),
            thresholdLedgerVersion = ledger.version,
            thresholdProvenance = provenanceFor(ledger, thresholdsInUse),
            splitDiscipline = splitVerdict,
            calibration = calibration,
            suites = accumulator.suiteReports(),
            gates = accumulator.gates,
            metrics = accumulator.metrics,
            tables = accumulator.tables,
            histograms = accumulator.histograms,
            failures = accumulator.failures,
            operatingPoints = operatingPointsFor(registry, ledger, thresholdsInUse),
            specConflicts = SpecConflicts.detect(ledger),
            notes = accumulator.notes,
            status = status,
            exitCode = exit.code,
        )

        Files.createDirectories(outDir)
        sink.write(record, outDir)
        writeCrosscheckVectors(outDir)
        writeMrzCorpus(outDir, args.macRows)

        printConsole(record, outDir, splitVerdict, calibration)
        return exit.code
    }

    // ------------------------------------------------------------------ helpers

    private class Accumulator {
        val gates = mutableListOf<GateResult>()
        val metrics = mutableListOf<Metric>()
        val tables = mutableListOf<TableData>()
        val histograms = mutableListOf<Histogram>()
        val failures = mutableListOf<FailureCase>()
        val notes = mutableListOf<String>()
        private val suites = mutableListOf<SuiteReport>()

        /** Per-suite detail rendered from :core's own metrics types, appended to metrics.json. */
        val evalmetricsExtras = linkedMapOf<String, String>()

        fun absorb(
            suite: String,
            gates: List<GateResult>,
            metrics: List<Metric>,
            tables: List<TableData>,
            histograms: List<Histogram>,
            failures: List<FailureCase>,
            notes: List<String>,
        ) {
            this.gates += gates
            this.metrics += metrics
            this.tables += tables
            this.histograms += histograms
            this.failures += failures
            this.notes += notes
            suites += SuiteReport(
                suite = suite,
                ran = true,
                gateIds = gates.map { it.id },
                notes = notes.filter { it.isNotBlank() },
            )
        }

        fun suiteReports(): List<SuiteReport> = suites
    }

    private inline fun <T> timed(block: () -> T): T = block()

    /**
     * `:core`'s feature vectors for the shared synthetic recipes, next to the run outputs.
     *
     * Written on every run, not only on request, so a run directory is self-contained: the
     * Python cross-check can be pointed at any committed run and re-verified later against
     * the exact `:core` build that produced the numbers, rather than against whatever `:core`
     * happens to be on the machine that day.
     */
    private fun writeCrosscheckVectors(outDir: Path) {
        val dir = outDir.resolve("crosscheck")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("kotlin_features.jsonl"), CrosscheckVectors.jsonLines())
        Files.writeString(
            dir.resolve("README.md"),
            "# NumPy ↔ Kotlin feature cross-check\n\n" +
                "`kotlin_features.jsonl` is `:core`'s feature vector for each recipe in\n" +
                "`eval/tools/patches.py`, written by " +
                "`dev.kasoti.eval.suites.CrosscheckVectors`.\n\n" +
                "Verify it with:\n\n```bash\n" +
                "python3 eval/tools/cross_check.py --kotlin " +
                "eval/runs/<run-id>/crosscheck/kotlin_features.jsonl\n```\n\n" +
                "Tolerances, and why they are what they are, are stated in\n" +
                "`dev.kasoti.eval.suites.CrosscheckVectors`.\n",
        )
    }

    /**
     * `:core`'s normative MRZ corpus, written next to the run outputs.
     *
     * The same corpus the M-gate measured, not a re-derivation, so a reviewer can read the
     * actual lines behind any row id in the failure gallery and confirm for themselves that a
     * blind-spot row really is undetectable rather than taking the harness's word for it. It
     * contains no PII: `MrzCorpus` generates it from a fixed seed.
     */
    private fun writeMrzCorpus(outDir: Path, rows: Int) {
        Files.writeString(outDir.resolve("mrz_corpus.jsonl"), mrzCorpusJsonl(rows))
    }

    private fun mrzCorpusJsonl(rows: Int): String =
        dev.kasoti.mrz.MrzCorpus.generate(
            seed = MrzSuite.SEED,
            count = rows,
            referenceYear = MrzSuite.REFERENCE_YEAR,
        ).joinToString("\n") { case ->
            dev.kasoti.evalmetrics.MetricJsonWriter.write(
                dev.kasoti.evalmetrics.jsonOf(
                    "id" to dev.kasoti.evalmetrics.MetricJson.Str(case.id),
                    "format" to dev.kasoti.evalmetrics.MetricJson.Str(case.format.name),
                    "mutation" to dev.kasoti.evalmetrics.MetricJson.Str(case.mutation.name),
                    "expectedCaught" to dev.kasoti.evalmetrics.MetricJson.Bool(case.expectedCaught),
                    "expectedFields" to dev.kasoti.evalmetrics.MetricJson.Arr(
                        case.expectedFields.map { dev.kasoti.evalmetrics.MetricJson.Str(it.name) },
                    ),
                    "blindSpotReason" to (
                        case.blindSpotReason?.let { dev.kasoti.evalmetrics.MetricJson.Str(it) }
                            ?: dev.kasoti.evalmetrics.MetricJson.Null
                        ),
                    "lines" to dev.kasoti.evalmetrics.MetricJson.Arr(
                        case.lines.map { dev.kasoti.evalmetrics.MetricJson.Str(it) },
                    ),
                ),
            )
        } + "\n"

    private fun emitMrzCorpus(repoRootHint: String, target: String, rows: Int): Int {
        val text = mrzCorpusJsonl(rows)
        if (target == "-") {
            System.out.print(text)
        } else {
            val resolved = java.nio.file.Paths.get(target)
                .let { if (it.isAbsolute) it else Paths.get(repoRootHint).resolve(it) }
            Files.createDirectories(resolved.parent)
            Files.writeString(resolved, text)
            println("wrote $rows MRZ corpus row(s) to $resolved")
        }
        return ExitCode.OK.code
    }

    private fun emitCrosscheck(repoRootHint: String, target: String): Int {
        val text = CrosscheckVectors.jsonLines()
        val path = if (target == "-") {
            System.out.print(text)
            null
        } else {
            val resolved = java.nio.file.Paths.get(target)
                .let { if (it.isAbsolute) it else Paths.get(repoRootHint).resolve(it) }
            Files.createDirectories(resolved.parent)
            Files.writeString(resolved, text)
            resolved
        }
        path?.let {
            println("wrote ${CrosscheckVectors.vectors().size} feature vector(s) to $it")
        }
        val problems = CrosscheckVectors.selfCheck()
        if (problems.isNotEmpty()) {
            problems.forEach { System.err.println("eval: $it") }
            return ExitCode.GATE_FAILED.code
        }
        return ExitCode.OK.code
    }

    /**
     * FAIL and INCOMPLETE are both non-passes, and INVALID dominates both: a run that
     * tuned on the report split is not evidence regardless of what its numbers say.
     */
    private fun statusFor(split: SplitVerdict, acc: Accumulator): RunStatus = when {
        !split.isValid -> RunStatus.INVALID
        acc.gates.any { it.status == GateStatus.FAIL } -> RunStatus.GATE_FAILED
        acc.gates.any { it.status == GateStatus.SKIPPED } -> RunStatus.INCOMPLETE
        else -> RunStatus.PASS
    }

    private fun exitFor(status: RunStatus): ExitCode = when (status) {
        RunStatus.PASS -> ExitCode.OK
        RunStatus.GATE_FAILED -> ExitCode.GATE_FAILED
        RunStatus.INVALID -> ExitCode.INVALID_SPLIT
        RunStatus.INCOMPLETE -> ExitCode.INCOMPLETE
        RunStatus.ERROR -> ExitCode.INTERNAL
    }

    private fun thresholdsFor(suite: Suite): Set<String> = buildSet {
        if (suite.suites.contains("diary")) {
            addAll(
                listOf(
                    ThresholdName.T_ALIAS_HI, ThresholdName.T_WL, ThresholdName.VMAX,
                    ThresholdName.FACILITATOR_MIN_GROUPS, ThresholdName.FACILITATOR_WINDOW_DAYS,
                    ThresholdName.T_FACE_RED, ThresholdName.T_FACE_GREEN,
                ).map { it.name },
            )
        }
        if (suite.suites.contains("macro")) {
            addAll(listOf(ThresholdName.MACRO_MARGIN_RED, ThresholdName.MACRO_MARGIN_AMBER).map { it.name })
        }
        if (suite.suites.contains("face")) {
            addAll(listOf(ThresholdName.T_FACE_RED, ThresholdName.T_FACE_GREEN).map { it.name })
        }
        if (suite.suites.contains("parity")) {
            addAll(listOf(ThresholdName.T_FACE_RED, ThresholdName.T_FACE_GREEN).map { it.name })
        }
    }

    private fun provenanceFor(ledger: ThresholdLedger, inUse: Set<String>): List<ThresholdProvenance> =
        inUse.sorted().map { name ->
            ledger.forName(name) ?: ThresholdProvenance(
                name = name,
                dataset = "unrecorded",
                selectedOnSplit = ThresholdProvenance.UNKNOWN,
                runId = "-",
                selectedAtUtc = "-",
                owner = "-",
                note = "no ledger entry; the harness refuses to certify this threshold's provenance",
            )
        }

    /**
     * The operating points this run used, with their provenance attached.
     *
     * `provisional` is set for any threshold whose ledger entry says it is still untuned:
     * FUSION.md §5 records the shipped values as "TBD by tuning", so a metric resting on
     * one is a pipeline reading, and the summary says so in words.
     */
    private fun operatingPointsFor(
        registry: ThresholdRegistry,
        ledger: ThresholdLedger,
        inUse: Set<String>,
    ): List<OperatingPoint> = ThresholdName.entries
        .filter { it.name in inUse }
        .map { threshold ->
            val entry = ledger.forName(threshold.name)
            val split = entry?.selectedOnSplit ?: ThresholdProvenance.UNKNOWN
            OperatingPoint(
                threshold = threshold.name,
                value = registry[threshold],
                unit = threshold.unit,
                selectedOnSplit = split,
                provisional = entry?.isUntuned != false,
                rationale = "${threshold.tuningDataRef}; owner ${threshold.owner}",
                floor = threshold.floor,
                ceiling = threshold.ceiling,
            )
        }

    private fun readVerdicts(path: Path): List<ParitySuite.Verdict>? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            Files.readAllLines(path).filter { it.isNotBlank() }.map { line ->
                val fields = line.split("|")
                ParitySuite.Verdict(
                    caseId = fields.getOrElse(0) { "" },
                    dataset = fields.getOrElse(1) { "" },
                    verdict = fields.getOrElse(2) { "" },
                    findingCodes = fields.getOrElse(3) { "" }.split(',').filter { it.isNotBlank() },
                    similarity = fields.getOrElse(4) { "0" }.toDoubleOrNull() ?: 0.0,
                )
            }
        }.getOrNull()
    }

    private fun resolveRepoRoot(explicit: String?): Path? {
        if (explicit != null) {
            val path = Paths.get(explicit).toAbsolutePath().normalize()
            return if (Files.isRegularFile(path.resolve("settings.gradle.kts"))) path else null
        }
        var candidate: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun autoRunId(suite: Suite, started: Instant): String {
        val date = DATE_FORMAT.format(started.atZone(ZoneOffset.UTC))
        val entropy = "${System.nanoTime()}-${ProcessHandle.current().pid()}"
        val hash = dev.kasoti.crypto.Hex.encode(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$suite/$date/$entropy".toByteArray(Charsets.UTF_8)),
        ).take(4)
        return "eval-$date-${suite.id}-$hash"
    }

    private fun gitCommit(repoRoot: Path): String =
        runCommand(repoRoot, "git", "rev-parse", "--short", "HEAD") ?: "unknown"

    private fun gitDirty(repoRoot: Path): Boolean =
        runCommand(repoRoot, "git", "status", "--porcelain")?.isNotEmpty() ?: false

    private fun runCommand(root: Path, vararg command: String): String? = runCatching {
        val process = ProcessBuilder(command.toList())
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            output.ifEmpty { null }
        } else {
            null
        }
    }.getOrNull()

    private fun hostDescription(): String = listOfNotNull(
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("os.arch"),
        "${Runtime.getRuntime().availableProcessors()} cpu",
    ).joinToString(" ")

    private fun iso(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(instant)

    private fun printConsole(
        record: RunRecord,
        outDir: Path,
        split: SplitVerdict,
        calibration: CalibrationVerdict,
    ) {
        println()
        println("  run-id  ${record.runId}")
        println("  status  ${record.status}  (exit ${record.exitCode})")
        println("  commit  ${record.commit}${if (record.commitDirty) " (dirty)" else ""}")
        record.headline?.let {
            println("  HEADLINE MRZ mutate-catch (detectable mutants): ${fmtPct(it.value)}")
        }
        record.metrics.firstOrNull { m -> m.name == "mrz.mutate_catch.structurally_blind" }?.let {
            println("          structurally blind (reported separately, not in the headline): ${it.value?.toInt()}")
        }
        println("  split   ${split.summary}")
        if (!calibration.accepted) {
            println("  calib   REFUSED — ${calibration.reason}")
        }
        record.notes.filter { it.contains(MacroSuite.SYNTHETIC_BANNER) }.forEach {
            println("  !!!!!   $it")
        }
        for (gate in record.gates) {
            val mark = when (gate.status) {
                GateStatus.PASS -> "PASS"
                GateStatus.FAIL -> "FAIL"
                GateStatus.SKIPPED -> "SKIP"
                GateStatus.INVALID -> "INVAL"
            }
            println("  [$mark] ${gate.id.padEnd(18)} ${gate.observed}")
            if (gate.status == GateStatus.SKIPPED) {
                println("         ↳ skipped, NOT passed: ${gate.detail.take(160)}")
            }
        }
        if (record.failures.isNotEmpty()) {
            println("  ${record.failures.size} failing case(s) → failures/failures.md")
        }
        println()
        println("  wrote ${outDir.resolve("metrics.json")}")
        println("  wrote ${outDir.resolve("summary.md")}")
        if (record.status != RunStatus.PASS) {
            println()
            println("  This run is NOT a pass. Do not quote its numbers as a gate result.")
        }
        println()
    }

    private fun fmtPct(v: Double?): String {
        if (v == null) return "—"
        val body = "%.4f".format(v * 100).trimEnd('0').trimEnd('.')
        return "$body%"
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

        /**
         * Why D-FACE is absent, stated once so the face suite's message and this note cannot
         * drift. It is a data fact, not an excuse: no consented face crop may be committed
         * (DATA.md §1) and the collection SOP has not been run.
         */
        const val NO_FACE_DATA_NOTE =
            "No D-FACE media exists in the repository: face crops are consented biometric data " +
                "that must never be committed (DATA.md §1) and the collection SOP (DATA.md §4) " +
                "has not been run. There is also no bundled embedding model (DESIGN §6), so a " +
                "face crop could not be turned into a vector even if one were present."

        /**
         * The calibration device id the host-side harness evaluates against. Not a real
         * serial: it names the *evaluation host's* calibration slot, so a laptop with no
         * calibration file fails closed rather than borrowing a phone's gains.
         */
        const val HostDeviceId = "TEST-HARNESS-JVM"
    }
}
