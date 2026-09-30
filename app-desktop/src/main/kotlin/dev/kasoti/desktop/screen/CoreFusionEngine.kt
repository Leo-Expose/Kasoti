package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.FusionEngine
import dev.kasoti.threshold.ThresholdRegistry

/**
 * The real `dev.kasoti.fusion.FusionEngine`, behind the console's [VerdictEngine] interface.
 *
 * This is what the seam was built for. The engine landed in `:core` after the console was
 * written, and the whole point of the interface was that adopting it would be a change at
 * the composition root rather than a rewrite: this file is the only one in the module that
 * names `FusionEngine`, and everything above it talks to [VerdictEngine].
 *
 * `VerdictReport` carries far more than the console models — per-layer bearing, the
 * rationale, the carried-over proofs, the trust fast path — and the mapping is deliberately
 * lossy in one direction. What is not done is throwing the extra material away: [rationale]
 * is kept so the cascade can surface it as warnings, and a verdict produced here is always
 * a *subset* of the real one rather than a different one.
 */
class CoreFusionEngine : VerdictEngine {

    override val ruleVersion: String get() = FusionEngine.FUSION_RULE_VERSION

    /** The lines `:core` used to justify the verdict, for the console's warnings block. */
    var rationale: List<String> = emptyList()
        private set

    override fun decide(
        evidence: Evidence,
        registry: ThresholdRegistry,
        referenceYear: Int,
    ): Decision {
        val report = FusionEngine.decide(evidence, registry, referenceYear)
        rationale = report.rationale
        return Decision(verdict = report.verdict, findings = report.findings)
    }
}
