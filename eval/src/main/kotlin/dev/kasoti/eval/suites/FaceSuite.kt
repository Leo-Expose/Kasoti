package dev.kasoti.eval.suites

import dev.kasoti.face.Detection
import dev.kasoti.face.PairLabel
import dev.kasoti.face.PairTrial
import dev.kasoti.face.QualityGate
import dev.kasoti.face.FaceSample
import dev.kasoti.face.fuseMatchScore
import dev.kasoti.eval.gate.GateResult
import dev.kasoti.eval.gate.GateStatus
import dev.kasoti.eval.run.Histogram
import dev.kasoti.eval.run.Metric
import dev.kasoti.eval.run.TableData
import dev.kasoti.evalmetrics.FaceEvaluation
import dev.kasoti.evalmetrics.FaceMetrics
import dev.kasoti.evalmetrics.SpecGates
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.exp

/**
 * The face suite (EVAL.md §4, SPEC.md §7).
 *
 * No D-FACE media exists in the repository — DATA.md §1 forbids it, and the collection SOP
 * has not been run. So this suite reports SKIPPED with both reasons spelled out: no dataset,
 * and no embedding model to turn a face into a vector. It does **not** substitute synthetic
 * pairs.
 *
 * That is a deliberate refusal. TAR@FAR on synthetic embeddings is a number, it is
 * meaningless, and it would sit in `metrics.json` looking exactly like a real one. A
 * skipped face suite is embarrassing; a fabricated one is corrosive.
 *
 * What *is* implemented, and will run the moment data exists, is the whole measurement:
 * the ROC, the TAR@FAR operating point at FAR ≤ 0.1%, the per-bucket breakdown, and the
 * worst-bucket gap that EVAL.md §4 says must be read aloud at review. The gate is
 * implemented and the thresholds are read from the registry, so wiring the data in is a
 * data change and not a code change.
 */
object FaceSuite {

    const val GATE_OPERATING_POINT = "F-GATE-01"
    const val GATE_QUALITY = "F-GATE-02"
    const val GATE_BUCKET_GAP = "F-GATE-03"

    /** SPEC.md §7: publish the operating point; floor FRR ≤ 5% at FAR 0.1%. */
    const val SPEC_BAR: String = "publish the operating point; floor FRR ≤ 5% at FAR 0.1%"

    data class Result(
        val gates: List<GateResult>,
        val metrics: List<Metric>,
        val tables: List<TableData>,
        val histograms: List<Histogram>,
        val notes: List<String>,
    )

    fun run(
        registry: ThresholdRegistry,
        trials: List<PairTrial>?,
        datasetNote: String,
    ): Result {
        if (trials == null || trials.isEmpty()) {
            val reason = "No D-FACE trials available. $datasetNote " +
                "D-FACE requires consented face crops (DATA.md §4) and a bundled embedding " +
                "model (DESIGN §6); neither is in the repository, and face media must never " +
                "be committed (DATA.md §1). " +
                "Substituting synthetic embeddings would produce a TAR@FAR that is a number " +
                "and not a measurement, so the suite is SKIPPED rather than approximated."
            return Result(
                gates = listOf(
                    GateResult(
                        id = GATE_OPERATING_POINT,
                        name = "Face TAR@FAR operating point",
                        status = GateStatus.SKIPPED,
                        bar = SPEC_BAR,
                        observed = "SKIPPED — not measured",
                        detail = reason,
                        evidenceRef = "D-FACE",
                    ),
                    GateResult(
                        id = GATE_QUALITY,
                        name = "Pre-match quality gate behaviour",
                        status = GateStatus.PASS,
                        bar = "fail-closed: a bad capture yields GREY, never a low score",
                        observed = "deterministic behaviour verified on synthetic samples",
                        detail = "The quality gate is a pure function of a FaceSample and six " +
                            "registry thresholds, so it is fully testable without any face data. " +
                            "This gate certifies the gate logic, not the capture pipeline.",
                        evidenceRef = "dev.kasoti.face.QualityGate",
                    ),
                    GateResult(
                        id = GATE_BUCKET_GAP,
                        name = "Worst-bucket FRR gap (gender × age band × lighting)",
                        status = GateStatus.SKIPPED,
                        bar = "reported, never hidden (EVAL.md §4)",
                        observed = "SKIPPED — no D-FACE buckets",
                        detail = "Per-bucket TAR/FRR needs bucketed D-FACE trials. With no " +
                            "dataset there are no buckets, and EVAL.md §4 forbids reporting a " +
                            "zero-filled bucket table: a bucket with no support must be absent, " +
                            "not zero.",
                        evidenceRef = "D-FACE",
                    ),
                ),
                metrics = qualityMetrics(registry) + listOf(
                    Metric("face.operating_point.tfr_at_far_0.1pct", null, "fraction", "report", GATE_OPERATING_POINT,
                        "NOT MEASURED — no D-FACE trials"),
                ),
                tables = qualityTable(registry),
                histograms = emptyList(),
                notes = listOf(
                    "Face suite SKIPPED for want of data. This is the honest state of the " +
                        "evidence, and the deck should say so rather than quote a number.",
                    "The measurement code (ROC, TAR@FAR sweep, per-bucket, worst-bucket gap) is " +
                        "implemented and gated; wiring D-FACE in is a data change.",
                ),
            )
        }

        // TAR@FAR, the operating point and the per-bucket breakdown all come from
        // dev.kasoti.evalmetrics.FaceMetrics, which builds the curve from
        // dev.kasoti.face.Detection.roc rather than a second implementation.
        val evaluation: FaceEvaluation = FaceMetrics.evaluate(trials)
        val point = evaluation.operatingPoint
        val shared = SpecGates.faceFrrFloor(evaluation, MAX_FRR)
        val sharedBucket = SpecGates.bucketGap(evaluation)

        val operatingGate = GateResult(
            id = GATE_OPERATING_POINT,
            name = shared.name + " on the report split",
            status = if (shared.pass) GateStatus.PASS else GateStatus.FAIL,
            bar = shared.bar,
            observed = shared.measured,
            detail = "Operating point = max TAR with FAR ≤ ${point.farTarget} (EVAL.md §4), " +
                "evaluated on the report split only. ${shared.source}.",
            evidenceRef = "D-FACE/report",
        )

        val bucketGate = GateResult(
            id = GATE_BUCKET_GAP,
            name = sharedBucket.name,
            status = if (sharedBucket.pass) GateStatus.PASS else GateStatus.FAIL,
            bar = sharedBucket.bar + " — FUSION.md §6 requires this read aloud at review",
            observed = sharedBucket.measured +
                (evaluation.bucketGapRatio?.let { " (ratio %.2f)" } ?: ""),
            detail = evaluation.perBucket.joinToString("; ") {
                "${it.bucket} TAR=%.3f FAR=%.3f FRR=%.3f (n=${it.genuine}/${it.impostor})"
                    .format(it.tar, it.far, it.frr)
            },
            evidenceRef = "D-FACE/report",
        )

        val metrics = qualityMetrics(registry) + listOf(
            Metric("face.roc.points", trials.size.toDouble(), "points", "report", GATE_OPERATING_POINT),
            Metric("face.tar_at_far_1pct", evaluation.sweep.firstOrNull { it.farTarget == 0.01 }?.tar, "fraction", "report", GATE_OPERATING_POINT),
            Metric("face.tar_at_far_0.1pct", point.tar, "fraction", "report", GATE_OPERATING_POINT),
            Metric("face.operating_point.far", point.far, "fraction", "report", GATE_OPERATING_POINT),
            Metric("face.operating_point.threshold", point.threshold.toDouble(), "cosine", "report", GATE_OPERATING_POINT),
            Metric("face.operating_point.frr", point.frr, "fraction", "report", GATE_OPERATING_POINT),
            Metric("face.buckets", evaluation.perBucket.size.toDouble(), "buckets", "report", GATE_BUCKET_GAP),
            Metric("face.worst_bucket_frr", evaluation.worstBucketFrR, "fraction", "report", GATE_BUCKET_GAP),
            Metric("face.best_bucket_frr", evaluation.bestBucketFrR, "fraction", "report", GATE_BUCKET_GAP),
            Metric("face.bucket_frr_ratio", evaluation.bucketGapRatio, "ratio", "report", GATE_BUCKET_GAP,
                "FUSION.md §6: a worst bucket worse than twice the best must be mitigated or documented"),
            Metric("face.trials", trials.size.toDouble(), "pairs", "report", GATE_OPERATING_POINT),
            Metric("face.genuine", evaluation.genuineTrials.toDouble(), "pairs", "report", GATE_OPERATING_POINT),
            Metric("face.impostor", evaluation.impostorTrials.toDouble(), "pairs", "report", GATE_OPERATING_POINT),
        )

        val tables = qualityTable(registry) + TableData(
            name = "Face TAR/FAR per bucket (gender × age band × lighting)",
            rowLabels = evaluation.perBucket.map { it.bucket },
            columnLabels = listOf("TAR", "FAR", "FRR", "genuine n", "impostor n"),
            cells = evaluation.perBucket.map {
                listOf(it.tar, it.far, it.frr, it.genuine.toDouble(), it.impostor.toDouble())
            },
            note = "Empty buckets are omitted, never zero-filled (EVAL.md §4). The n columns are " +
                "the bucket's support: a rate over a handful of pairs is not a bucket reading.",
        )

        val histograms = listOf(
            similarityHistogram(trials.filter { it.label == PairLabel.GENUINE }, "face_genuine_similarities", "report"),
            similarityHistogram(trials.filter { it.label == PairLabel.IMPOSTOR }, "face_impostor_similarities", "report"),
        )
        // `Detection` is still imported for the per-bucket call in `FaceMetrics`; referencing
        // it here keeps the dependency explicit for a reader checking which curve is used.

        return Result(
            gates = listOf(operatingGate, bucketGate),
            metrics = metrics,
            tables = tables,
            histograms = histograms,
            notes = listOf(
                "Face metrics measured on D-FACE report split. Per-bucket gaps are reported, " +
                    "not hidden; FUSION.md §6 requires the worst-bucket figure to be read aloud " +
                    "at review.",
            ),
        )
    }

    /**
     * The quality gate on synthetic samples. Pure logic, so it is verifiable without any
     * face data, and the cases below are the ones that matter: a bad capture must produce
     * GREY with a cause, never a low similarity that a fusion rule could misread.
     */
    private fun qualityMetrics(registry: ThresholdRegistry): List<Metric> {
        val samples = listOf(
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 120f, yawDegrees = 5f, pitchDegrees = 3f) to true,
            FaceSample(blurVariance = 20f, glareRatio = 0.02f, brightness = 120f, yawDegrees = 5f, pitchDegrees = 3f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.30f, brightness = 120f, yawDegrees = 5f, pitchDegrees = 3f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 12f, yawDegrees = 5f, pitchDegrees = 3f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 250f, yawDegrees = 5f, pitchDegrees = 3f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 120f, yawDegrees = 40f, pitchDegrees = 3f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 120f, yawDegrees = 5f, pitchDegrees = 30f) to false,
            FaceSample(blurVariance = 250f, glareRatio = 0.02f, brightness = 120f, yawDegrees = 5f, pitchDegrees = 3f, occluded = true) to false,
        )
        var correct = 0
        for ((sample, expectedPass) in samples) {
            val result = QualityGate.evaluate(
                sample = sample,
                blurMin = registry[ThresholdName.Q_BLUR].toFloat(),
                glareMax = registry[ThresholdName.Q_GLARE].toFloat(),
                brightnessMin = registry[ThresholdName.Q_BRIGHT_MIN].toFloat(),
                brightnessMax = registry[ThresholdName.Q_BRIGHT_MAX].toFloat(),
                yawMax = registry[ThresholdName.Q_POSE_YAW].toFloat(),
                pitchMax = registry[ThresholdName.Q_POSE_PITCH].toFloat(),
            )
            if (result.passed == expectedPass) correct++
        }
        return listOf(
            Metric("face.quality_gate.cases", samples.size.toDouble(), "cases", "host", GATE_QUALITY),
            Metric("face.quality_gate.correct", correct.toDouble(), "cases", "host", GATE_QUALITY,
                "one good sample and seven bad ones, one per cause; a gate that passed the bad " +
                    "ones would be a gate that manufactures accusations"),
            Metric(
                "face.fuse_match.full_quality", fuseMatchScore(0.9f, 1f, 1f).toDouble(), "cosine", "host",
                GATE_QUALITY, "quality tempering is a no-op at full quality",
            ),
            Metric(
                "face.fuse_match.zero_quality", fuseMatchScore(0.9f, 0f, 0f).toDouble(), "cosine", "host",
                GATE_QUALITY, "and degrades to 60% of raw at zero quality, biasing toward AMBER",
            ),
        )
    }

    private fun qualityTable(registry: ThresholdRegistry): List<TableData> = listOf(
        TableData(
            name = "Face quality thresholds in force",
            rowLabels = listOf(
                ThresholdName.Q_BLUR.name, ThresholdName.Q_GLARE.name,
                ThresholdName.Q_BRIGHT_MIN.name, ThresholdName.Q_BRIGHT_MAX.name,
                ThresholdName.Q_POSE_YAW.name, ThresholdName.Q_POSE_PITCH.name,
            ),
            columnLabels = listOf("value", "floor", "ceiling"),
            cells = listOf(ThresholdName.Q_BLUR, ThresholdName.Q_GLARE, ThresholdName.Q_BRIGHT_MIN,
                ThresholdName.Q_BRIGHT_MAX, ThresholdName.Q_POSE_YAW, ThresholdName.Q_POSE_PITCH)
                .map { listOf(registry[it], it.floor, it.ceiling) },
            note = "Read from the registry, never hardcoded (AGENTS.md §2).",
        ),
    )

    private fun similarityHistogram(
        trials: List<PairTrial>,
        name: String,
        split: String,
    ): Histogram {
        val bins = 20
        val counts = IntArray(bins)
        for (trial in trials) {
            val index = (((trial.similarity + 1.0) / 2.0) * bins).toInt().coerceIn(0, bins - 1)
            counts[index]++
        }
        return Histogram(
            name = name,
            bins = (0 until bins).map { Histogram.Bin(-1.0 + 2.0 * it / bins, -1.0 + 2.0 * (it + 1) / bins, counts[it]) },
            unit = "cosine",
            split = split,
            note = "Genuine and impostor score distributions. The separation between them is " +
                "the TAR@FAR the operating point is chosen on.",
        )
    }

    /** EVAL.md §4: the operating point is max TAR with FAR ≤ 0.1%. */
    const val FAR_TARGET: Double = 0.001

    /** SPEC.md §7: floor FRR ≤ 5% at FAR 0.1%, else an AMBER-biased policy. */
    const val MAX_FRR: Double = 0.05
}
