package dev.kasoti.android.field

import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.TrustState
import dev.kasoti.fusion.Verdict
import dev.kasoti.fusion.VerdictReport
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import kotlin.random.Random

/**
 * A trust-lane enrolment (FR-H5, FUSION.md §8).
 *
 * [subjectHash] is a salted hash, never a name or an embedding. The lane exists to make a
 * *repeat* crossing fast, so the only thing it needs to hold is something it can compare a face
 * to — and holding a name to do that would turn a speed optimisation into a readable roll of
 * who crossed the post (THREAT_MODEL.md §4, the same trade `CrossingEvent.nameSha` makes).
 *
 * [enrolledOn] and [reverifyOn] are `IsoDate`s rather than instants because the policy is
 * expressed in days (`REVERIFY_DAYS`) and a post is a place where "30 days" is the only
 * question anyone ever asks.
 */
data class Enrolment(
    val subjectHash: String,
    val enrolledOn: IsoDate,
    val reverifyOn: IsoDate,
    val consentRecorded: Boolean,
    val revoked: Boolean = false,
    val revokedOn: IsoDate? = null,
) {
    fun isLive(today: IsoDate): Boolean = !revoked && today < reverifyOn

    /** Days until re-verification, floored at zero so a UI cannot render "-3 days". */
    fun daysUntilReverify(today: IsoDate): Int =
        if (isLive(today)) CalendarArithmetic.daysBetween(today, reverifyOn).coerceAtLeast(0) else 0
}

/** Where enrolments are kept. A platform class implements this; tests use the in-memory one. */
interface EnrolmentStore {
    fun get(subjectHash: String): Enrolment?
    fun put(enrolment: Enrolment)
    fun all(): List<Enrolment>
    fun wipe()
}

class InMemoryEnrolmentStore : EnrolmentStore {
    private val bySubject = LinkedHashMap<String, Enrolment>()
    override fun get(subjectHash: String): Enrolment? = bySubject[subjectHash]
    override fun put(enrolment: Enrolment) { bySubject[enrolment.subjectHash] = enrolment }
    override fun all(): List<Enrolment> = bySubject.values.toList()
    override fun wipe() = bySubject.clear()
}

/** Why a trust-lane action was refused. Every case maps to an operator-facing `FindingCode`. */
enum class TrustOutcome {
    /** Enrolment created. The fast path is available from now. */
    ENROLLED,

    /** An existing, live enrolment: the face-only check may take its shortcut. */
    FAST_PATH,

    /** No enrolment: a full stranger check. The normal case. */
    FULL_CHECK,

    /** A re-verification is due. Full check, even though an enrolment exists. */
    REVERIFY_DUE,

    /** Revoked: full check, and the fast path is permanently closed. */
    REVOKED,

    /** The scheduler selected this subject. Full check anyway (FUSION.md §8, p = RECHECK_P). */
    RANDOM_RECHECK,

    /** Enrolment refused: the stranger check was not GREEN, or consent is missing. */
    ENROLMENT_REFUSED,

    /** Enrolment refused: the supervisor PIN did not verify. */
    PIN_REJECTED,

    /** Enrolment refused: a PIN is required and none was supplied. */
    PIN_REQUIRED,
}

/**
 * The trust lane (FR-H5).
 *
 * ## The policy, as FUSION.md §8 states it
 *
 * Enrol requires a *full stranger check that returned GREEN*, a live supervisor PIN, recorded
 * subject consent, and stored photo-zone evidence. Any one missing and the enrolment is
 * refused. The fast path then requires: the enrolment is live, the re-verify interval has not
 * elapsed, the scheduler did not select this subject, and the check that ran cleared the face
 * with liveness and a diary re-check behind it — which is `:core`'s `TRUST_OK`, not this
 * class's word to give.
 *
 * ## What this class does *not* do
 *
 * It never returns `ENROLLED` trust on its own. [decide] produces a [TrustState] to hand to
 * `Evidence.trust`, and `:core` independently requires the face to have cleared at or above
 * `T_FACE_GREEN`, the passive-liveness score to be at or above `LIVE_PASSIVE_MIN`, a
 * non-null head-turn result, and a diary layer to be present before it attaches `TRUST_OK`.
 * The lane therefore cannot fast-path a case the engine would not have cleared anyway — the
 * fastest lane must also be the *safer* one, or operators will start refusing to enrol people
 * for whose fast path they would be responsible.
 *
 * ## The random re-check draw is recorded
 *
 * The scheduler draw is a seeded, reproducible number and [TrustDecision.draw] carries it, so
 * a supervisor asking "was this a random re-check?" gets an answer from the audit record
 * rather than from memory. A re-check that cannot be reconstructed is a re-check that looks
 * like a bug.
 */
class TrustLane(
    private val store: EnrolmentStore,
    private val registry: ThresholdRegistry,
    private val pinVerifier: SupervisorPin,
    private val random: Random = Random.Default,
) {

    data class TrustDecision(
        val state: TrustState,
        val outcome: TrustOutcome,
        val code: FindingCode?,
        /** The re-check draw in 0..1, when one was taken. `null` when no draw happened. */
        val draw: Float? = null,
        val daysUntilReverify: Int = 0,
    ) {
        val isFastPath: Boolean get() = state == TrustState.ENROLLED
    }

    /**
     * Which lane this crossing takes.
     *
     * @param subjectHash the salted subject hash. `null` for a stranger with no prior crossing.
     * @param today the caller's date, never read from the device clock inside this class.
     */
    fun decide(subjectHash: String?, today: IsoDate): TrustDecision {
        if (subjectHash.isNullOrBlank()) return TrustDecision(TrustState.VERIFY, TrustOutcome.FULL_CHECK, null)

        val enrolment = store.get(subjectHash)
            ?: return TrustDecision(TrustState.VERIFY, TrustOutcome.FULL_CHECK, null)

        if (enrolment.revoked) {
            return TrustDecision(TrustState.REVOKED, TrustOutcome.REVOKED, FindingCode.TRUST_OK)
        }
        if (!enrolment.isLive(today)) {
            return TrustDecision(TrustState.VERIFY, TrustOutcome.REVERIFY_DUE, null, daysUntilReverify = 0)
        }

        // The scheduler runs *before* the fast path is offered, so a selected subject cannot
        // be waved through by an existing enrolment. Same rule as `:core`'s `trustFlag`.
        val draw = random.nextFloat()
        val recheckBar = registry[ThresholdName.RECHECK_P].toFloat()
        if (draw < recheckBar) {
            return TrustDecision(TrustState.RANDOM_RECHECK, TrustOutcome.RANDOM_RECHECK, null, draw = draw)
        }

        return TrustDecision(
            state = TrustState.ENROLLED,
            outcome = TrustOutcome.FAST_PATH,
            code = null,
            // The draw travels on the fast path as well as the re-check path. A supervisor
            // asking "was this a random re-check?" must get the same answer either way, and
            // a draw that is only recorded when it *fires* is a draw that cannot be audited
            // when it did not — which is exactly the case worth auditing.
            draw = draw,
            daysUntilReverify = enrolment.daysUntilReverify(today),
        )
    }

    /**
     * Enrol a subject who has just cleared a full stranger check.
     *
     * @param report the verdict from that full check. **Must** be GREEN: FUSION.md §8 is
     *   explicit that enrolment follows a cleared stranger check, and accepting anything else
     *   would let a supervisor enrol someone the system has just flagged.
     * @param consentRecorded subject consent, captured at the desk. Not optional.
     */
    fun enrol(
        subjectHash: String,
        report: VerdictReport,
        pin: String,
        today: IsoDate,
        consentRecorded: Boolean,
    ): TrustDecision {
        if (pin.isBlank()) return TrustDecision(TrustState.VERIFY, TrustOutcome.PIN_REQUIRED, null)
        if (!pinVerifier.verify(pin)) {
            return TrustDecision(TrustState.VERIFY, TrustOutcome.PIN_REJECTED, null)
        }
        if (report.verdict != Verdict.GREEN) {
            return TrustDecision(TrustState.VERIFY, TrustOutcome.ENROLMENT_REFUSED, report.firstHit?.code)
        }
        if (!consentRecorded) {
            return TrustDecision(TrustState.VERIFY, TrustOutcome.ENROLMENT_REFUSED, null)
        }
        if (report.demoMode) {
            // A demo enrolment in a real enrolment store is the fastest way to get a demo
            // fixture into production trust (invariant I7). Refused, not flagged.
            return TrustDecision(TrustState.VERIFY, TrustOutcome.ENROLMENT_REFUSED, null)
        }

        val reverifyOn = CalendarArithmetic.plusDays(today, registry[ThresholdName.REVERIFY_DAYS].toInt())

        store.put(
            Enrolment(
                subjectHash = subjectHash,
                enrolledOn = today,
                reverifyOn = reverifyOn,
                consentRecorded = consentRecorded,
            ),
        )
        return TrustDecision(TrustState.ENROLLED, TrustOutcome.ENROLLED, FindingCode.TRUST_OK)
    }

    /**
     * Revoke instantly (FUSION.md §8: "instant revoke").
     *
     * PIN-gated, because revocation is a supervisor action — but *revocation is not gated on
     * the reason*. A supervisor who revokes a wrongly-enrolled person must not have to argue
     * with a form first, and the audit record keeps the timestamp either way.
     */
    fun revoke(subjectHash: String, pin: String, today: IsoDate): TrustDecision {
        if (pin.isBlank()) return TrustDecision(TrustState.VERIFY, TrustOutcome.PIN_REQUIRED, null)
        if (!pinVerifier.verify(pin)) return TrustDecision(TrustState.VERIFY, TrustOutcome.PIN_REJECTED, null)
        val existing = store.get(subjectHash)
            ?: return TrustDecision(TrustState.VERIFY, TrustOutcome.FULL_CHECK, null)
        store.put(existing.copy(revoked = true, revokedOn = today))
        return TrustDecision(TrustState.REVOKED, TrustOutcome.REVOKED, null)
    }
}

/**
 * Supervisor PIN verification.
 *
 * Not the app's business how the PIN is stored — that is the platform layer's, and it is
 * expected to be an Android Keystore-backed hash (DESIGN.md §3). What the field layer owns is
 * the *shape*: a comparison, a minimum length, and a refusal rather than a default.
 */
fun interface SupervisorPin {

    fun verify(pin: String): Boolean

    companion object {
        /**
         * The shortest PIN the lane accepts.
         *
         * Four digits is the floor and it is genuinely weak; it is here because a jawan
         * enrolling people one-handed with gloves on cannot be asked for a passphrase, and a
         * PIN nobody can type is a PIN nobody uses. The threat it does not stop is an insider
         * with the handset, which THREAT_MODEL.md accepts as out of scope for the fast path.
         */
        const val MIN_LENGTH = 4

        /** For tests and demo mode only. Never a production implementation. */
        fun fixed(expected: String): SupervisorPin = SupervisorPin { candidate ->
            candidate.length >= MIN_LENGTH && dev.kasoti.crypto.constantTimeEquals(
                candidate.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
        }
    }
}
