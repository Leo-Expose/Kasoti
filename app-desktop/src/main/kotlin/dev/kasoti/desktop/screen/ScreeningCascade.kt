package dev.kasoti.desktop.screen

import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.Presentation
import dev.kasoti.fusion.QrEvidence
import dev.kasoti.fusion.QualityReport
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.TrustState
import dev.kasoti.mrz.MrzParser
import dev.kasoti.mrz.MrzResult
import dev.kasoti.platform.crypto.JcaSignatureVerifier
import dev.kasoti.platform.imaging.ImageIoImaging
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import java.nio.file.Path

/**
 * What one screening produced, before a verdict is chosen.
 *
 * The layer list is printed as well as the verdict. An officer looking at a RED needs to
 * know which layers actually ran: "RED because the MRZ check digit failed" and "RED because
 * five layers ran and one disagreed" are very different conversations with a member of the
 * public, and only the first is safe to have at a counter.
 */
data class ScreeningResult(
    val caseId: String,
    val track: Track,
    val image: Path,
    val evidence: Evidence,
    val decision: Decision,
    val thresholdVersion: String,
    val fusionRuleVersion: String,
    val layers: List<LayerStatus>,
    val mrz: MrzResult?,
    val warnings: List<String>,
) {
    val requiresSecondary: Boolean get() = decision.verdict.requiresSecondary
}

/** One cascade layer: did it run, and what did it have to say. */
data class LayerStatus(val name: String, val state: State, val detail: String) {
    enum class State { RAN, UNAVAILABLE, SKIPPED }
}

/** Everything the cascade needs that is not the image itself. */
data class ScreeningRequest(
    val image: Path,
    val track: Track,
    val sidecar: Sidecar = Sidecar.EMPTY,
    val today: IsoDate,
    val deviceId: String,
    val demoMode: Boolean = false,
    val consecutiveGreyCount: Int = 0,
    /** Verbatim officer-typed MRZ rows; overrides OCR when present (BUILD.md §5). */
    val manualMrz: List<String> = emptyList(),
    val classifier: dev.kasoti.factory.ProcessClassifier? = null,
    val clipUsed: Boolean = true,
    /** Print `:core`'s own rationale lines under the verdict. */
    val showRationale: Boolean = true,
)

/**
 * The cascade: math → QR → macro → face → diary (DESIGN.md principle 2).
 *
 * The ordering is not a preference. Each layer is cheaper than the one after it, and each
 * one that fails hard makes the layers below it *less* informative rather than more. A
 * failed check digit does not become less of a failure because the face also did not match.
 *
 * Layers with no evidence source wired in yet — face and diary — are recorded as
 * `UNAVAILABLE` and contribute `null` evidence. They are not stubbed with a plausible
 * number. A system that reports a face similarity it did not compute is worse than one
 * that admits it did not look, because the number would be indistinguishable from a real
 * one in the audit record.
 */
class ScreeningCascade(
    val imaging: ImageIoImaging = ImageIoImaging(),
    val engine: VerdictEngine = CoreFusionEngine(),
    private val registryVersion: String = "v1",
    private val registryRunId: String = "console",
    val registry: ThresholdRegistry = ThresholdRegistry.defaults(registryVersion, registryRunId),
) {

    val verifier = JcaSignatureVerifier()

    fun screen(request: ScreeningRequest, caseId: String): ScreeningResult {
        val layers = mutableListOf<LayerStatus>()
        val warnings = mutableListOf<String>()

        val gray = readImage(request.image, layers, warnings)
        val quality = assessQuality(gray, request, layers)
        val math = runMath(request, quality, layers, warnings)
        val qr = runQr(request, layers, warnings)
        val macro = runMacro(request, layers, warnings)

        // TODO(M2,@vision): bind the TFLite-JVM interpreter. Until `EmbeddingModel` has a
        // real implementation the face layer cannot run, and the console reports
        // UNAVAILABLE rather than inventing a similarity. See DESIGN.md D1.
        layers += LayerStatus("face", LayerStatus.State.UNAVAILABLE, "no embedding interpreter bound (M2)")
        // TODO(M2,@core): `dev.kasoti.diary` is being written in `:core`. Until it lands
        // there is no alias/travel/facilitator/watchlist evidence, and DiaryEvidence stays
        // null so fusion cannot see a diary layer that did not run.
        layers += LayerStatus("diary", LayerStatus.State.UNAVAILABLE, "dev.kasoti.diary not yet in :core (M2)")

        val evidence = Evidence(
            track = request.track,
            quality = quality,
            math = math,
            qr = qr,
            macro = macro,
            presentation = presentationOf(request),
            trust = trustOf(request),
            consecutiveGreyCount = request.consecutiveGreyCount,
            demoMode = request.demoMode,
        )
        val decision = engine.decide(evidence, registry, referenceYear = request.today.year)

        // `:core` explains itself in `rationale`. Surfacing it beats re-deriving the same
        // sentences here, which is how a console ends up disagreeing with the engine about
        // why a verdict was reached.
        (engine as? CoreFusionEngine)?.rationale?.takeIf { request.showRationale }?.let {
            warnings += it
        }

        return ScreeningResult(
            caseId = caseId,
            track = request.track,
            image = request.image,
            evidence = evidence,
            decision = decision,
            thresholdVersion = registry.version,
            fusionRuleVersion = engine.ruleVersion,
            layers = layers,
            mrz = parseMrz(request),
            warnings = warnings,
        )
    }

    internal fun presentationOf(request: ScreeningRequest): Presentation =
        Presentation.entries.firstOrNull { it.name == request.sidecar.presentation.uppercase() }
            ?: Presentation.PHYSICAL

    internal fun trustOf(request: ScreeningRequest): TrustState =
        TrustState.entries.firstOrNull { it.name == request.sidecar.trust.uppercase() } ?: TrustState.VERIFY

    internal fun parseMrz(request: ScreeningRequest): MrzResult? {
        val lines = request.manualMrz.ifEmpty { request.sidecar.mrzLines }
        if (lines.isEmpty()) return null
        return MrzParser.parse(lines, referenceYear = request.today.year)
    }

    companion object {
        /** DATA.md §3 patch size, so a live capture and a file import are the same input. */
        const val MACRO_PATCH_SIZE = 256

        const val DEFAULT_TEST_KEY_ID = "uidai_test"
    }
}
