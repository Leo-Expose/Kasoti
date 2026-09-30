package dev.kasoti.eval.suites

import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.SvmModel
import dev.kasoti.factory.SvmModelReader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk shape of `svm_print_v1.json`, the artefact `eval/tools/train_svm.py` writes.
 *
 * **The parsing lives in `:core` now** ([SvmModelReader]) and this file is only the harness's own
 * index into `eval/models/`. It used to carry a second, independent implementation of the same
 * shape with `ignoreUnknownKeys = false`; two readers of a weights file is one too many, because
 * the failure they share is silent — a reader that reorders nothing, or accepts a name it should
 * not, still returns a model and a number.
 */
@Serializable
data class SvmModelFile(
    val version: String,
    /** In any order; [SvmModelReader] reorders the rows into `ProcessLabel` declaration order. */
    val labels: List<String>,
    /** One row per class, each `MacroFeatures.TOTAL` long, in `MacroFeatures.toVector()` order. */
    val weights: List<List<Float>>,
    val bias: List<Float>,
    val featureNames: List<String>,
    val trainingRunId: String,
    /**
     * True when the model was fitted on generated textures rather than D-MACRO. Carried into every
     * gate and metric so a synthetic model can never be presented as a gate-eligible one.
     */
    @SerialName("synthetic") val synthetic: Boolean = false,
    @SerialName("splitPolicy") val splitPolicy: String = "",
    @SerialName("sourceDocCount") val sourceDocCount: Int = 0,
    @SerialName("trainedOn") val trainedOn: String = "",
) {
    /** The bytes `:core` will read, assembled from these fields in the on-disk order. */
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("""  "version": ${version.json()},""")
        appendLine("""  "labels": ${labels.toJsonArray()},""")
        appendLine("""  "featureNames": ${featureNames.toJsonArray()},""")
        appendLine("""  "weights": ${weights.joinToString(", ", "[", "]") { it.joinToString(",", "[", "]") }},""")
        appendLine("""  "bias": ${bias.joinToString(",", "[", "]") { it.toString() }},""")
        appendLine("""  "trainingRunId": ${trainingRunId.json()}""")
        appendLine("}")
    }

    fun toSvmModel(): Result<SvmModel> = runCatching {
        SvmModelReader.parse(toJson(), origin = "svm model file").model
    }

    private fun String.json(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun List<String>.toJsonArray(): String = joinToString(",", "[", "]") { it.json() }
}

/**
 * Loads a trained model, or explains why it could not.
 *
 * A missing model is a skip with a reason; a *malformed* one is a hard error. The distinction
 * matters: "we have not trained yet" is a hole in the evidence, while "we have a weights file the
 * loader cannot read" is a bug that would otherwise surface as a plausible-looking accuracy.
 */
object SvmModelLoader {

    /** D-MACRO. Present once DATA.md §3's collection SOP has been run. */
    const val REAL_PATH = "eval/models/svm_print_v1.json"

    /**
     * The committed synthetic model. Not gate-eligible under any circumstances (SPEC.md §7), but
     * it is what lets the demo classify instead of abstaining on every patch, and it is committed
     * precisely so the demo is reproducible.
     */
    const val SYNTHETIC_PATH = "eval/models/svm_print_v1_synthetic.json"

    /** Kept for the callers that named the old constant; it is the *real* model's path. */
    const val DEFAULT_PATH = REAL_PATH

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    /** @return null when [path] does not exist; throws when it exists but is unusable. */
    fun loadIfPresent(path: Path): TrainedModel? {
        if (!Files.isRegularFile(path)) return null
        val file = Files.readString(path)
        val decoded = json.decodeFromString(SvmModelFile.serializer(), file)
        val model = decoded.toSvmModel().getOrElse { error ->
            throw IllegalStateException(
                "svm model at $path is unusable: ${error.message}. Refusing to classify with a " +
                    "half-read model — a shuffled feature order produces a number that looks " +
                    "like a metric and is not one.",
            )
        }
        return TrainedModel(
            model = model,
            path = path.toString(),
            synthetic = decoded.synthetic || file.contains("\"synthetic\": true"),
            splitPolicy = decoded.splitPolicy,
            sourceDocCount = decoded.sourceDocCount,
            trainedOn = decoded.trainedOn,
            resolution = ModelResolution.DIRECT,
        )
    }

    /**
     * Which model this run got, and why.
     *
     * Carried into the gate detail and printed on the console, because "the harness classified
     * with a model" and "the harness classified with the *synthetic* model because there is no
     * real one" are different facts and only one of them is evidence.
     */
    enum class ModelResolution(val label: String, val note: String) {
        DIRECT("loaded", "read from the path requested"),
        REAL_PREFERRED(
            "real",
            "D-MACRO model present and used; the synthetic fallback was not touched",
        ),
        SYNTHETIC_FALLBACK(
            "SYNTHETIC FALLBACK",
            "no D-MACRO model at $REAL_PATH, so the committed synthetic model was used. Its numbers " +
                "are a property of eval/tools/synth_macros.py and support no claim about print " +
                "processes. This is NOT the D-MACRO gate of SPEC.md §7.",
        ),
        NONE("absent", "no model file found; the macro suite reports SKIPPED with a reason"),
    }

    /**
     * The real model if there is one, the committed synthetic model otherwise, and the reason
     * either way.
     *
     * The order is the point. A real model is always preferred even when the synthetic one is
     * present, because the alternative is a run that quietly keeps reporting the synthetic number
     * after real data has landed — which is the most likely way this whole mechanism goes wrong in
     * practice.
     */
    fun resolve(repoRoot: Path): Resolution {
        val real = repoRoot.resolve(REAL_PATH)
        if (Files.isRegularFile(real)) {
            return Resolution(
                loadIfPresent(real)!!.withResolution(ModelResolution.REAL_PREFERRED),
                ModelResolution.REAL_PREFERRED,
                real.toString(),
            )
        }
        val synthetic = repoRoot.resolve(SYNTHETIC_PATH)
        if (Files.isRegularFile(synthetic)) {
            return Resolution(
                loadIfPresent(synthetic)!!.withResolution(ModelResolution.SYNTHETIC_FALLBACK),
                ModelResolution.SYNTHETIC_FALLBACK,
                synthetic.toString(),
            )
        }
        return Resolution(null, ModelResolution.NONE, "no $REAL_PATH and no $SYNTHETIC_PATH")
    }

    data class Resolution(
        val model: TrainedModel?,
        val which: ModelResolution,
        val path: String,
    )
}

/** Re-exported so readers do not have to follow the import for the feature vector width. */
val MACRO_FEATURE_WIDTH: Int = MacroFeatures.TOTAL
