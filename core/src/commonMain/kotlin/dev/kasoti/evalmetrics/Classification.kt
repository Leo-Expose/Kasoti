package dev.kasoti.evalmetrics

/**
 * Per-class precision, recall, macro-F1 and the full confusion matrix (EVAL.md §4).
 *
 * Two conventions are chosen deliberately and stated here because the alternative readings
 * change published numbers:
 *
 * * **Undefined rates are 0.0, never 1.0.** A class that was never predicted has undefined
 *   precision. Reporting 0.0 says "nothing was retrieved"; reporting 1.0 would claim perfect
 *   precision for a check that never fired, which is the classic way a gate is passed by a
 *   metric that was never computed.
 * * **Macro averages run over classes with support.** A label listed for completeness but
 *   absent from the corpus contributes to the confusion matrix and to nothing else. The
 *   matrix stays complete so a missing class is visible; the averages stay honest.
 */
data class ClassScore(
    val label: String,
    val support: Int,
    val predicted: Int,
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
) {
    val precision: Double get() = ratio(truePositive, truePositive + falsePositive)

    val recall: Double get() = ratio(truePositive, support)

    val f1: Double
        get() {
            val p = precision
            val r = recall
            return if (p + r <= 0.0) 0.0 else 2.0 * p * r / (p + r)
        }

    companion object {
        internal fun ratio(numerator: Int, denominator: Int): Double =
            if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
    }
}

/** Rows are actual classes, columns are predictions, both in [labels] order. */
data class ConfusionMatrix(val labels: List<String>, val rows: List<List<Int>>) {
    fun count(actual: String, predicted: String): Int {
        val r = labels.indexOf(actual)
        val c = labels.indexOf(predicted)
        return if (r < 0 || c < 0) 0 else rows[r][c]
    }

    fun toJson(): MetricJson.Obj = jsonOf(
        "labels" to MetricJson.Arr(labels.map { MetricJson.Str(it) }),
        "rows" to MetricJson.Arr(rows.map { row -> MetricJson.Arr(row.map { MetricJson.Num(it.toDouble()) }) }),
    )
}

data class ClassificationMetrics(
    val n: Int,
    val perClass: List<ClassScore>,
    val matrix: ConfusionMatrix,
    val accuracy: Double,
    val macroPrecision: Double,
    val macroRecall: Double,
    val macroF1: Double,
) {
    /** Worst recall over the classes that actually occurred. */
    val minRecall: Double get() = perClass.filter { it.support > 0 }.minOfOrNull { it.recall } ?: 0.0

    fun recallOf(label: String): Double = perClass.firstOrNull { it.label == label }?.recall ?: 0.0

    fun precisionOf(label: String): Double = perClass.firstOrNull { it.label == label }?.precision ?: 0.0

    /**
     * FUSION.md §5 / EVAL.md §4: a class may only be used to produce a RED when its recall
     * clears the floor. This is the hook the harness turns into a gate.
     */
    fun meetsRecallFloor(floor: Double): Boolean = n > 0 && minRecall >= floor

    /** Labels that would be blocked from RED-use, in the order they were declared. */
    fun belowRecallFloor(floor: Double): List<String> =
        perClass.filter { it.support > 0 && it.recall < floor }.map { it.label }

    fun toJson(): MetricJson.Obj = jsonOf(
        "n" to MetricJson.Num(n.toDouble()),
        "accuracy" to MetricJson.Num(accuracy),
        "macroPrecision" to MetricJson.Num(macroPrecision),
        "macroRecall" to MetricJson.Num(macroRecall),
        "macroF1" to MetricJson.Num(macroF1),
        "minRecall" to MetricJson.Num(minRecall),
        "perClass" to MetricJson.Arr(
            perClass.map { score ->
                jsonOf(
                    "label" to MetricJson.Str(score.label),
                    "support" to MetricJson.Num(score.support.toDouble()),
                    "predicted" to MetricJson.Num(score.predicted.toDouble()),
                    "precision" to MetricJson.Num(score.precision),
                    "recall" to MetricJson.Num(score.recall),
                    "f1" to MetricJson.Num(score.f1),
                )
            },
        ),
        "confusion" to matrix.toJson(),
    )
}

object Classification {

    /**
     * @param pairs `(actual, predicted)`, in the order the harness produced them.
     * @param labels classes to report. Anything observed but not listed is appended, so a
     *   surprise prediction class cannot silently vanish from the matrix.
     */
    fun of(pairs: List<Pair<String, String>>, labels: List<String> = emptyList()): ClassificationMetrics {
        val observed = pairs.flatMap { (actual, predicted) -> listOf(actual, predicted) }.distinct().sorted()
        val all = (labels + observed).distinct().sorted()
        require(all.isNotEmpty() || pairs.isEmpty()) { "no classes to report" }

        val matrix = ConfusionMatrix(
            labels = all,
            rows = all.map { actual -> all.map { predicted -> pairs.count { it.first == actual && it.second == predicted } } },
        )
        val correct = pairs.count { it.first == it.second }
        val perClass = all.map { label ->
            val support = matrix.count(label, label) + all.sumOf { other -> if (other != label) matrix.count(label, other) else 0 }
            val predicted = all.sumOf { other -> matrix.count(other, label) }
            ClassScore(
                label = label,
                support = support,
                predicted = predicted,
                truePositive = matrix.count(label, label),
                falsePositive = predicted - matrix.count(label, label),
                falseNegative = support - matrix.count(label, label),
            )
        }
        val scored = perClass.filter { it.support > 0 }
        return ClassificationMetrics(
            n = pairs.size,
            perClass = perClass,
            matrix = matrix,
            accuracy = ClassScore.ratio(correct, pairs.size),
            macroPrecision = scored.map { it.precision }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            macroRecall = scored.map { it.recall }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            macroF1 = scored.map { it.f1 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
        )
    }
}
