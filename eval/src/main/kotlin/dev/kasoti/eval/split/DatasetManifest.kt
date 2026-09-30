package dev.kasoti.eval.split

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manifest of dataset splits, as declared in `eval/data/manifests/datasets.json`.
 *
 * The manifest is the *authority* for what split a dataset has. The harness does not infer
 * splits from filenames or from directory depth, because a directory depth is exactly the
 * thing a motivated person would quietly change.
 */
@Serializable
data class DatasetManifest(
    val version: String,
    val datasets: List<DatasetEntry>,
) {
    fun byId(id: String): DatasetEntry? = datasets.firstOrNull { it.id == id }

    companion object {
        /** Split names the protocol recognises. */
        const val TRAIN = "train"
        const val TUNE = "tune"
        const val REPORT = "report"
        const val HELD_OUT = "held-out"
        const val ALL_TEST = "all-test"
        const val SYNTHETIC = "synthetic"

        val ALL_SPLITS = setOf(TRAIN, TUNE, REPORT, HELD_OUT, ALL_TEST, SYNTHETIC)
    }
}

@Serializable
data class DatasetEntry(
    val id: String,
    /** Splits this dataset is divided into, in the order EVAL.md §2 lists them. */
    val splits: List<String>,
    /**
     * What a row is grouped by when splitting: `source-doc`, `identity`, `none`.
     *
     * EVAL.md §2 is explicit that splitting by patch or by frame is leakage, because two
     * crops of one document share every pixel-level artefact. The harness refuses a
     * manifest that declares `patch` or `frame`.
     */
    val splitUnit: String,
    /** The split whose numbers are allowed into the deck. Others are tuning-only. */
    val reportSplit: String? = null,
    val gate: String? = null,
    val note: String = "",
) {
    fun isReport(split: String): Boolean = reportSplit != null && split == reportSplit

    companion object {
        const val UNIT_SOURCE_DOC = "source-doc"
        const val UNIT_IDENTITY = "identity"
        const val UNIT_NONE = "none"

        /** Units that constitute leakage under EVAL.md §2. */
        val LEAKY_UNITS = setOf("patch", "frame", "image", "file", "row")

        val LEGAL_UNITS = setOf(UNIT_SOURCE_DOC, UNIT_IDENTITY, UNIT_NONE)
    }
}

/** Severity of a manifest problem, chosen so the harness can fail the right way. */
@Serializable
enum class ManifestProblemSeverity {
    /** Breaks the anti-gaming guarantee: the run is INVALID regardless of the numbers. */
    FATAL,

    /** The run cannot be trusted on a specific metric: that gate becomes SKIPPED. */
    BLOCKING,

    /** Worth saying out loud in `summary.md`, does not stop the run. */
    ADVISORY,
}

@Serializable
data class ManifestProblem(
    val severity: ManifestProblemSeverity,
    val code: String,
    val subject: String,
    val detail: String,
)

/**
 * Structural validation of the dataset manifest itself.
 *
 * This runs before any number is computed, so a manifest that declares leaky splits fails
 * the run even when every metric in it happens to be fine — otherwise "the numbers were
 * good" would launder a broken experimental design.
 */
object DatasetManifestValidator {

    fun validate(manifest: DatasetManifest): List<ManifestProblem> {
        val problems = mutableListOf<ManifestProblem>()

        if (manifest.datasets.isEmpty()) {
            problems += ManifestProblem(
                ManifestProblemSeverity.FATAL,
                "MANIFEST_EMPTY",
                manifest.version,
                "the dataset manifest declares no datasets, so no split discipline can be enforced",
            )
        }

        val seen = mutableSetOf<String>()
        for (entry in manifest.datasets) {
            if (!seen.add(entry.id)) {
                problems += ManifestProblem(
                    ManifestProblemSeverity.FATAL,
                    "MANIFEST_DUPLICATE_DATASET",
                    entry.id,
                    "dataset '${entry.id}' is declared more than once; a shadowed declaration could " +
                        "declare a different split unit than the one that is read",
                )
            }

            if (entry.splits.isEmpty()) {
                problems += ManifestProblem(
                    ManifestProblemSeverity.FATAL,
                    "MANIFEST_NO_SPLITS",
                    entry.id,
                    "dataset '${entry.id}' declares no split, so rows cannot be attributed to a split",
                )
            }
            for (split in entry.splits) {
                if (split !in DatasetManifest.ALL_SPLITS) {
                    problems += ManifestProblem(
                        ManifestProblemSeverity.FATAL,
                        "MANIFEST_UNKNOWN_SPLIT",
                        "${entry.id}/$split",
                        "'$split' is not one of ${DatasetManifest.ALL_SPLITS.joinToString()}",
                    )
                }
            }
            if (entry.splits.distinct().size != entry.splits.size) {
                problems += ManifestProblem(
                    ManifestProblemSeverity.FATAL,
                    "MANIFEST_DUPLICATE_SPLIT",
                    entry.id,
                    "dataset '${entry.id}' declares a split twice: ${entry.splits}",
                )
            }

            if (entry.splitUnit in DatasetEntry.LEAKY_UNITS) {
                problems += ManifestProblem(
                    ManifestProblemSeverity.FATAL,
                    "MANIFEST_LEAKY_SPLIT_UNIT",
                    entry.id,
                    "dataset '${entry.id}' is split by '${entry.splitUnit}', but EVAL.md §2 requires " +
                        "splitting by source document or identity; crops of one document share " +
                        "pixel-level artefacts and would leak across the split",
                )
            }
            if (entry.splitUnit !in DatasetEntry.LEGAL_UNITS) {
                problems += ManifestProblem(
                    ManifestProblemSeverity.FATAL,
                    "MANIFEST_UNKNOWN_SPLIT_UNIT",
                    entry.id,
                    "split unit '${entry.splitUnit}' is not one of ${DatasetEntry.LEGAL_UNITS.joinToString()}",
                )
            }

            if (entry.reportSplit != null) {
                if (entry.reportSplit !in entry.splits) {
                    problems += ManifestProblem(
                        ManifestProblemSeverity.FATAL,
                        "MANIFEST_REPORT_SPLIT_ABSENT",
                        entry.id,
                        "report split '${entry.reportSplit}' is not among the declared splits " +
                            "${entry.splits}; report-split metrics could not be attributed",
                    )
                }
                if (entry.splits.size == 1) {
                    problems += ManifestProblem(
                        ManifestProblemSeverity.ADVISORY,
                        "MANIFEST_SINGLE_SPLIT",
                        entry.id,
                        "dataset '${entry.id}' has a single split '${entry.splits.first()}'; there is " +
                            "nothing held back, so a number from it is a training-set number",
                    )
                }
            }
        }

        return problems
    }
}
