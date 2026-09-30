package dev.kasoti.eval.suites

import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessLabel
import dev.kasoti.factory.Spectrum
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The specimen patches `eval/tools/synth_macros.py --emit-specimens` writes.
 *
 * ## Why a vector dump and not pixels
 *
 * `eval/data/fixtures/README.md` admits exactly three kinds of file into that directory: a recipe
 * that deterministically produces pixels, a **vector dump**, or generated MRZ lines. It says a
 * patch-shaped `.png` is "what you actually want is a recipe", and it is right: a committed image
 * whose provenance is a committed script is one link of the chain weaker than a committed vector
 * whose provenance is that same script. So the pixels stay in the generator and what is committed
 * is the 66-float vector `ProcessClassifier.extract` produces for them.
 *
 * That is a real weakening and it is worth being exact about it. What is being trusted here is
 * `extract_features.extract`, the NumPy extractor — **not** `:core`. What makes that acceptable is
 * `eval/tools/cross_check.py`, which measures the NumPy↔Kotlin feature agreement at a stated
 * tolerance on every run and fails the build outside it. This file is one more step along the same
 * chain the repository already relies on for its seven shared recipes, not a new assumption.
 *
 * ## Regenerating
 *
 * ```bash
 * python3 eval/tools/synth_macros.py \\
 *     --emit-specimens eval/data/fixtures/macro_synth/specimens.jsonl
 * ```
 *
 * [assertFresh] checks the `generator` stamp against the seed the class specimens are generated
 * from, so a dump left behind by a different seed fails rather than quietly testing the wrong
 * thing. A dump is not stale the way a cache is stale; it is stale the way a *renamed* fixture is.
 */
@Serializable
data class MacroSpecimenFile(
    val id: String,
    /**
     * A `ProcessLabel` name for the seven class specimens, or a specimen name for the ambiguous
     * ones. Named `label` because that is the key `train_svm.py` reads.
     */
    val label: String,
    /** `class` = expect this label. `abstain` = expect the system to decline to name a class. */
    val expectation: String,
    val sourceDoc: String,
    val synthetic: Boolean,
    val generator: String,
    val features: List<Float>,
) {
    val isClassSpecimen: Boolean get() = expectation == "class"

    /**
     * The inverse of `MacroFeatures.toVector`.
     *
     * Written out by hand rather than by adding a `fromVector` to `:core`, because
     * `toVector`/`fromVector` being a matched pair in production code would invite somebody to
     * reconstruct a patch from a vector and believe it is the patch. Here it exists only to put a
     * committed vector back into the type the classifier takes.
     */
    fun toMacroFeatures(): MacroFeatures {
        require(features.size == MacroFeatures.TOTAL) {
            "$id has ${features.size} features, expected ${MacroFeatures.TOTAL}"
        }
        val bins = FloatArray(MacroFeatures.LBP_BINS) { features[MacroFeatures.NUMERIC_FEATURES + it] }
        return MacroFeatures(
            peakFrequency = features[0] * Spectrum.PATCH,
            // `toVector` stores ln1p(peakiness)/6, so expm1 inverts it.
            peakiness = kotlin.math.expm1(features[1] * 6f),
            bandEnergy = features[2],
            highFrequencyEnergy = features[3],
            spectralSlope = features[4],
            stdDev = features[5],
            edgeDensity = features[6],
            lbpBins = bins,
        )
    }
}

object SpecimenFixtures {

    const val PATH = "eval/data/fixtures/macro_synth/specimens.jsonl"

    /**
     * The seed `synth_macros.py` generates the class specimens from.
     *
     * Duplicated as a literal on purpose. If this were read out of the generator, [assertFresh]
     * would compare the dump against itself and never fail, which is the failure mode a
     * "consistency check" has when both sides come from the thing it is checking.
     */
    const val GENERATOR_SEED = "2026101"

    private val json = Json { ignoreUnknownKeys = false }

    /** @return empty when the file is absent, so a checkout without it still runs. */
    fun loadIfPresent(repoRoot: Path): List<MacroSpecimenFile> {
        val path = repoRoot.resolve(PATH)
        if (!Files.isRegularFile(path)) return emptyList()
        return Files.readAllLines(path).filter { it.isNotBlank() }.map { line ->
            json.decodeFromString(MacroSpecimenFile.serializer(), line)
        }
    }

    fun classSpecimens(rows: List<MacroSpecimenFile>): List<MacroSpecimenFile> =
        rows.filter { it.isClassSpecimen }

    fun ambiguousSpecimens(rows: List<MacroSpecimenFile>): List<MacroSpecimenFile> =
        rows.filterNot { it.isClassSpecimen }

    /**
     * @return the reasons the dump cannot be trusted, or an empty list. A refusal here is a failed
     *   run, not a warning: a test that quietly used a dump from a different seed would report a
     *   model result for a model that had never seen those patches.
     */
    fun assertFresh(rows: List<MacroSpecimenFile>): List<String> {
        val problems = mutableListOf<String>()
        if (rows.isEmpty()) {
            problems += "$PATH is empty or absent; regenerate it with " +
                "`python3 eval/tools/synth_macros.py --emit-specimens $PATH`"
            return problems
        }
        val classRows = classSpecimens(rows)
        val labels = classRows.map { it.label }.sorted()
        if (labels != ProcessLabel.entries.map { it.name }.sorted()) {
            problems += "$PATH covers ${labels} as class specimens, expected one per ProcessLabel"
        }
        val wrongSeed = rows.firstOrNull { !it.generator.endsWith("@$GENERATOR_SEED") }
        if (wrongSeed != null) {
            problems += "$PATH was generated by '${wrongSeed.generator}', but the committed seed is $GENERATOR_SEED"
        }
        val unmarked = rows.firstOrNull { !it.synthetic }
        if (unmarked != null) {
            problems += "${unmarked.id} is not marked synthetic, so the corpus stopped declaring what it is"
        }
        val wrongWidth = rows.firstOrNull { it.features.size != MacroFeatures.TOTAL }
        if (wrongWidth != null) {
            problems += "${wrongWidth.id} has ${wrongWidth.features.size} features, expected ${MacroFeatures.TOTAL}"
        }
        return problems
    }
}
