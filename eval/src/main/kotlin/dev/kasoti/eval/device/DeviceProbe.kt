package dev.kasoti.eval.device

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A device the harness can see.
 *
 * [isEmulator] is explicit rather than inferred later, because EVAL.md §8 forbids
 * cherry-picked devices and a gate run on an emulator is a different measurement from a gate
 * run on the NAMED low-end phone. The flag lets the summary say which it was without
 * re-deriving it from the model string.
 */
data class AttachedDevice(
    val serial: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    val isEmulator: Boolean,
) {
    val role: String get() = if (isEmulator) "emulator (NOT a NAMED gate device)" else "physical"
}

/**
 * Finds attached Android devices.
 *
 * Returns an empty list rather than throwing when `adb` is absent, so the caller decides
 * what "no device" means. For the parity suite it means a loud skip (EVAL.md §3: parity
 * needs a device and must skip loudly and explicitly, never silently pass); for the
 * latency suite it means the numbers are host numbers. The distinction is the caller's,
 * and giving this function an opinion about it would be the wrong place to put one.
 */
object DeviceProbe {

    private const val ADB_TIMEOUT_SECONDS = 20L

    fun find(serialHint: String? = null, adbPath: String = defaultAdb()): List<AttachedDevice> {
        val adb = File(adbPath)
        if (!adb.isFile || !adb.canExecute()) return emptyList()

        val listed = run(listOf(adbPath, "devices", "-l"), ADB_TIMEOUT_SECONDS) ?: return emptyList()
        if (!listed.second) return emptyList()

        val devices = mutableListOf<AttachedDevice>()
        for (line in listed.first.lineSequence().drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*")) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] != "device") continue // offline / unauthorised
            val serial = parts[0]
            if (serialHint != null && serial != serialHint) continue
            devices += describe(serial, adbPath) ?: continue
        }
        return devices
    }

    private fun describe(serial: String, adbPath: String): AttachedDevice? {
        fun prop(name: String): String =
            run(listOf(adbPath, "-s", serial, "shell", "getprop", name), ADB_TIMEOUT_SECONDS)
                ?.first?.trim().orEmpty()

        val model = prop("ro.product.model").ifEmpty { "unknown-model" }
        val version = prop("ro.build.version.release").ifEmpty { "unknown" }
        val sdk = prop("ro.build.version.sdk").toIntOrNull() ?: 0
        val fingerprint = prop("ro.build.fingerprint")
        val emulatorHints = listOf("sdk_gphone", "emulator", "generic", "goldfish", "ranchu", "vbox")
        val isEmulator = emulatorHints.any { model.contains(it, ignoreCase = true) } ||
            fingerprint.contains("generic", ignoreCase = true) ||
            serial.startsWith("emulator-")
        return AttachedDevice(serial, model, version, sdk, fingerprint, isEmulator)
    }

    private fun defaultAdb(): String {
        val fromEnv = System.getenv("ADB")
        if (!fromEnv.isNullOrBlank()) return fromEnv
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!sdk.isNullOrBlank()) return File(sdk, "platform-tools/adb").path
        return "adb"
    }

    private fun run(command: List<String>, timeoutSeconds: Long): Pair<String, Boolean>? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().readText() to (process.exitValue() == 0)
        }
    }.getOrNull()
}
