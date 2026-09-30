package dev.kasoti.eval.split

import dev.kasoti.eval.calibration.CalibrationGate
import dev.kasoti.eval.calibration.CalibrationVerdict
import dev.kasoti.eval.calibration.DeviceCalibration
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the split manifests off disk.
 *
 * Manifests live in `eval/data/manifests/` and are committed. A missing manifest is a hard
 * error rather than a default: silently substituting an empty manifest would turn "the
 * operator forgot to record the provenance" into "the operator recorded that nothing has
 * provenance", which is the exact confusion this mechanism exists to prevent.
 */
class ManifestStore(private val root: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = false
        encodeDefaults = true
    }

    val datasetManifestPath: Path get() = root.resolve("datasets.json")
    val ledgerPath: Path get() = root.resolve("threshold_provenance.json")

    /** Raised for anything the operator must fix on disk. Never swallowed. */
    class ManifestError(message: String) : IllegalStateException(message)

    fun loadDatasetManifest(): DatasetManifest = read(DatasetManifest.serializer(), datasetManifestPath)

    fun loadLedger(): ThresholdLedger = read(ThresholdLedger.serializer(), ledgerPath)

    fun loadCalibration(deviceId: String): Pair<DeviceCalibration?, String> {
        val path = root.resolve("calib").resolve(deviceId).resolve("device_calib.json")
        if (!Files.isRegularFile(path)) return null to path.toString()
        val text = runCatching { Files.readString(path) }
            .getOrElse { throw ManifestError("device_calib.json at $path is unreadable: ${it.message}") }
        val calibration = runCatching {
            json.decodeFromString(DeviceCalibration.serializer(), text)
        }.getOrElse {
            throw ManifestError(
                "device_calib.json at $path does not match the DATA.md §5 schema: ${it.message}. " +
                    "The calibration gate fails closed, so a malformed file is a refusal to run " +
                    "macro, not a warning.",
            )
        }
        return calibration to path.toString()
    }

    fun evaluateCalibration(deviceId: String, nowUtcEpochSeconds: Long): CalibrationVerdict {
        val (calibration, path) = loadCalibration(deviceId)
        return CalibrationGate.evaluate(deviceId, calibration, nowUtcEpochSeconds, path)
    }

    private fun <T> read(serializer: kotlinx.serialization.KSerializer<T>, path: Path): T {
        if (!Files.isRegularFile(path)) {
            throw ManifestError(
                "required manifest $path is missing. The harness enforces split discipline " +
                    "by manifest (EVAL.md §8), so it cannot run without it; an absent manifest " +
                    "must not be read as 'nothing needs checking'.",
            )
        }
        val text = runCatching { Files.readString(path) }
            .getOrElse { throw ManifestError("manifest $path is unreadable: ${it.message}") }
        return runCatching { json.decodeFromString(serializer, text) }
            .getOrElse { throw ManifestError("manifest $path is malformed: ${it.message}") }
    }
}
