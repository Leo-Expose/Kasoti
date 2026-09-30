package dev.kasoti.eval

/**
 * Parsed command line for `:eval run` (EVAL.md §3).
 *
 * Shape:
 * ```
 * :eval run --suite=smoke|full|parity --run-id=auto --out=eval/runs/
 * ```
 * The suite may also be given positionally, because `AGENTS.md` §1 and every runbook use
 * `--args="smoke"`. Both spellings are accepted; the flag wins if both are present.
 */
data class EvalArgs(
    val suite: Suite,
    val runId: String,
    val runIdWasAuto: Boolean,
    val outDir: String,
    val repoRoot: String?,
    val deviceSerial: String?,
    val macRows: Int,
    val verbose: Boolean,
    /** Write the Kotlin feature vectors for the shared synthetic fixtures and exit. */
    val emitCrosscheck: String?,

    /** Write `:core`'s normative MRZ corpus as JSONL and exit. */
    val emitMrzCorpus: String?,
) {
    companion object {

        const val USAGE: String = """
:eval run — evaluation harness CLI (EVAL.md §3)

  --suite=smoke|full|parity   suite to run (also accepted positionally: --args="smoke")
  --run-id=auto|<id>          run identifier; 'auto' derives eval-<date>-<suite>-<hash>
  --out=<dir>                 run output root (default eval/runs/)
  --repo-root=<dir>           override repository-root discovery
  --device=<serial>           Android serial for the parity suite (default: adb discovery)
  --mac-rows=<n>              MRZ corpus rows for the smoke suite (default 10000)
  --emit-crosscheck=<path>    write :core's feature vectors for the shared synthetic
                              fixtures as JSONL and exit (input to cross_check.py)
  --emit-mrz-corpus=<path>    write :core's normative MRZ corpus as JSONL and exit
                              (formatted for review by eval/tools/gen_mrz_corpus.py)
  --verbose                   print per-suite progress

Exit codes: 0 pass · 1 usage · 2 gate failed · 3 INVALID (report-split tuning) ·
            4 INCOMPLETE (skipped suite) · 70 internal
"""

        /** Thrown for anything the user can fix by re-typing the command. */
        class UsageError(message: String) : IllegalArgumentException(message)

        fun parse(argv: Array<String>): EvalArgs {
            var suite: Suite? = null
            var runId: String? = null
            var outDir = "eval/runs/"
            var repoRoot: String? = null
            var device: String? = null
            var macRows = 10_000
            var verbose = false
            var crosscheck: String? = null
            var mrzCorpus: String? = null

            for (raw in argv) {
                val arg = raw.trim()
                if (arg.isEmpty()) continue
                when {
                    arg.startsWith("--") -> {
                        val (key, value) = splitFlag(arg.removePrefix("--"))
                        when (key) {
                            "suite" -> suite = parseSuite(value)
                            "run-id" -> runId = value
                            "out" -> outDir = value
                            "repo-root" -> repoRoot = value
                            "device" -> device = value
                            "mac-rows" -> macRows = value.toIntOrNull()
                                ?: throw UsageError("--mac-rows must be an integer, got '$value'")

                            "emit-crosscheck" -> crosscheck = value
                            "emit-mrz-corpus" -> mrzCorpus = value
                            "verbose", "v" -> verbose = true
                            "help", "h" -> throw UsageError(USAGE.trim())
                            else -> throw UsageError("unknown flag --$key\n\n$USAGE")
                        }
                    }

                    arg.startsWith("-") -> throw UsageError("unknown flag $arg\n\n$USAGE")
                    else -> suite = parseSuite(arg)
                }
            }

            // `--emit-crosscheck` is the one mode that needs no suite: it exists so the
            // Python cross-check can obtain :core's feature vectors without a full run, and
            // requiring a suite to say so would make it awkward to use from a script.
            val chosen = suite ?: if (crosscheck != null || mrzCorpus != null) {
                Suite.SMOKE
            } else {
                throw UsageError("no suite given\n\n$USAGE")
            }
            val mac = when (chosen) {
                Suite.FULL -> FULL_MAC_ROWS
                else -> macRows
            }
            if (mac < 1) throw UsageError("--mac-rows must be positive, got $mac")

            return EvalArgs(
                suite = chosen,
                runId = runId ?: "auto",
                runIdWasAuto = runId == null || runId == "auto",
                outDir = outDir,
                repoRoot = repoRoot,
                deviceSerial = device,
                macRows = mac,
                verbose = verbose,
                emitCrosscheck = crosscheck,
                emitMrzCorpus = mrzCorpus,
            )
        }

        /** The gate corpus size from SPEC.md §7 / EVAL.md §2. Never silently reduced. */
        const val FULL_MAC_ROWS: Int = 10_000

        private fun splitFlag(flag: String): Pair<String, String> {
            val eq = flag.indexOf('=')
            return if (eq < 0) flag to "" else flag.substring(0, eq) to flag.substring(eq + 1)
        }

        private fun parseSuite(value: String): Suite = when (value.lowercase()) {
            "smoke" -> Suite.SMOKE
            "full" -> Suite.FULL
            "parity" -> Suite.PARITY
            else -> throw UsageError("unknown suite '$value' (expected smoke|full|parity)\n\n$USAGE")
        }
    }
}
