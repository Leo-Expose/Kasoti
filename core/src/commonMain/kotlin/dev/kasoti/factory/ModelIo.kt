package dev.kasoti.factory

/**
 * The canonical reader for `svm_print_v1.json` (DESIGN.md §6).
 *
 * ## Why it lives in `:core`
 *
 * Three call sites read this artefact and all three must read it the same way: the Android macro
 * stage, the desktop console, and the `:eval` harness. When the reader was duplicated, the
 * desktop copy and the harness copy had already drifted — one accepted a named-weight form the
 * other rejected — and a divergence there is invisible, because a model read with the wrong
 * feature *order* still scores a plausible number for every patch. One implementation, in the
 * module both platforms already depend on, is the only way that stays true. `app-desktop`'s
 * `SvmModelFile` is now a thin delegate to this.
 *
 * ## What it refuses, and why each refusal matters
 *
 * - **A wrong feature count.** [SvmModel] requires `MacroFeatures.TOTAL` names and that many
 *   weights per class. A model file is a *list of numbers*; nothing about it says which number is
 *   which. So a file whose width is wrong describes a model over a vector that does not exist.
 * - **A label set that is not exactly the seven `ProcessLabel` entries.** [SvmModel] requires one
 *   weight vector per class, and fusion has a `when` over the seven. A model covering six would
 *   fail at the cast; a model covering eight would score a class nothing can act on.
 * - **A missing `trainingRunId`.** A model that cannot be traced to a run cannot be tuned, cannot
 *   be compared against another, and cannot appear in an audit record. It is not evidence.
 * - **A weight that is not finite.** A `NaN` in a weight row produces a `NaN` score, and a `NaN`
 *   softmax is a `NaN` margin, and a `NaN` comparison is false — so the argmax silently returns
 *   the *first* class. That is a confident wrong label from a corrupt file, which is the one
 *   failure mode nobody catches by looking at a console.
 *
 * ## Provenance travels with the model
 *
 * [LoadedSvmModel.synthetic] is the flag the whole system hangs its honesty on: a model fitted on
 * generated textures can exercise the entire macro path and is still never gate-eligible
 * (SPEC.md §7, EVAL.md §2). It is carried in the file, read here, and handed to the caller, so
 * no platform has to re-derive provenance from a filename.
 */
class SvmModelFormatException(message: String) : IllegalArgumentException(message)

/** A model file plus where it came from and what it was trained on. */
class LoadedSvmModel(
    val model: SvmModel,
    /** Where the bytes came from, for a diagnostic that names a file rather than a class. */
    val origin: String,
    /** True when fitted on generated textures. **Never** gate-eligible (SPEC.md §7). */
    val synthetic: Boolean,
    val splitPolicy: String,
    val trainedOn: String,
    val sourceDocCount: Int,
) {
    /** What an operator should be told when this model produces a label. */
    val provenance: String
        get() = buildString {
            append(model.version).append(" @ ").append(model.trainingRunId)
            if (synthetic) {
                append(" — SYNTHETIC, trained on generated textures, never gate-eligible")
                if (trainedOn.isNotEmpty()) append(" (").append(trainedOn).append(')')
            } else if (trainedOn.isNotEmpty()) {
                append(" — trained on ").append(trainedOn)
            }
        }
}

object SvmModelReader {

    /**
     * The feature names this loader expects, in `MacroFeatures.toVector()` order.
     *
     * Camel-cased, because that is `MacroFeatures`' field names and `app-desktop`'s
     * `SvmModelFile.expectedFeatureNames()` — the two must agree, or the desktop and the phone
     * would accept different files. The trainer writes snake_case
     * (`extract_features.feature_names()`); the mismatch is deliberate and documented there, and
     * [checkFeatureNames] accepts either so the file is self-describing rather than
     * format-locked.
     */
    fun expectedFeatureNames(): List<String> = listOf(
        "peakFrequency", "peakiness", "bandEnergy",
        "highFrequencyEnergy", "spectralSlope", "stdDev", "edgeDensity",
    ) + (0 until MacroFeatures.LBP_BINS).map { "lbp$it" }

    /** The snake_case spelling `extract_features.py` writes. Accepted for the same reason. */
    private fun expectedFeatureNamesSnake(): List<String> = listOf(
        "peak_frequency", "peakiness", "band_energy", "high_frequency_energy",
        "spectral_slope", "std_dev", "edge_density",
    ) + (0 until MacroFeatures.LBP_BINS).map { "lbp_%02d".format(it) }

    /**
     * @throws SvmModelFormatException on anything the file gets wrong. Never returns a partially
     *   read model: a model with four of seven classes is not a model, and defaulting the rest
     *   would produce confident labels with no weights behind them.
     */
    fun parse(text: String, origin: String = "<inline>"): LoadedSvmModel {
        val root = try {
            Json.parse(text).asObjectOrNull
        } catch (e: JsonSyntaxException) {
            throw SvmModelFormatException("$origin is not valid JSON: ${e.message}")
        } ?: throw SvmModelFormatException("$origin is not a JSON object")

        val version = root.string("version") ?: throw SvmModelFormatException("$origin has no `version`")
        val runId = root.string("trainingRunId")
            ?: throw SvmModelFormatException(
                "$origin has no `trainingRunId`; a model that cannot be traced to a tuning run is not usable",
            )

        val featureNames = root.stringList("featureNames")
        if (featureNames.size != MacroFeatures.TOTAL) {
            throw SvmModelFormatException(
                "$origin declares ${featureNames.size} feature names, expected ${MacroFeatures.TOTAL}",
            )
        }
        checkFeatureNames(origin, featureNames)

        val labelNames = root.stringList("labels")
        if (labelNames.size != ProcessLabel.entries.size) {
            throw SvmModelFormatException(
                "$origin covers ${labelNames.size} classes, expected ${ProcessLabel.entries.size}",
            )
        }
        val labels = labelNames.map { name ->
            ProcessLabel.entries.firstOrNull { it.name == name }
                ?: throw SvmModelFormatException("$origin names unknown process class '$name'")
        }
        if (labels.toSet() != ProcessLabel.entries.toSet()) {
            throw SvmModelFormatException(
                "$origin must cover exactly ${ProcessLabel.entries.joinToString(", ") { it.name }}, " +
                    "got ${labelNames.joinToString(", ")}",
            )
        }

        val weightRows = root.array("weights")?.items
            ?: throw SvmModelFormatException("$origin has no `weights`")
        if (weightRows.size != labels.size) {
            throw SvmModelFormatException(
                "$origin has ${weightRows.size} weight vectors for ${labels.size} labels",
            )
        }
        val fileOrder = Array(labels.size) { index ->
            val values = weightRows[index].toFloatArray(origin, "weights[$index]")
            if (values.size != MacroFeatures.TOTAL) {
                throw SvmModelFormatException(
                    "$origin: weights[$index] has ${values.size} entries, expected ${MacroFeatures.TOTAL}",
                )
            }
            values
        }
        val fileBias = root.array("bias")?.toFloatArray(origin, "bias")
            ?: throw SvmModelFormatException("$origin has no `bias`")
        if (fileBias.size != labels.size) {
            throw SvmModelFormatException("$origin has ${fileBias.size} biases for ${labels.size} labels")
        }

        // Reorder the rows into `ProcessLabel` declaration order. `SvmModel.scores` pairs
        // `labels[i]` with `weights[i]` and the callers index by enum, so handing it the file's
        // order while declaring declaration order would turn a permuted file into a *different
        // model* that scores plausibly on every patch. No shape check can catch that, because
        // every shape is right; the only defence is not to trust the order.
        val order = ProcessLabel.entries.map { label -> labels.indexOf(label) }

        return LoadedSvmModel(
            model = SvmModel(
                version = version,
                // `ProcessLabel` declaration order, not file order: `SvmModel.scores` pairs
                // `labels[i]` with `weights[i]`, and the callers index by enum, so reordering here
                // is what makes a permuted file behave like a model instead of failing.
                labels = ProcessLabel.entries.toList(),
                weights = order.map { fileOrder[it] }.toTypedArray(),
                bias = order.map { fileBias[it] }.toFloatArray(),
                featureNames = featureNames,
                trainingRunId = runId,
            ),
            origin = origin,
            synthetic = root.bool("synthetic") ?: false,
            splitPolicy = root.string("splitPolicy") ?: "",
            trainedOn = root.string("trainedOn") ?: "",
            sourceDocCount = root.int("sourceDocCount") ?: 0,
        )
    }

    /**
     * The order of a 66-number row is the model's whole meaning, so the file is required to say
     * which order it used — and required to say it *correctly*.
     *
     * A wrong name list is far more dangerous than a missing one: the names are metadata, and a
     * loader that trusted them would happily score a permuted file. Both the camelCase spelling
     * `:core` and `app-desktop` use and the snake_case spelling the trainer writes are accepted,
     * and nothing else is.
     */
    private fun checkFeatureNames(origin: String, actual: List<String>) {
        if (actual == expectedFeatureNames() || actual == expectedFeatureNamesSnake()) return
        val expected = expectedFeatureNames()
        val firstBad = actual.indices.firstOrNull { actual[it] != expected[it] }
        throw SvmModelFormatException(
            "$origin: feature name ${firstBad?.let { actual[it] }} at index ${firstBad ?: 0} is not the " +
                "expected ${expected.getOrNull(firstBad ?: 0)}; the model's numbers are only " +
                "meaningful in MacroFeatures.toVector() order",
        )
    }

    // ---------------------------------------------------------------- field access

    private fun JsonValue.Obj.string(key: String): String? = when (val v = fields[key]) {
        null, NullValue -> null
        is JsonValue.Str -> v.value
        else -> throw SvmModelFormatException("`$key` is a string field, found ${v.kindName()}")
    }

    private fun JsonValue.Obj.bool(key: String): Boolean? = when (val v = fields[key]) {
        null, NullValue -> null
        is JsonValue.Bool -> v.value
        else -> throw SvmModelFormatException("`$key` is a boolean field, found ${v.kindName()}")
    }

    private fun JsonValue.Obj.int(key: String): Int? = when (val v = fields[key]) {
        null, NullValue -> null
        is JsonValue.Num -> v.value.toInt()
        else -> throw SvmModelFormatException("`$key` is a number field, found ${v.kindName()}")
    }

    private fun JsonValue.Obj.stringList(key: String): List<String> = when (val v = fields[key]) {
        null -> emptyList()
        is JsonValue.Arr -> v.items.map {
            it.asStringOrNull ?: throw SvmModelFormatException("`$key` must hold only strings")
        }
        else -> throw SvmModelFormatException("`$key` is an array field, found ${v.kindName()}")
    }

    private fun JsonValue.Obj.array(key: String): JsonValue.Arr? = when (val v = fields[key]) {
        null, NullValue -> null
        is JsonValue.Arr -> v
        else -> throw SvmModelFormatException("`$key` is an array field, found ${v.kindName()}")
    }

    private fun JsonValue.toFloatArray(origin: String, what: String): FloatArray {
        val items = asArrayOrNull?.items
            ?: throw SvmModelFormatException("$origin: $what is not an array")
        val out = FloatArray(items.size)
        for (i in items.indices) {
            val value = items[i].asNumberOrNull
                ?: throw SvmModelFormatException("$origin: $what[$i] is not a number")
            val narrowed = value.toFloat()
            if (!narrowed.isFinite()) {
                // The one refusal here that has no happy path at all: a NaN score has a NaN
                // softmax, and every comparison against NaN is false, so the argmax would return
                // the first class with full confidence.
                throw SvmModelFormatException(
                    "$origin: $what[$i] is $value, which does not fit a finite float",
                )
            }
            out[i] = narrowed
        }
        return out
    }

    private fun JsonValue.kindName(): String = when (this) {
        is JsonValue.Str -> "a string"
        is JsonValue.Num -> "a number"
        is JsonValue.Bool -> "a boolean"
        is JsonValue.Arr -> "an array"
        is JsonValue.Obj -> "an object"
        NullValue -> "null"
    }
}
