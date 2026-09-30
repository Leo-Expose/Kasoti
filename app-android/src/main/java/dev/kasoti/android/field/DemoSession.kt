package dev.kasoti.android.field

import dev.kasoti.fusion.VerdictReport
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate

/**
 * Demo-mode state and the one-tap reset (FR-C5).
 *
 * ## What "reset" has to mean
 *
 * Not "go back to the start". It has to leave the device in a state a judge can verify is
 * identical to the morning's: no leftover demo verdict, no leftover demo diary write attempt,
 * no leftover enrolment, no leftover consecutive-GREY counter, and demo mode itself **off**.
 * DEMO.md §5's recovery line for a broken run is "swap to the backup phone", which is a worse
 * outcome than a reset that works, and §6's gate is five consecutive crash-free runs — so the
 * reset is on the demo's critical path, not a convenience.
 *
 * The counters matter more than they look. `consecutiveGreyCount` in particular feeds
 * `:core`'s `A_GREY3` escalation, so a demo left in a bad-capture state would, after three
 * rehearsals, start escalating to AMBER for reasons that have nothing to do with the document.
 */
class DemoSession(
    private val registry: ThresholdRegistry,
    private val today: IsoDate,
    private val guard: DiaryGuard? = null,
    private val enrolments: EnrolmentStore? = null,
) {
    data class State(
        val enabled: Boolean = false,
        val loadedScenarioId: String? = null,
        /** Demo cases run so far this session. Reset to zero; shown on the demo bar. */
        val runsThisSession: Int = 0,
        val refusals: Int = 0,
    ) {
        /** The watermark is shown whenever demo mode is on, whatever is loaded (FR-C5). */
        val watermarked: Boolean get() = enabled
    }

    var state: State = State()
        private set

    fun enable() {
        state = state.copy(enabled = true)
    }

    fun disable() {
        state = state.copy(enabled = false)
    }

    /**
     * Toggle demo mode, and reset when turning it off.
     *
     * Turning demo mode off is the moment the app returns to being a real screening tool, so
     * the reset happens there rather than requiring a second action — an operator who
     * forgets to reset and then keeps the app in demo mode over a real queue is a much worse
     * outcome than an extra wipe on the way out.
     */
    fun toggle(): State {
        state = if (state.enabled) {
            reset()
        } else {
            state.copy(enabled = true)
        }
        return state
    }

    /**
     * One-tap reset.
     *
     * @return the cleared state, with demo mode **off**.
     */
    fun reset(): State {
        enrolments?.wipe()
        state = State(enabled = false, loadedScenarioId = null, runsThisSession = 0, refusals = 0)
        return state
    }

    /**
     * Build a demo case.
     *
     * @return the case, or `null` when demo mode is off or the id is unknown. Returning
     *   `null` rather than running anyway is deliberate: a demo path that works when it should
     *   not is a demo path that will be left switched on.
     */
    fun load(scenarioId: String): DemoCase? {
        if (!state.enabled) return null
        val scenario = DemoCatalogue.byId(scenarioId) ?: return null
        val case = scenario.build(DemoInputs(registry, today))
        state = state.copy(loadedScenarioId = scenarioId, runsThisSession = state.runsThisSession + 1)
        return case
    }

    fun inputs(): DemoInputs = DemoInputs(registry, today)

    /**
     * Confirm what a demo run decided.
     *
     * The check is one-directional and it is a hard failure, because a demo whose expected
     * outcome has drifted is worse than no demo: the operator has rehearsed a line that does
     * not match what the audience will see. [ScenarioDrift] is returned rather than thrown so
     * the app can show the drift and a recovery line (DEMO.md §5) instead of dying on stage.
     */
    fun verify(scenario: DemoScenario, report: VerdictReport): ScenarioDrift? {
        val expected = when (scenario.expected) {
            DemoScenario.ExpectedOutcome.GREEN -> dev.kasoti.fusion.Verdict.GREEN
            DemoScenario.ExpectedOutcome.AMBER -> dev.kasoti.fusion.Verdict.AMBER
            DemoScenario.ExpectedOutcome.RED -> dev.kasoti.fusion.Verdict.RED
            DemoScenario.ExpectedOutcome.GREY -> dev.kasoti.fusion.Verdict.GREY
        }
        if (report.verdict == expected) return null
        if (!report.demoMode) {
            return ScenarioDrift(
                scenario.id,
                expected.name,
                report.verdict.name,
                "the demo path lost its demoMode flag; this case is not safe to show (I7)",
            )
        }
        return ScenarioDrift(
            scenario.id,
            expected.name,
            report.verdict.name,
            "thresholds=${report.thresholdVersion}@${report.thresholdRunId} fusion=${report.fusionRuleVersion}",
        )
    }

    data class ScenarioDrift(
        val scenarioId: String,
        val expected: String,
        val actual: String,
        val detail: String,
    )
}
