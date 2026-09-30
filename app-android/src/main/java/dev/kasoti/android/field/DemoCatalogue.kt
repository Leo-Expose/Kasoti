package dev.kasoti.android.field

import dev.kasoti.fusion.TrustState
import dev.kasoti.i18n.Language
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate

/**
 * What a scenario needs in order to build its case.
 *
 * The registry rather than a set of bare numbers, because a demo whose thresholds have drifted
 * away from the shipped ones is demonstrating a system nobody has.
 */
data class DemoInputs(
    val registry: ThresholdRegistry,
    val today: IsoDate,
) {
    /** MRZ two-digit years are resolved against this; the live path uses the same clock. */
    val referenceYear: Int get() = today.year
}

/**
 * One demo scenario (FR-C5: seeded fixtures).
 *
 * A scenario is a *builder*, not a recording. Each one constructs the case's evidence and lets
 * the real [dev.kasoti.fusion.FusionEngine] decide, so the demo runs the actual cascade. A
 * stored verdict would prove nothing, and DEMO.md's script is explicitly built on judges
 * poking the app with their own printouts.
 */
data class DemoScenario(
    val id: String,
    val title: String,
    val titleHi: String,
    /** Which beat of DEMO.md §3 this is for. Shown to the operator, never to a judge. */
    val demoMoment: String,
    val expected: ExpectedOutcome,
    val build: (DemoInputs) -> DemoCase,
) {
    enum class ExpectedOutcome { GREEN, AMBER, RED, GREY }

    fun title(language: Language): String = if (language == Language.HINDI) titleHi else title
}

/**
 * The demo catalogue — one fixture per beat of DEMO.md §3.
 *
 * ## The 3-minute script, mapped
 *
 * | DEMO.md moment | Scenario | Mechanism it exercises |
 * |---|---|---|
 * | 0:00–0:20 genuine specimen | [GENUINE_PASSPORT] | every load-bearing passport layer supplied → GREEN |
 * | 0:20–1:00 live macro, genuine vs inkjet | [GENUINE_OFFSET_MACRO], [INKJET_PRINTOUT] | both zones agree; the *label* is the story |
 * | 1:00–1:40 forged inkjet twin | [FORGED_INKJET_TWIN] | `R-PROC-02`: zones disagree, both margins above the RED floor |
 * | 1:40–2:20 the alias | [ALIAS_RAMESH_SURESH] | `R-ALIAS-01`: hit over `T_ALIAS_HI`, different name, gap corroborated |
 * | 1:40–2:20 optional watchlist | [WATCHLIST_HIT] | `A_WL_01` → AMBER |
 * | DEMO.md §5 "judge's printout breaks the router" | [ROUTER_UNSUPPORTED] | `SYS_UNSUPPORTED_TRACK` → AMBER. Honest software. |
 * | DEMO.md §5 recovery / the GREY moment | [BLURRY_RETAKE] | quality gate fails on a frame that already showed an expiry → GREY + carried proof |
 *
 * ## What is deliberately absent
 *
 * **No binary fixtures.** The seed is a name and a date; the MRZ is built at run time by
 * `dev.kasoti.mrz.MrzBuilder` from a [MrzPerson]. That means the demo's MRZ is a real
 * check-digit-correct TD3, regenerated against the same reference year the live path uses, so
 * a change to the century rule cannot leave the demo quietly showing a stale string. It also
 * means no volunteer's document image is committed to the repo (DATA.md §1).
 *
 * **No stored verdicts.** See [DemoScenario].
 *
 * **No invented accuracy claims.** Nothing in this file is a measured performance figure; the
 * values below are simulated *inputs*, and EVAL.md is the only place performance numbers come
 * from (AGENTS.md §5 forbids hand-typed accuracy numbers anywhere else).
 */
object DemoCatalogue {

    const val GENUINE_PASSPORT = "genuine-passport"
    const val GENUINE_OFFSET_MACRO = "genuine-offset-macro"
    const val INKJET_PRINTOUT = "inkjet-printout"
    const val FORGED_INKJET_TWIN = "forged-inkjet-twin"
    const val ALIAS_RAMESH_SURESH = "alias-ramesh-suresh"
    const val WATCHLIST_HIT = "watchlist-hit"
    const val ROUTER_UNSUPPORTED = "router-unsupported"
    const val BLURRY_RETAKE = "blurry-retake"

    /**
     * The seed.
     *
     * Fixed, not random. FR-C5 says "seeded fixtures" and the value of a seed is that a
     * rehearsal produces the same result as the performance — a demo whose red case lands on
     * GREEN because of an unlucky draw is a demo that needs a second take, and DEMO.md §6 counts
     * consecutive crash-free rehearsals, not lucky ones.
     */
    val SPECIMEN: MrzPerson = MrzPerson(
        surname = "SHARMA",
        givenNames = "RAMESH",
        documentNumber = "K4820913",
        nationality = "IND",
        birthDate = IsoDate(1984, 3, 17),
        sex = 'M',
        expiryDate = IsoDate(2031, 3, 16),
        personalNumber = "SPECIMEN001",
    )

    /** The alias case: the same face, a different name (DEMO.md §3, 1:40–2:20). */
    val ALIAS_PERSON: MrzPerson = SPECIMEN.copy(
        givenNames = "SURESH",
        documentNumber = "K4820999",
    )

    fun all(): List<DemoScenario> = listOf(
        DemoScenario(
            id = GENUINE_PASSPORT,
            title = "Genuine specimen",
            titleHi = "असली नमूना",
            demoMoment = "DEMO.md 0:00-0:20",
            expected = DemoScenario.ExpectedOutcome.GREEN,
        ) { DemoBodies.genuinePassport(it, GENUINE_PASSPORT) },

        DemoScenario(
            id = GENUINE_OFFSET_MACRO,
            title = "Genuine offset print — both zones agree",
            titleHi = "असली ऑफसेट छपाई — दोनों क्षेत्र सहमत",
            demoMoment = "DEMO.md 0:20-1:00 (left)",
            expected = DemoScenario.ExpectedOutcome.GREEN,
        ) { DemoBodies.genuinePassport(it, GENUINE_OFFSET_MACRO) },

        DemoScenario(
            id = INKJET_PRINTOUT,
            title = "Inkjet printout — a copy, and both zones agree",
            titleHi = "इंकजेट प्रिंटआउट — नकल, और दोनों क्षेत्र सहमत",
            demoMoment = "DEMO.md 0:20-1:00 (the judge's printout)",
            // GREEN, and deliberately so. See [KNOWN_GAPS] — the process *label* is the
            // deliverable in this beat, not the verdict, and pretending otherwise would be
            // the kind of overclaim AGENTS.md §5 forbids.
            expected = DemoScenario.ExpectedOutcome.GREEN,
        ) { DemoBodies.inkjetPrintout(it) },

        DemoScenario(
            id = FORGED_INKJET_TWIN,
            title = "Forged inkjet twin — photo zone replaced",
            titleHi = "नकली इंकजेट जुड़वा — चित्र क्षेत्र बदला गया",
            demoMoment = "DEMO.md 1:00-1:40",
            expected = DemoScenario.ExpectedOutcome.RED,
        ) { DemoBodies.forgedTwin(it) },

        DemoScenario(
            id = ALIAS_RAMESH_SURESH,
            title = "Alias — earlier crossing under another name",
            titleHi = "उपनाम — पहले अलग नाम से प्रवेश",
            demoMoment = "DEMO.md 1:40-2:20",
            expected = DemoScenario.ExpectedOutcome.RED,
        ) { DemoBodies.aliasCase(it) },

        DemoScenario(
            id = WATCHLIST_HIT,
            title = "Watchlist match",
            titleHi = "निगरानी सूची मिलान",
            demoMoment = "DEMO.md 1:40-2:20 (optional)",
            expected = DemoScenario.ExpectedOutcome.AMBER,
        ) { DemoBodies.watchlist(it) },

        DemoScenario(
            id = ROUTER_UNSUPPORTED,
            title = "Unsupported document — honest software",
            titleHi = "असमर्थित दस्तावेज़ — ईमानदार सॉफ़्टवेयर",
            demoMoment = "DEMO.md §5 recovery line",
            expected = DemoScenario.ExpectedOutcome.AMBER,
        ) { DemoBodies.unsupportedTrack(it) },

        DemoScenario(
            id = BLURRY_RETAKE,
            title = "Blurred capture — retake, never an accusation",
            titleHi = "धुँधली तस्वीर — दोबारा फोटो, कभी आरोप नहीं",
            demoMoment = "DEMO.md §5 recovery line (the GREY moment)",
            expected = DemoScenario.ExpectedOutcome.GREY,
        ) { DemoBodies.blurryRetake(it) },
    )

    fun byId(id: String): DemoScenario? = all().firstOrNull { it.id == id }

    /**
     * Gaps this build knows about, recorded where a judge would trip over them.
     *
     * ## The Aadhaar "PVC honesty rule" is not implemented in `:core`
     *
     * SPEC FR-F1 and FUSION.md §7 both say an Aadhaar's macro layer carries a *PVC honesty
     * rule* — a PVC card should read as the substrate it is, and an inkjet reading on one is
     * evidence of a copy. `:core`'s `AmberRules.process` has no such rule: it fires
     * `A_PROC_01` on a zone **mismatch**, a **SCREEN** hint, or a **worn** pair, and
     * otherwise confirms `MACRO_OK`. Two confident, *agreeing* INKJET zones on a genuine
     * Aadhaar therefore clear.
     *
     * The app could paper over that by hard-coding "PVC cards are never inkjet", and it
     * deliberately does not: no `:core` test, no eval fixture and no FUSION row backs such a
     * rule, and inventing one at the app layer is precisely the hand-typed claim the eval
     * protocol exists to prevent. The fix belongs in `:core` as a `TrackMatrix`-adjacent
     * expected-substrate table with an eval fixture; until then the honest presentation is to
     * show the *labels* prominently — which the macro card does — and let the supervisor read
     * them, and to say so rather than to fake a verdict.
     *
     * ## The classifier is not trained
     *
     * With no `svm_print_v1.json` bound, every patch classifies as `UNKNOWN` with a zero
     * margin, so `DocAggregator` abstains and the macro layer contributes nothing. A demo must
     * not paper over that: an untrained model prints `UNKNOWN`, and the recovery line is
     * "honest software says so" (DEMO.md §5).
     */
    object KNOWN_GAPS {
        const val PVC_HONESTY_RULE = "FUSION.md §7 PVC honesty rule has no :core implementation"
        const val UNTRAINED_CLASSIFIER = "no svm_print_v1.json bound: macro classifies UNKNOWN and abstains"
        const val NFC_UNSUPPORTED = "FR-N1 is P1; this build has no NFC reader and the chip layer is ABSENT"
    }

    /**
     * The MRZ for a person, generated rather than stored.
     *
     * @param referenceYear the caller's clock year, the same one the live parse path uses.
     */
    fun mrzFor(person: MrzPerson, inputs: DemoInputs): List<String> =
        MrzBuilder.buildTd3(person, inputs.referenceYear)
}
