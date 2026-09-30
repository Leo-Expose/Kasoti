package dev.kasoti.android.field

import dev.kasoti.diary.CrossingEvent
import dev.kasoti.diary.Diary
import dev.kasoti.diary.DiaryMergeReport
import dev.kasoti.fusion.VerdictReport

/**
 * Invariant I7: demo evidence never reaches a real diary.
 *
 * ## The attack this exists for
 *
 * Demo mode is the single most convenient thing in the app to leave switched on, and its whole
 * purpose is to make a *specific person* look like they crossed the border. A demo run that
 * reached the real diary would put a fabricated crossing into a law-enforcement log, and it
 * would be indistinguishable from a real one in every downstream view: the console, the sync
 * bundle, the shift report. Nothing in the wire format says "this was a rehearsal" — the flag
 * is local, and that is by design (SYNC.md §1: no bundle ever carries a tombstone, and the
 * same reasoning means a bundle never carries a demo marker either).
 *
 * So isolation has to happen at the *only* point where it cannot be forgotten: the sink.
 * Everything that writes to a diary goes through here, and this refuses.
 *
 * ## How it refuses
 *
 * By making the check impossible to omit. [Sink.append] is the whole write API, it requires a
 * [VerdictReport], and the demo bit is read from that report rather than from a parameter —
 * because `:core` carries `Evidence.demoMode` through to `VerdictReport.demoMode` verbatim, so
 * there is one value, set once, at the top of the cascade, and it cannot drift from what was
 * actually decided.
 *
 * A refused append is a [Refusal] with a reason, not an exception: the operator is mid-demo and
 * an exception would take the app down in front of a room. The refusal is logged and counted.
 */
class DiaryGuard(private val diary: Diary, private val log: FieldLog = FieldLog.NOOP) {

    data class Refusal(val recordId: String, val reason: Reason) {
        enum class Reason {
            /** The case that produced this event was a demo. I7. */
            DEMO_EVIDENCE,

            /** A sync bundle carried a demo record from another device. I7, cross-device. */
            INCOMING_DEMO_RECORD,
        }
    }

    sealed interface Result {
        data class Written(val recordId: String) : Result
        data class Refused(val refusal: Refusal) : Result
    }

    data class MergeSummary(
        val accepted: Int,
        /** Held back because they were demo records, and therefore never merged. */
        val refusedDemo: Int,
        val report: DiaryMergeReport,
    )

    /**
     * Append a locally captured crossing.
     *
     * @param report the verdict this event came from. Required, and used for both the demo bit
     *   and the audit line — a record written without its verdict could not be explained later.
     */
    fun append(caseId: String, event: CrossingEvent, report: VerdictReport): Result {
        if (report.demoMode) {
            log.write(
                FieldLogEntry.of(
                    kind = FieldLogEntry.Kind.DEMO,
                    caseId = caseId,
                    detail = "diary append refused: demo evidence (I7)",
                ),
            )
            return Result.Refused(Refusal(event.id, Refusal.Reason.DEMO_EVIDENCE))
        }
        diary.append(event)
        return Result.Written(event.id)
    }

    /**
     * Merge an incoming sync bundle, holding demo records back.
     *
     * `:core`'s `Diary.mergeIncoming` has no demo concept, and it must not acquire one: a
     * bundle is data from a peer, and the peer is not trusted to label its own fixtures. So
     * the split happens here, before the call, and the held-back records go to the caller's
     * quarantine with the reason attached.
     */
    fun mergeIncoming(
        records: List<CrossingEvent>,
        demoRecordIds: Set<String>,
        quarantine: (Refusal.Reason, String, List<CrossingEvent>) -> Unit,
    ): MergeSummary {
        val (demo, real) = records.partition { it.id in demoRecordIds }
        if (demo.isNotEmpty()) {
            log.write(
                FieldLogEntry.of(
                    kind = FieldLogEntry.Kind.DIARY,
                    detail = "merge held back ${demo.size} demo record(s) (I7)",
                ),
            )
            quarantine(Refusal.Reason.INCOMING_DEMO_RECORD, "demo records from a peer", demo)
        }
        val report = if (real.isEmpty()) {
            dev.kasoti.diary.DiaryMergeReport(accepted = 0, dupes = 0, quarantined = demo.size)
        } else {
            diary.mergeIncoming(real).also { merged ->
                return MergeSummary(merged.accepted, demo.size, merged)
            }
        }
        return MergeSummary(0, demo.size, report)
    }

    /**
     * Whether a record is safe to export into a bundle.
     *
     * Export is the other direction of I7 and the easier one to forget: a demo record that
     * never entered the local diary cannot be exported, but a record that *did* (through a
     * bug, or through an older build) must not leave the device either. The check is here so
     * the export path has one place to ask.
     */
    fun exportable(event: CrossingEvent, report: VerdictReport): Boolean = !report.demoMode
}
