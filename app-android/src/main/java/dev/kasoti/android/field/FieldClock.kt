package dev.kasoti.android.field

import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import kotlin.random.Random

/**
 * The clock, injected (AGENTS.md §2: UTC ISO-8601 everywhere; §5: never read a device clock
 * for a security decision *without a skew note*).
 *
 * Two-digit MRZ years cannot be resolved without a caller-supplied reference year, and expiry
 * logic cannot run at all without a "today", so the app cannot avoid having a clock. What it
 * can avoid is having an *unexamined* one — hence [SkewReport] below, which is what discharges
 * the §5 obligation rather than a comment that says "we know about skew".
 */
interface FieldClock {
    /** `YYYY-MM-DDTHH:MM:SS.mmmZ` — the width `IsoInstant.parse` requires. */
    fun nowIso(): String

    /** The date half of [nowIso], for expiry and age rules. */
    fun today(): IsoDate

    /** Epoch millis, for ULID generation. `IsoInstant.parse(nowIso())` is the pure equivalent. */
    fun nowMillis(): Long
}

/** A fixed clock. Tests, demo fixtures, and the reproducible eval runs all use this. */
class FixedClock(private val instant: String) : FieldClock {
    init {
        require(dev.kasoti.diary.IsoInstant.isValid(instant)) { "not a UTC ISO-8601 instant: $instant" }
    }

    override fun nowIso(): String = instant
    override fun today(): IsoDate = IsoDate.parse(instant.substring(0, 10))!!
    override fun nowMillis(): Long = dev.kasoti.diary.IsoInstant.parse(instant)!!
}

/**
 * Device-clock disagreement, as a first-class value.
 *
 * SYNC.md §2 sets the bar: beyond `CLOCK_SKEW_MAX_MIN` minutes is a *warning*, not a rejection,
 * and it is reported in the merge report. The same number is reused here because the exposure is
 * the same: a phone whose clock is a year fast decides every expiry rule wrongly, and the
 * symptom — a genuine document read as expired — is indistinguishable from a forgery unless the
 * skew is recorded next to the verdict.
 *
 * [trustedReference] is the last time this device agreed with a reference it trusts (a sync
 * peer, or the provisioning time). `null` means "never checked", which is [State.UNVERIFIED]
 * and is itself worth showing on a diagnostics screen.
 */
data class SkewReport(
    val state: State,
    val minutes: Int,
    val trustedReference: String?,
) {
    enum class State {
        /** Agrees within the policy bar. */
        AGREEING,

        /** Beyond the bar. Verdicts still compute; the report is shown and logged. */
        SKEWED,

        /** No reference point yet. Not the same as agreeing. */
        UNVERIFIED,
    }

    val isBlocking: Boolean get() = false

    companion object {
        /**
         * @param trustedReferenceIso the last instant this device was known to agree with a
         *   trusted source, or `null`.
         * @return minutes between [deviceNowIso] and [trustedReferenceIso], or [State.UNVERIFIED].
         */
        fun measure(
            deviceNowIso: String,
            trustedReferenceIso: String?,
            registry: ThresholdRegistry,
        ): SkewReport {
            val deviceMillis = dev.kasoti.diary.IsoInstant.parse(deviceNowIso)
            val referenceMillis = trustedReferenceIso?.let { dev.kasoti.diary.IsoInstant.parse(it) }
                ?: return SkewReport(State.UNVERIFIED, 0, null)
            if (deviceMillis == null || referenceMillis == null) {
                return SkewReport(State.UNVERIFIED, 0, trustedReferenceIso)
            }
            val deltaMinutes = kotlin.math.abs(deviceMillis - referenceMillis) / MILLIS_PER_MINUTE
            val bar = registry[ThresholdName.CLOCK_SKEW_MAX_MIN]
            val state = if (deltaMinutes > bar) State.SKEWED else State.AGREEING
            return SkewReport(state, deltaMinutes.toInt(), trustedReferenceIso)
        }

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Identifier minting (AGENTS.md §2: `evt_<ulid>`, `case_<ulid>`, never sequential-only).
 *
 * The ids are ULIDs built from [FieldClock] rather than from a counter, so two phones that both
 * start at zero cannot produce colliding ids, and a case id is self-describing about when it
 * happened. `:core` owns the alphabet and the Crockford base32 encoding
 * (`dev.kasoti.diary.Ulid`); this is only the app's naming of them.
 */
class IdFactory(
    private val clock: FieldClock,
    private val random: Random = Random.Default,
) {
    fun caseId(): String = "case_" + dev.kasoti.diary.Ulid.generate(clock.nowMillis(), random)

    fun eventId(): String = dev.kasoti.diary.Ulid.eventId(clock.nowMillis(), random)

    /** A per-install device id, written once at provisioning (SYNC.md §6: re-provision on a swap). */
    fun deviceId(): String = "post-" + dev.kasoti.diary.Ulid.generate(clock.nowMillis(), random).take(8)
}
