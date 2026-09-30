package dev.kasoti.eval.fixtures

import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.PostCoord
import dev.kasoti.diary.PostMap
import dev.kasoti.diary.Ulid
import dev.kasoti.fusion.DiaryHit
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import java.time.Instant
import kotlin.random.Random

/**
 * The D-SCEN scripted diary corpus (EVAL.md §2, `D-SCEN`, 40 scripts, 100%).
 *
 * These are not a description of diary behaviour; they are the input to the *real* rules.
 * Each script materialises real [CrossingEvent]s and real [DiaryHit]s, and the suite feeds
 * them to `dev.kasoti.diary.aliasRule` / `impossibleTravel` / `facilitatorRule` and then to
 * `dev.kasoti.fusion.FusionEngine`. Nothing about the rules is reimplemented here — only the
 * situations are staged.
 *
 * Every script states three things: what it plants, which finding codes must come out, and
 * whether a *human* should be implicated. The third is the one that matters most. FUSION.md §1
 * is explicit that a screener which says "we could not look properly" must never render as
 * an accusation, so the suite asserts the AMBER and GREEN outcomes as firmly as the RED ones
 * — a diary suite that only checks that planted attacks are caught is measuring recall and
 * not the false-alarm rate that decides whether the rule is safe to ship at all.
 */
object DiaryScenarios {

    enum class Category {
        /** Planted for another identity, to test the alias rule. */
        ALIAS,

        /** Two postings far apart in too little time. */
        TRAVEL,

        /** Many crossings sharing a small set of cohorts. */
        FACILITATOR,

        /** A planted watchlist entry. */
        WATCHLIST,

        /** A clean run: nothing should fire. */
        BENIGN,
    }

    /**
     * @param expectedCodes must all be present, so a scenario cannot pass for the wrong
     *   reason. An AMBER script that accidentally produced AMBER for an unrelated finding has
     *   not tested the rule it was written for (`evalmetrics.ScenarioOutcome.codesMatched`).
     */
    data class Scenario(
        val id: String,
        val category: Category,
        val expected: Verdict,
        val expectedCodes: Set<FindingCode>,
        val events: List<CrossingEvent>,
        val aliasHits: List<DiaryHit>,
        val watchlistHits: List<DiaryHit>,
        val claimedNameSha: String,
        val claimedDob: String,
        val posts: PostMap,
        val description: String,
    ) {
        /** The scenario's own reference instant, reused as a placeholder timestamp on hits. */
        val REFERENCE_TS: String get() = REFERENCE_INSTANT
    }

    /** The reference instant every crossing is offset from. Never `now()`. */
    const val REFERENCE_INSTANT: String = "2026-09-01T09:00:00Z"
    private val REFERENCE_MILLIS: Long = Instant.parse(REFERENCE_INSTANT).toEpochMilli()

    /**
     * Fictional posts with real coordinates.
     *
     * `dev.kasoti.diary.impossibleTravel` computes great-circle distance from coordinates
     * rather than from a distance table, so the numbers below are latitude/longitude in a
     * fictional-but-consistent layout. They are chosen so the travel rule's threshold
     * crossing lands on a round hour, which is what makes the boundary scripts meaningful.
     */
    val POSTS: PostMap = mapOf(
        "P-01" to PostCoord("P-01", 27.0000, 84.0000),
        "P-02" to PostCoord("P-02", 28.1500, 84.0000),
        "P-03" to PostCoord("P-03", 27.0000, 84.9000),
        "P-04" to PostCoord("P-04", 29.5000, 84.0000),
        "P-05" to PostCoord("P-05", 26.9000, 83.1000),
    )

    const val TRACKED_DEVICE = "TEST-DEV-NORTH"
    const val WATCH_DEVICE = "TEST-DEV-WEST"

    /** A valid embedding-model tag: `name@sha256:<64 hex>` (EmbModel.isValid). */
    const val EMB_MODEL = "eval-test@sha256:" +
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /** The name hash a person presents under. Raw names never enter the diary. */
    val SUBJECT_NAME_SHA: String get() = digestOf("subject:ramESH")
    val OTHER_NAME_SHA: String get() = digestOf("subject:suresh")
    const val SUBJECT_DOB = "1990-01-01"
    const val OTHER_DOB = "1991-07-14"

    fun build(): List<Scenario> = buildList {
        addAll(aliasScenarios())
        addAll(travelScenarios())
        addAll(facilitatorScenarios())
        addAll(watchlistScenarios())
        addAll(benignScenarios())
    }

    // ---- ALIAS (10) ---------------------------------------------------------
    // R-ALIAS-01 fires when a ranked hit is at or above T_ALIAS_HI *and* the claimed name or
    // DOB differs. FUSION.md §2 makes it a hard RED gated on supervisor confirmation, so a
    // hit at or above the threshold is RED whatever the mismatch was; below the threshold the
    // rule correctly does not fire at all and the case is GREEN.
    //
    // The ten rows straddle T_ALIAS_HI (0.80) on both sides of the inclusive boundary — the
    // last RED row sits exactly on it — and the disambiguation arm varies: five differ by
    // name, five by DOB only. An earlier draft of this script expected AMBER for the DOB-only
    // rows; that was the harness guessing at the rule, and the rule says RED, so the script
    // follows the rule. (Recorded because the temptation to "fix" the product to match a
    // hand-written expectation is the exact failure AGENTS.md §8 warns about.)
    private fun aliasScenarios(): List<Scenario> = (0 until 10).map { i ->
        val similarity = listOf(0.95f, 0.88f, 0.84f, 0.82f, 0.81f, 0.80f, 0.79f, 0.78f, 0.77f, 0.76f)[i]
        val nameDiffers = i < 5
        val aboveThreshold = similarity >= 0.80f
        val priorNameSha = if (nameDiffers) OTHER_NAME_SHA else SUBJECT_NAME_SHA
        val subjectEvent = event("evt-a-%02d-1".format(i), seq = 1, post = "P-01", hoursAgo = 240.0,
            nameSha = SUBJECT_NAME_SHA, dob = SUBJECT_DOB, device = TRACKED_DEVICE)
        val priorEvent = event("evt-a-%02d-2".format(i), seq = 2, post = "P-05", hoursAgo = 48.0,
            nameSha = priorNameSha, dob = OTHER_DOB, device = WATCH_DEVICE)

        Scenario(
            id = "scen-alias-%02d".format(i),
            category = Category.ALIAS,
            expected = if (aboveThreshold) Verdict.RED else Verdict.GREEN,
            expectedCodes = if (aboveThreshold) setOf(FindingCode.R_ALIAS_01) else emptySet(),
            events = listOf(priorEvent, subjectEvent),
            aliasHits = listOf(
                DiaryHit(
                    eventId = priorEvent.id,
                    similarity = similarity,
                    post = priorEvent.post,
                    timestamp = priorEvent.ts,
                    nameHash = priorEvent.nameSha,
                    dob = priorEvent.dob,
                ),
            ),
            watchlistHits = emptyList(),
            claimedNameSha = SUBJECT_NAME_SHA,
            claimedDob = SUBJECT_DOB,
            posts = POSTS,
            description = "a prior crossing by " + (if (nameDiffers) "a different name" else "the same name but a different DOB") +
                " at similarity $similarity (T_ALIAS_HI is 0.80, inclusive)",
        )
    }

    // ---- TRAVEL (10) --------------------------------------------------------
    // P-01 (27.0N, 84.0E) to P-02 (28.15N, 84.0E) is 127.7 km. At Vmax 120 km/h that is
    // 1.06 h, so the boundary sits between 1 h and 1.5 h — the scripts straddle it exactly,
    // including a row that is expected NOT to fire.
    private fun travelScenarios(): List<Scenario> = (0 until 10).map { i ->
        val hours = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 3.0, 6.0, 24.0, 72.0)[i]
        val first = event("evt-t-%02d-1".format(i), seq = 1, post = "P-02", hoursAgo = hours + 1.0,
            nameSha = SUBJECT_NAME_SHA, dob = SUBJECT_DOB, device = TRACKED_DEVICE)
        val second = event("evt-t-%02d-2".format(i), seq = 2, post = "P-01", hoursAgo = 1.0,
            nameSha = SUBJECT_NAME_SHA, dob = SUBJECT_DOB, device = TRACKED_DEVICE)
        val implied = distanceKm("P-01", "P-02") / hours

        Scenario(
            id = "scen-travel-%02d".format(i),
            category = Category.TRAVEL,
            expected = if (implied > 120.0) Verdict.RED else Verdict.GREEN,
            expectedCodes = emptySet(),
            events = listOf(first, second),
            aliasHits = emptyList(),
            watchlistHits = emptyList(),
            claimedNameSha = SUBJECT_NAME_SHA,
            claimedDob = SUBJECT_DOB,
            posts = POSTS,
            description = "P-02 → P-01 in $hours h implies " +
                "${"%.1f".format(implied)} km/h against Vmax 120 km/h",
        )
    }

    // ---- FACILITATOR (10) ---------------------------------------------------
    // A-FAC-01 fires when one subject crosses alongside at least FACILITATOR_MIN_GROUPS
    // distinct cohorts inside the window. `facilitatorRule` counts cohorts co-occurring at
    // the *same post* within FACILITATOR_COOCCUR_HOURS, so each planted cohort also has to
    // appear at the post the subject used — a script that only varied group counts without
    // varying the posts would be testing nothing.
    private fun facilitatorScenarios(): List<Scenario> = (0 until 10).map { i ->
        val cohortCount = listOf(1, 2, 3, 3, 4, 4, 5, 6, 8, 12)[i]
        val events = mutableListOf<CrossingEvent>()
        var seq = 1L
        for (c in 0 until 8) {
            val post = "P-0${1 + c % 3}"
            events += event(
                "evt-f-%02d-s-%02d".format(i, c), seq++, post, (c * 8).toDouble(),
                SUBJECT_NAME_SHA, SUBJECT_DOB, TRACKED_DEVICE,
            )
            for (g in 0 until cohortCount) {
                events += event(
                    "evt-f-%02d-g%02d-%02d".format(i, g, c), seq++, post, (c * 8).toDouble() + 0.25,
                    cohortNameSha(i, g), "1995-01-01", WATCH_DEVICE,
                )
            }
        }
        Scenario(
            id = "scen-fac-%02d".format(i),
            category = Category.FACILITATOR,
            expected = if (cohortCount >= 3) Verdict.AMBER else Verdict.GREEN,
            expectedCodes = if (cohortCount >= 3) setOf(FindingCode.A_FAC_01) else emptySet(),
            events = events,
            aliasHits = emptyList(),
            watchlistHits = emptyList(),
            claimedNameSha = SUBJECT_NAME_SHA,
            claimedDob = SUBJECT_DOB,
            posts = POSTS,
            description = "one subject crossing alongside $cohortCount distinct cohort(s) at the " +
                "same posts (A-FAC-01 fires at >= 3)",
        )
    }

    // ---- WATCHLIST (5) ------------------------------------------------------
    // A-WL-01 fires at any similarity at or above T_WL. The lowest row sits just above it,
    // which is the case worth testing: a watchlist band set too low buries the officer in
    // matches, and one set too high misses the entry it exists to catch.
    private fun watchlistScenarios(): List<Scenario> = (0 until 5).map { i ->
        val similarity = listOf(0.99f, 0.92f, 0.85f, 0.80f, 0.76f)[i]
        val subjectEvent = event("evt-w-%02d-1".format(i), seq = 1, post = "P-03", hoursAgo = 2.0,
            nameSha = SUBJECT_NAME_SHA, dob = SUBJECT_DOB, device = TRACKED_DEVICE)
        Scenario(
            id = "scen-wl-%02d".format(i),
            category = Category.WATCHLIST,
            expected = Verdict.AMBER,
            expectedCodes = setOf(FindingCode.A_WL_01),
            events = listOf(subjectEvent),
            aliasHits = emptyList(),
            watchlistHits = listOf(
                DiaryHit(
                    eventId = "wl-%02d".format(i),
                    similarity = similarity,
                    post = "P-03",
                    timestamp = subjectEvent.ts,
                    nameHash = SUBJECT_NAME_SHA,
                    dob = SUBJECT_DOB,
                ),
            ),
            claimedNameSha = SUBJECT_NAME_SHA,
            claimedDob = SUBJECT_DOB,
            posts = POSTS,
            description = "a watchlist entry at similarity $similarity (T_WL is 0.75)",
        )
    }

    // ---- BENIGN (5) ---------------------------------------------------------
    // Four separate posts, four separate cohorts, 24 h apart: an ordinary week. Every one of
    // these must come back GREEN, which is the half of the diary suite that keeps the other
    // half honest.
    private fun benignScenarios(): List<Scenario> = (0 until 5).map { i ->
        val posts = listOf("P-01", "P-02", "P-03", "P-05")
        val events = posts.mapIndexed { c, post ->
            event(
                "evt-b-%02d-%02d".format(i, c), (c + 1).toLong(), post, 24.0 * (c + 1),
                SUBJECT_NAME_SHA, SUBJECT_DOB, TRACKED_DEVICE,
            )
        }
        Scenario(
            id = "scen-benign-%02d".format(i),
            category = Category.BENIGN,
            expected = Verdict.GREEN,
            expectedCodes = emptySet(),
            events = events,
            aliasHits = emptyList(),
            watchlistHits = emptyList(),
            claimedNameSha = SUBJECT_NAME_SHA,
            claimedDob = SUBJECT_DOB,
            posts = POSTS,
            description = "four ordinary crossings, one post and one cohort each, 24 h apart",
        )
    }

    // ---------------------------------------------------------------- builders

    /**
     * A cohort name hash whose leading hex characters are unique.
     *
     * `dev.kasoti.diary.subjectKeyOf` derives the cohort key from the first
     * `FACILITATOR_GROUP_PREFIX_HEX` characters of `nameSha` — four, by default — precisely so
     * the diary stays unidentifiable. A name hash whose distinguishing text lives past that
     * prefix therefore collapses every cohort into one, and A-FAC-01, which *counts distinct
     * cohorts*, can never fire. Planting cohorts is only meaningful when they are
     * distinguishable in the prefix the rule actually reads, so the first four characters
     * here are a unique counter and the rest is filler.
     */
    private fun cohortNameSha(scenario: Int, cohort: Int): String {
        val tag = "%04x".format(scenario * 16 + cohort)
        return tag + "d3f1".repeat(15)
    }

    private fun event(
        id: String,
        seq: Long,
        post: String,
        hoursAgo: Double,
        nameSha: String,
        dob: String,
        device: String,
    ): CrossingEvent = CrossingEvent(
        // `CrossingEvent` requires an `evt_<26-char Crockford base32>` id (AGENTS.md §2), so
        // the readable script label becomes the sequence number and the ULID carries the
        // time. A readable id in this field would be rejected by the very rule the suite
        // exists to test.
        id = "evt_" + Ulid.generate(REFERENCE_MILLIS - (hoursAgo * 3_600_000).toLong(), Random(seq * 7919 + 11)),
        seq = seq,
        device = device,
        post = post,
        ts = Iso(REFERENCE_MILLIS - (hoursAgo * 3_600_000).toLong()),
        track = Track.PAPER_ID,
        nameSha = nameSha,
        dob = dob,
        docHash = digestOf("doc:$id"),
        embModel = EMB_MODEL,
        emb = embeddingFor(id),
        qScale = 1f / 127f,
        q = 0.95f,
        verdict = Verdict.GREEN,
        findings = emptyList(),
        prev = "",
        extra = mapOf("scenario" to dev.kasoti.json.JsonValue.Str(id)),
    )

    /**
     * A deterministic 128-dimension int8 vector per event.
     *
     * The rules under test do not read the vector — `aliasRule` consumes ranked
     * [DiaryHit]s and `facilitatorRule` reads only the hashed cohort key — but
     * [CrossingEvent] requires one, and a real one means the fixture is a valid diary record
     * rather than a special case that would be rejected the moment the constructor tightened.
     */
    private fun embeddingFor(id: String): ByteArray = ByteArray(128) { index ->
        val v = (index * 31 + id.length * 17 + id.hashCode()) and 0xFF
        (v - 128).toByte()
    }

    private fun Iso(epochMillis: Long): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMillis))

    /** Great-circle distance, matching `dev.kasoti.diary.haversineKm`. */
    fun distanceKm(a: String, b: String): Double {
        val first = POSTS.getValue(a)
        val second = POSTS.getValue(b)
        val lat1 = Math.toRadians(first.latDeg)
        val lat2 = Math.toRadians(second.latDeg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(second.lonDeg - first.lonDeg)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * 6371.0088 * Math.asin(Math.sqrt(h))
    }

    /**
     * A real, deterministic SHA-256 digest of [label], as lowercase hex.
     *
     * `CrossingEvent` requires `docHash`/`nameSha` to be genuine hex digests (SYNC.md §3), so
     * the fixtures must produce real ones rather than readable placeholders — a fixture that
     * violates the invariant it exists to test is worse than no fixture. `:eval` is a JVM
     * module, so JCA is available here; `:core` is common code and is not.
     */
    private fun digestOf(label: String): String = dev.kasoti.crypto.Hex.encode(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(label.toByteArray(Charsets.UTF_8)),
    )
}
