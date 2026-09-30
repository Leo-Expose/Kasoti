package dev.kasoti.fusion

import dev.kasoti.threshold.ThresholdRegistry

/**
 * Shared captures for the fusion tests.
 *
 * The tests build cases by mutating one clean passport rather than assembling a bespoke
 * [Evidence] per rule, because the property under test is never "does this rule fire" on its
 * own — it is "does it fire, does the verdict move, and does nothing else fire on the way". A
 * test that hand-builds a three-layer case would pass while the real pipeline, which always
 * arrives with every layer populated, quietly behaved differently.
 */
internal open class FusionFixtures {

    protected val registry = ThresholdRegistry.defaults(version = "v1", runId = "unit-test")
    protected val year = 2026

    // ------------------------------------------------------------------ harness

    protected fun decide(case: Evidence): VerdictReport = FusionEngine.decide(case, registry, year)

    /** A passport on which every load-bearing layer cleared. */
    protected fun cleanPassport(overrides: Evidence.() -> Evidence = { this }): Evidence = Evidence(
        track = Track.PASSPORT,
        quality = QualityReport.CLEAN,
        math = MathEvidence(allChecksPassed = true),
        qr = QrEvidence(present = true, signed = true, signatureValid = true),
        chip = ChipEvidence(present = true, passiveAuthValid = true, dg1MatchesMrz = true, supported = true),
        macro = MacroEvidence(
            photoZoneLabel = ProcessLabel.OFFSET,
            photoZoneMargin = 0.90f,
            textZoneLabel = ProcessLabel.OFFSET,
            textZoneMargin = 0.88f,
            uvState = UvState.PRESENT,
            clipUsed = true,
        ),
        face = FaceEvidence(
            similarity = 0.80f,
            docQuality = 1f,
            liveQuality = 1f,
            passiveLivenessScore = 0.90f,
            headTurnPassed = true,
        ),
        diary = DiaryEvidence(),
    ).overrides()

    protected fun decideFace(similarity: Float, quality: Float): VerdictReport = decide(
        cleanPassport {
            copy(
                face = FaceEvidence(
                    similarity = similarity,
                    docQuality = quality,
                    liveQuality = quality,
                    passiveLivenessScore = 0.9f,
                    headTurnPassed = true,
                ),
            )
        },
    )

    protected fun aadhaar(overrides: Evidence.() -> Evidence = { this }): Evidence = cleanPassport(
        overrides = {
            copy(
                track = Track.AADHAAR,
                chip = null,
                qr = QrEvidence(present = true, signed = true, signatureValid = true),
            )
        },
    ).overrides()

    protected fun hit(eventId: String = "evt_01", similarity: Float, dob: String? = "1990-01-01"): DiaryHit =
        DiaryHit(
            eventId = eventId,
            similarity = similarity,
            post = "RAXAUL",
            timestamp = "2026-01-02T04:15:00Z",
            nameHash = "9f2c1a7e4b6d0c3a5e8f1b2d4c6a0e37b5d8f1a2c4e6b0d3f5a7c9e1b3d5f70",
            dob = dob,
        )

    protected fun macro(
        photoLabel: ProcessLabel = ProcessLabel.OFFSET,
        photoMargin: Float = 0.90f,
        textLabel: ProcessLabel = ProcessLabel.OFFSET,
        textMargin: Float = 0.88f,
    ) = MacroEvidence(
        photoZoneLabel = photoLabel,
        photoZoneMargin = photoMargin,
        textZoneLabel = textLabel,
        textZoneMargin = textMargin,
        uvState = UvState.PRESENT,
        clipUsed = true,
    )

    // ------------------------------------------------------------------ §2 hard RED
}
