package dev.kasoti.eval.calibration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A `device_calib.json` as written by the in-app guided routine (DATA.md §5).
 *
 * The schema is intentionally small: white-balance gains, pixels-per-millimetre, and a
 * focus verdict. It exists so that a macro patch captured on an uncalibrated phone is
 * visibly uncalibrated rather than quietly comparable with a calibrated one — the failure
 * this prevents is a threshold "tuned" on two different colour pipelines.
 */
@Serializable
data class DeviceCalibration(
    val deviceId: String,
    /** ISO-8601 UTC instant the routine completed, not when the file was written. */
    val capturedAtUtc: String,
    /** Per-channel white-balance gains, in the order the capture stack applies them. */
    val whiteBalanceGains: List<Float>,
    val pxPerMm: Float,
    val focusOk: Boolean,
    /** Version of the printable card the routine was run against (DATA.md §5 §). */
    val cardVersion: String = "",
    val operator: String = "",
) {
    companion object {
        /**
         * EVAL.md §7: macro and face numbers do not count without calibration this fresh.
         * Seven days, measured against the *capture* time, not the file time.
         */
        const val MAX_AGE_DAYS: Long = 7
    }
}

/** Why a calibration did or did not clear the gate. The text is the error message. */
@Serializable
data class CalibrationVerdict(
    val deviceId: String,
    val present: Boolean,
    val ageDays: Double? = null,
    val accepted: Boolean,
    val reason: String,
    val calibration: DeviceCalibration? = null,
    @SerialName("source") val source: String = "",
) {
    companion object {
        fun missing(deviceId: String, where: String) = CalibrationVerdict(
            deviceId = deviceId,
            present = false,
            accepted = false,
            reason = "No device_calib.json for '$deviceId' (looked in $where). " +
                "Macro metrics are uncalibrated: white-balance and pixels-per-millimetre vary " +
                "per device and per lighting, so a threshold fitted across an uncalibrated " +
                "device is fitted to the camera's colour pipeline rather than to the print " +
                "process. EVAL.md §7 requires calibration no older than " +
                "${DeviceCalibration.MAX_AGE_DAYS} days before any macro or face number counts.",
            source = where,
        )
    }
}

/**
 * The calibration gate (EVAL.md §7).
 *
 * Fails closed. A stale, malformed or absent calibration refuses the macro suite outright;
 * it does not degrade to "report anyway" and it does not warn-and-continue, because the
 * resulting number would be indistinguishable from a calibrated one once it is in a table.
 */
object CalibrationGate {

    fun evaluate(
        deviceId: String,
        calibration: DeviceCalibration?,
        nowUtcEpochSeconds: Long,
        sourcePath: String,
    ): CalibrationVerdict {
        if (calibration == null) {
            return CalibrationVerdict.missing(deviceId, sourcePath)
        }
        if (calibration.deviceId != deviceId) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                accepted = false,
                reason = "device_calib.json at $sourcePath is for device " +
                    "'${calibration.deviceId}', not '$deviceId'. Using another device's " +
                    "white-balance gains would silently rescale every macro patch.",
                source = sourcePath,
            )
        }

        val captured = parseInstant(calibration.capturedAtUtc)
        if (captured == null) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                accepted = false,
                reason = "device_calib.json has an unparseable capturedAtUtc " +
                    "'${calibration.capturedAtUtc}'; expected ISO-8601 UTC (AGENTS.md §2). " +
                    "An unreadable capture time means the freshness rule cannot be checked, " +
                    "so the calibration is refused.",
                source = sourcePath,
            )
        }

        // A calibration stamped in the future is a clock problem, not a fresh calibration.
        if (captured > nowUtcEpochSeconds) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                ageDays = 0.0,
                accepted = false,
                reason = "device_calib.json is stamped ${((captured - nowUtcEpochSeconds) / 3600.0)}h " +
                    "in the future. The routine's clock and this host's clock disagree, so the " +
                    "freshness check cannot be trusted; fix the skew (AGENTS.md §2) and re-run " +
                    "the calibration routine.",
                source = sourcePath,
            )
        }

        val ageDays = (nowUtcEpochSeconds - captured).toDouble() / 86_400.0
        if (ageDays > DeviceCalibration.MAX_AGE_DAYS) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                ageDays = ageDays,
                accepted = false,
                reason = "device_calib.json is ${"%.1f".format(ageDays)} days old; EVAL.md §7 caps " +
                    "macro calibration at ${DeviceCalibration.MAX_AGE_DAYS} days. Lighting and " +
                    "focus drift over that window, so a fresh capture is required before the " +
                    "numbers are comparable with the ones the threshold was fitted on.",
                source = sourcePath,
            )
        }

        if (!calibration.focusOk) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                ageDays = ageDays,
                accepted = false,
                reason = "device_calib.json reports focusOk=false. The clip was not seated flat on " +
                    "the calibration card, so pixels-per-millimetre is unreliable and macro " +
                    "feature scales cannot be compared across devices.",
                source = sourcePath,
            )
        }

        if (calibration.whiteBalanceGains.size != 3) {
            return CalibrationVerdict(
                deviceId = deviceId,
                present = true,
                ageDays = ageDays,
                accepted = false,
                reason = "device_calib.json declares ${calibration.whiteBalanceGains.size} " +
                    "white-balance gain(s); exactly 3 are required for a colour pipeline.",
                source = sourcePath,
            )
        }

        return CalibrationVerdict(
            deviceId = deviceId,
            present = true,
            ageDays = ageDays,
            accepted = true,
            reason = "calibration accepted: ${"%.2f".format(ageDays)} days old " +
                "(limit ${DeviceCalibration.MAX_AGE_DAYS}), ${calibration.pxPerMm} px/mm, " +
                "wb gains ${calibration.whiteBalanceGains}",
            calibration = calibration,
            source = sourcePath,
        )
    }

    private fun parseInstant(text: String): Long? = runCatching {
        java.time.Instant.parse(text).epochSecond
    }.getOrNull()
}
