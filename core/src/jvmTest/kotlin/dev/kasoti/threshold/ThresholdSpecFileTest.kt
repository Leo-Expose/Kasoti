package dev.kasoti.threshold

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tie between `fusion/thresholds.v1.json` and the text `ThresholdSpecSource` embeds.
 *
 * `:core`'s `commonMain` cannot read a file without an `expect`/`actual` pair, and `:core` has
 * none (its Android target is only wired when an SDK is present, so an `actual` would have to
 * be written for a target nobody compiles here). So the registry travels as a string constant
 * and this test is what stops the two copies diverging.
 *
 * That makes this file the one place in the repository where the *shipped* registry is read
 * from disk rather than from memory, which is also why it lives in `jvmTest` and not
 * `commonTest`: reading a path is platform I/O, and `commonTest` has no business doing it. The
 * schema itself is covered platform-free by `ThresholdSpecTest`.
 *
 * The failure this guards against is the quiet one. Edit the JSON to re-tune something, forget
 * the constant, and every test still passes — the constant is a valid registry, it is simply
 * the *old* one. Nothing else in the build would notice, and the re-tune would appear to have
 * happened while every device ran the previous operating point.
 */
class ThresholdSpecFileTest {

    private val registryFile: File =
        File("src/commonMain/kotlin/dev/kasoti/fusion/thresholds.v1.json")

    @Test
    fun theRegistryFileIsWhereTheBuildScriptSaysItIs() {
        assertTrue(
            registryFile.isFile,
            "expected the registry at ${registryFile.path} " +
                "(working directory is ${File(".").absolutePath}); ThresholdSpecSource embeds this exact text",
        )
    }

    @Test
    fun theEmbeddedCopyIsCharacterForCharacterTheFileOnDisk() {
        val onDisk = registryFile.readText()
        assertEquals(
            onDisk,
            ThresholdSpecSource.TEXT,
            "fusion/thresholds.v1.json and ThresholdSpecSource.TEXT have diverged. " +
                "Re-embed the file's contents into ThresholdSpecSource; the JSON is the registry " +
                "of record (AGENTS.md §2) and the constant is only how :core reads it.",
        )
    }

    @Test
    fun theFileOnDiskParsesAsTheRegistryTheBuildActuallyRuns() {
        // Parsing the disk copy again, independently of the constant, is what catches a JSON
        // that is byte-identical but semantically broken after a merge -- and, more usefully,
        // reports the failure against the file a maintainer would go and edit.
        val fromDisk = ThresholdSpec.parse(registryFile.readText())
        assertEquals(spec_version(), fromDisk.version)
        assertEquals(ThresholdName.entries.size, fromDisk.thresholds.size)
    }

    @Test
    fun theFileNamesEveryEnumConstantExactlyOnce() {
        val fromDisk = ThresholdSpec.parse(registryFile.readText())
        assertEquals(
            ThresholdName.entries.map { it.name }.toSet(),
            fromDisk.thresholds.keys,
            "the enum and the registry file disagree about which thresholds exist",
        )
    }

    private fun spec_version(): String = ThresholdSpec.parse(ThresholdSpecSource.TEXT).version
}
