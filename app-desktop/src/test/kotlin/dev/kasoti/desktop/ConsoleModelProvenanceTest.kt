package dev.kasoti.desktop

import dev.kasoti.desktop.screen.ModelLocator
import dev.kasoti.desktop.screen.SvmModelFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the console says about the macro model it is — or is not — using.
 *
 * ## The defect these lock down
 *
 * The shipped distribution did not carry its model and the lookup was relative to the working
 * directory. The same document therefore produced **RED from the repository and GREY from the
 * unpacked console**, and with `--demo` it produced **AMBER** while printing
 * `model=OFFSET, margin=0.000` — an argmax over a row of zero weights, in the shape of a
 * measurement. Three separate ways of being wrong, all of them silent, all of them on the
 * artefact that was being submitted for review.
 *
 * So the assertions here are about *text an operator reads*, not about exit codes. An exit
 * code of 0 with a different answer inside is precisely what was shipped.
 */
class ConsoleModelProvenanceTest {

    /** A locator that can find nothing at all: no environment, no installation, no checkout. */
    private fun emptyLocator(workingDir: Path): ModelLocator =
        ModelLocator(env = emptyMap(), installRoot = null, workingDir = workingDir)

    /** A locator pointed at an unpacked installation that carries the given model. */
    private fun installingLocator(workingDir: Path, installRoot: Path): ModelLocator {
        installRoot.resolve("bin").createDirectories()
        installRoot.resolve("lib").createDirectories()
        installRoot.resolve("lib/app-desktop.jar").writeText("not really a jar")
        return ModelLocator(env = emptyMap(), installRoot = installRoot, workingDir = workingDir)
    }

    private fun withDirs(block: (work: Path, install: Path) -> Unit) {
        val dir = createTempDirectory("kasoti-model")
        try {
            block(dir.resolve("work"), dir.resolve("install"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -------------------------------------------------- the missing-model path

    /**
     * A console with no model must say so *before* the verdict, not in one warning line among
     * thirty. An operator scanning a RED at a counter reads the verdict block and the banner;
     * they do not read the warnings section looking for the reason a layer is thin.
     */
    @Test
    fun `a missing model is announced loudly before any verdict`() = withDirs { work, install ->
        // An installation with no model in it: the realistic mis-provisioning case, and the
        // one where the full search list — including the packaged paths — is worth printing.
        withFixture(installingLocator(work, install)) { f ->
            val image = f.dir.resolve("doc.png")
            Fixture.syntheticDocument(image)
            val fields = f.dir.resolve("fields.json")
            Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))

            f.run(
                "screen", "--image", image.toString(), "--fields", fields.toString(),
                "--today", "2026-09-29", "--fusion", "local",
            )

            val text = f.text
            assertContains(text, "MACRO MODEL NOT FOUND")
            assertContains(text, "will NOT run on this screening")
            // Every place the console looked, each with a reason. "It did not find it" is not
            // an answer; "here are the four paths" is.
            assertContains(text, ModelLocator.PACKAGED_MODEL_DIR)
            assertContains(text, SvmModelFile.SYNTHETIC_PATH)
            assertContains(text, ConsolePolicy.SVM_MODEL_ENV)
            // And the announcement comes before the verdict, not after.
            assertTrue(
                text.indexOf("MACRO MODEL NOT FOUND") < text.indexOf("VERDICT :"),
                "the missing model was announced after the verdict was printed",
            )
        }
    }

    /** The layer row must say UNAVAILABLE, and must not print a reading it never made. */
    @Test
    fun `a missing model makes the macro layer UNAVAILABLE and prints no process label`() = withDirs { work, _ ->
        withFixture(emptyLocator(work)) { f ->
            val image = f.dir.resolve("doc.png")
            Fixture.syntheticDocument(image)
            val fields = f.dir.resolve("fields.json")
            Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))

            f.run(
                "screen", "--image", image.toString(), "--fields", fields.toString(),
                "--today", "2026-09-29", "--fusion", "local",
            )

            assertContains(f.text, "UNAVAILABLE")
            assertFalse(
                "model=" in f.text,
                "a model label was printed with no model provisioned: ${f.text.lines().first { it.contains("macro") }}",
            )
            assertContains(f.text, ConsolePolicy.SVM_MODEL_ENV)
        }
    }

    /**
     * The `--demo` substitution, adversarially.
     *
     * `--demo` used to bind the untrained stub whenever no file was found, *without saying
     * so*, and the layer table then read `RAN photo=UNKNOWN(margin=0.000, model=OFFSET)`. A
     * `RAN` row with a model name in it is a result. The stub has no opinion about anything.
     */
    @Test
    fun `--demo with no model on disk never reports a process label`() = withDirs { work, _ ->
        withFixture(emptyLocator(work)) { f ->
            val image = f.dir.resolve("doc.png")
            Fixture.syntheticDocument(image)
            val fields = f.dir.resolve("fields.json")
            Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))

            f.run(
                "screen", "--image", image.toString(), "--fields", fields.toString(),
                "--today", "2026-09-29", "--fusion", "local", "--demo",
            )

            assertFalse(
                "model=OFFSET" in f.text || "model=" in f.text,
                "the untrained stub produced a process label: ${f.text.lines().filter { it.contains("macro") }}",
            )
            assertContains(f.text, "UNTRAINED-STUB")
            assertContains(f.text, "UNAVAILABLE")
            assertContains(f.text, "DEMO RUN")
        }
    }

    /** An untrained model passed *explicitly* is refused without `--demo`, as it always was. */
    @Test
    fun `an explicitly named untrained stub is still refused outside demo mode`() = withDirs { work, _ ->
        withFixture(emptyLocator(work)) { f ->
            val image = f.dir.resolve("doc.png")
            Fixture.syntheticDocument(image)
            val stub = f.dir.resolve("stub.json")
            stub.writeText(SvmModelFileFixture.untrainedStubJson())

            val failure = kotlin.runCatching {
                f.run("screen", "--image", image.toString(), "--today", "2026-09-29", "--svm", stub.toString())
            }.exceptionOrNull()
            assertContains(failure?.message.orEmpty(), "demo stub")
        }
    }

    // ------------------------------------------------------- the installed path

    /**
     * A provisioned console names the file it read and where it came from.
     *
     * This is the case the shipped archive gets wrong if the packaging regresses: the model
     * ships, the locator finds it, and the operator can see that it was found rather than
     * taking it on trust.
     */
    @Test
    fun `a model inside the installation is found and its origin is printed`() = withDirs { work, install ->
        val packaged = install.resolve(ModelLocator.PACKAGED_MODEL_DIR).resolve(SvmModelFile.DEFAULT_FILE_NAME)
        packaged.parent.createDirectories()
        packaged.writeText(SvmModelFileFixture.confidentAgreeingModelJson())

        withFixture(installingLocator(work, install)) { f ->
            val image = f.dir.resolve("doc.png")
            Fixture.syntheticDocument(image)
            val fields = f.dir.resolve("fields.json")
            Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))

            f.run(
                "screen", "--image", image.toString(), "--fields", fields.toString(),
                "--today", "2026-09-29", "--fusion", "local",
            )

            assertContains(f.text, "shipped inside this installation")
            assertContains(f.text, packaged.fileName.toString())
            assertContains(f.text, "macro model:")
            assertContains(f.text, "RAN")
            assertFalse("MACRO MODEL NOT FOUND" in f.text)
        }
    }

    /**
     * The regression that matters most, as a unit test.
     *
     * Same document, same sidecar, two working directories: one under an installation with a
     * model, one with nothing at all. Before the fix the second silently produced a different
     * verdict. Now it must be *detectably* different — the layer says so — rather than
     * differing by an amount nobody notices.
     */
    @Test
    fun `the working directory does not decide whether the macro layer runs`() = withDirs { work, install ->
        val packaged = install.resolve(ModelLocator.PACKAGED_MODEL_DIR).resolve(SvmModelFile.SYNTHETIC_FILE_NAME)
        packaged.parent.createDirectories()
        packaged.writeText(SvmModelFileFixture.confidentAgreeingModelJson())

        fun layerState(locator: ModelLocator): String {
            var row = ""
            withFixture(locator) { f ->
                val image = f.dir.resolve("doc.png")
                Fixture.syntheticDocument(image)
                val fields = f.dir.resolve("fields.json")
                Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))
                f.run(
                    "screen", "--image", image.toString(), "--fields", fields.toString(),
                    "--today", "2026-09-29", "--fusion", "local",
                )
                row = f.text.lines().first { it.trim().startsWith("macro") }
            }
            return row
        }

        val installed = layerState(installingLocator(work, install))
        val bare = layerState(emptyLocator(work))
        assertContains(installed, "RAN")
        assertContains(bare, "UNAVAILABLE")
        assertNotEquals(installed, bare)
    }

    /** `help` is the first thing anyone types. It has to answer "did it find its model". */
    @Test
    fun `help reports whether a model was found`() = withDirs { work, install ->
        val packaged = install.resolve(ModelLocator.PACKAGED_MODEL_DIR).resolve(SvmModelFile.SYNTHETIC_FILE_NAME)
        packaged.parent.createDirectories()
        packaged.writeText(SvmModelFileFixture.confidentAgreeingModelJson())

        withFixture(installingLocator(work, install)) { f ->
            f.console.help()
            assertContains(f.text, "macro model:")
            assertContains(f.text, "shipped inside this installation")
            assertFalse("MACRO MODEL NOT FOUND" in f.text)
        }
        withFixture(emptyLocator(work)) { f ->
            f.console.help()
            assertContains(f.text, "MACRO MODEL NOT FOUND")
        }
    }

    @Test
    fun `help names the console home and why`() = withFixture { f ->
        f.console.help()
        assertContains(f.text, "default home:")
        assertContains(f.text, f.home.root.toString())
    }
}

/**
 * The shipped README is generated by `build.gradle.kts`, which cannot reference Kotlin
 * constants, so the names it prints are written out by hand there.
 *
 * That is a drift seam with teeth: renaming `KASOTI_SVM_MODEL` in [ConsolePolicy] would leave
 * a shipped README instructing operators to set a variable the console does not read, and
 * nothing would fail. This asserts the two stay in step.
 */
class ShippedReadmeDriftTest {

    private val buildScript: String = Path.of("build.gradle.kts")
        .takeIf { Files.isRegularFile(it) }
        ?.let { Files.readString(it) }
        ?: error(
            // The test task's working directory is the module directory, so a bare name is the
            // module's own script — the root also has one, and reading the wrong file would
            // make this guard pass while checking nothing.
            "app-desktop/build.gradle.kts not found from ${Path.of("").toAbsolutePath()}; " +
                "the README drift guard cannot run",
        )

    /**
     * Whole-token containment.
     *
     * `assertContains` is a *substring* test, so `KASOTI_SVM_MODEL_TYPO` satisfies a check for
     * `KASOTI_SVM_MODEL` — which is exactly the drift this guard exists to catch, and it
     * sailed through while the build script said the typo'd name. An env var is a token, so
     * it is matched as one.
     */
    private fun mentions(text: String, token: String): Boolean =
        Regex("""(?<![\w-])${Regex.escape(token)}(?![\w-])""").containsMatchIn(text)

    @Test
    fun `every environment variable the README tells an operator to set is one the console reads`() {
        for (envVar in listOf(
            ConsolePolicy.SVM_MODEL_ENV,
            ConsolePolicy.CONSOLE_HOME_ENV,
            ConsolePolicy.SUPERVISOR_PIN_ENV,
        )) {
            assertTrue(
                mentions(buildScript, envVar),
                "the shipped README does not name $envVar, so it cannot tell an operator to set it",
            )
        }
    }

    @Test
    fun `a near-miss spelling of an environment variable is not accepted`() {
        // The guard's own adversarial case: if this were a substring test it would pass.
        assertFalse(mentions("KASOTI_SVM_MODEL_TYPO=1", ConsolePolicy.SVM_MODEL_ENV))
        assertTrue(mentions("export KASOTI_SVM_MODEL=/m.json", ConsolePolicy.SVM_MODEL_ENV))
    }

    @Test
    fun `the README points at the model file the console actually looks for`() {
        // `SvmModelFileNames` in the build script is the copy the README is generated from,
        // so it is asserted on directly rather than by loose substring presence.
        assertTrue(
            mentions(buildScript, "const val DEFAULT = \"" + SvmModelFile.DEFAULT_FILE_NAME + "\""),
            "the README is generated from a model file name that is not ${SvmModelFile.DEFAULT_FILE_NAME}",
        )
        assertTrue(
            mentions(buildScript, "const val SYNTHETIC = \"" + SvmModelFile.SYNTHETIC_FILE_NAME + "\""),
            "the README is generated from a model file name that is not ${SvmModelFile.SYNTHETIC_FILE_NAME}",
        )
    }

    @Test
    fun `the directory the model ships in is the one the locator reads`() {
        val locatorSource = Files.readString(
            Path.of("src/main/kotlin/dev/kasoti/desktop/screen/ModelLocator.kt"),
        )
        assertTrue(
            mentions(locatorSource, "PACKAGED_MODEL_DIR = \"$PACKAGED_MODEL_DIR_IN_BUILD\""),
            "ModelLocator.PACKAGED_MODEL_DIR and build.gradle.kts disagree about where the model " +
                "ships, so the archive would carry it somewhere nothing looks",
        )
    }

    private companion object {
        const val PACKAGED_MODEL_DIR_IN_BUILD = "share/kasoti/models"
    }
}

/**
 * Where the console keeps its state.
 *
 * The home used to be the bare relative path `.kasoti-console`, so it resolved against
 * whatever directory the operator was standing in. Two consequences, both reproduced: the same
 * `wipe` deleted a different store depending on where it was typed, and an unpacked
 * distribution came to contain a `.kasoti-console` directory because it had been run from
 * inside its own install directory.
 */
class ConsoleHomeTest {

    @Test
    fun `the environment variable wins over everything`() {
        val home = ConsoleHome.default(
            env = mapOf(ConsolePolicy.CONSOLE_HOME_ENV to "/var/lib/kasoti"),
            userHome = Path.of("/home/officer"),
            workingDir = Path.of("/tmp"),
        )
        assertEquals(Path.of("/var/lib/kasoti"), home.root)
        assertContains(home.source, ConsolePolicy.CONSOLE_HOME_ENV)
    }

    @Test
    fun `the home is under the user home directory and not the working directory`() {
        val home = ConsoleHome.default(
            env = emptyMap(),
            userHome = Path.of("/home/officer"),
            workingDir = Path.of("/tmp/somewhere-the-operator-happened-to-be"),
        )
        assertEquals(Path.of("/home/officer").resolve(ConsoleHome.DIR_NAME), home.root)
        assertContains(home.source, "HOME")
    }

    /**
     * The adversarial case: two different working directories, one home.
     *
     * A `wipe` is defined as "delete this one directory". If the home follows the shell, that
     * sentence is not true and the audit chain forks, so this asserts the property rather
     * than the value.
     */
    @Test
    fun `the home does not move with the working directory`() {
        val first = ConsoleHome.default(emptyMap(), Path.of("/home/officer"), Path.of("/tmp/a"))
        val second = ConsoleHome.default(emptyMap(), Path.of("/home/officer"), Path.of("/tmp/b"))
        assertEquals(first.root, second.root)
    }

    /**
     * With no home directory at all, the fallback is the working directory — and it says so.
     *
     * Falling back silently is how the original bug happened, so the [ConsoleHome.source] a
     * reader can print has to distinguish this from a provisioned location.
     */
    @Test
    fun `no home directory falls back to the working directory and says so`() {
        val home = ConsoleHome.default(emptyMap(), userHome = null, workingDir = Path.of("/tmp"))
        assertEquals(Path.of("/tmp").resolve(ConsoleHome.DIR_NAME), home.root)
        assertContains(home.source, "no home directory found")
    }

    @Test
    fun `a blank environment variable is ignored rather than becoming an empty path`() {
        val home = ConsoleHome.default(
            env = mapOf(ConsolePolicy.CONSOLE_HOME_ENV to "  "),
            userHome = Path.of("/home/officer"),
            workingDir = Path.of("/tmp"),
        )
        assertEquals(Path.of("/home/officer").resolve(ConsoleHome.DIR_NAME), home.root)
    }

    @Test
    fun `the default name is unchanged so a deployment's scripts keep working`() {
        assertEquals(".kasoti-console", ConsoleHome.DEFAULT_DIR)
    }
}
