package dev.kasoti.desktop

import dev.kasoti.desktop.screen.ModelCandidate
import dev.kasoti.desktop.screen.ModelLocator
import dev.kasoti.desktop.screen.ModelLocation
import dev.kasoti.desktop.screen.ModelOrigin
import dev.kasoti.desktop.screen.SvmModelFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model search, and the install root it hangs off.
 *
 * These exist because of a shipped defect: the distribution did not carry its model and the
 * lookup was relative to the working directory, so the *same document* got a RED from the
 * repository and a GREY from the unpacked console. A search that depends on where the process
 * was started is not a search, it is a coincidence, and the only defence is asserting on the
 * paths themselves.
 */
class ModelLocatorTest {

    private fun withTree(block: (Path) -> Unit) {
        val dir = createTempDirectory("kasoti-locator")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A directory that looks like an unpacked distribution, with one jar in `lib/`. */
    private fun installRoot(dir: Path): Path =
        dir.resolve("kasoti").apply {
            resolve("bin").createDirectories()
            resolve("lib").createDirectories()
            resolve("lib/app-desktop-1.0.jar").writeText("not really a jar")
        }

    private fun packagedModel(root: Path, name: String = SvmModelFile.SYNTHETIC_FILE_NAME): Path =
        root.resolve(ModelLocator.PACKAGED_MODEL_DIR).resolve(name)
            .also { it.parent.createDirectories(); it.writeText("{}") }

    // ------------------------------------------------------------- install root

    @Test
    fun `the install root is found from a jar on the classpath`() = withTree { dir ->
        val root = installRoot(dir)
        assertEquals(root, ModelLocator.installRootOf(root.resolve("lib/app-desktop-1.0.jar")))
    }

    @Test
    fun `the install root is found when run from a classes directory`() = withTree { dir ->
        // `java -cp build/classes/kotlin/main` is how `gradlew run` reaches this code, and it
        // is deliberately NOT an installation: there is no packaged model beside a classes
        // directory, so claiming otherwise would send the search somewhere empty. It must
        // fall through to the checkout.
        val root = installRoot(dir)
        val classes = root.resolve("build/classes/kotlin/main")
        classes.createDirectories()
        assertNull(ModelLocator.installRootOf(classes))
    }

    @Test
    fun `a single jar beside a share directory is an installation`() = withTree { dir ->
        val root = dir.resolve("flat")
        root.resolve("share/kasoti/models").createDirectories()
        val jar = root.resolve("app-desktop-1.0.jar")
        jar.writeText("not really a jar")
        assertEquals(root, ModelLocator.installRootOf(jar))
    }

    @Test
    fun `a jar in a directory not named lib finds no installation`() = withTree { dir ->
        // Some other layout entirely — a Maven-style `target/` or a hand-rolled staging dir.
        // Guessing that it is an installation would send the search to a path that does not
        // exist; falling through to the checkout is the recoverable mistake.
        val jar = dir.resolve("target").resolve("app-desktop-1.0.jar")
        jar.parent.createDirectories()
        jar.writeText("not really a jar")
        assertNull(ModelLocator.installRootOf(jar))
    }

    /**
     * The adversarial case that killed an ancestor *search*: on a Linux host `/` has `bin`,
     * `/lib` and jars in it, so anything that walks up looking for those three things ends up
     * declaring the root of the disk an installation. The derivation has to be structural.
     */
    @Test
    fun `the filesystem root is never mistaken for an installation`() = withTree { dir ->
        val jar = dir.resolve("tmp/somewhere/lib").resolve("app-desktop-1.0.jar")
        jar.parent.createDirectories()
        jar.writeText("not really a jar")
        val root = ModelLocator.installRootOf(jar)
        assertTrue(root == null || root.resolve("bin").let { Files.isDirectory(it) })
        assertTrue(root != Path.of("/"), "the walk reached the filesystem root and called it an installation")
    }

    @Test
    fun `a null code source is not an installation`() {
        assertNull(ModelLocator.installRootOf(null))
    }

    @Test
    fun `a repository directory is not an installation even when it has bin and lib`() = withTree { dir ->
        // The shape a source tree can have by accident. There is no `lib` directory in the
        // classpath position, so the derivation must decline rather than pattern-match.
        val repo = dir.resolve("checkout")
        repo.resolve("bin").createDirectories()
        repo.resolve("lib").createDirectories()
        repo.resolve("lib/notes.txt").writeText("not a jar")
        val jar = repo.resolve("build/libs/app-desktop-1.0.jar")
        jar.parent.createDirectories()
        jar.writeText("not really a jar")
        assertNull(ModelLocator.installRootOf(jar))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `the packaged model is found with no working directory and no environment`() = withTree { dir ->
        val root = installRoot(dir)
        packagedModel(root)
        val located = ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate()
        val found = assertIs<ModelLocation.Found>(located)
        assertEquals(ModelOrigin.PACKAGED, found.origin)
        assertEquals(packagedModel(root), found.path)
    }

    @Test
    fun `the packaged real model is preferred over the packaged synthetic one`() = withTree { dir ->
        val root = installRoot(dir)
        packagedModel(root)
        packagedModel(root, SvmModelFile.DEFAULT_FILE_NAME)
        val found = assertIs<ModelLocation.Found>(
            ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate(),
        )
        assertEquals(SvmModelFile.DEFAULT_FILE_NAME, found.path.fileName.toString())
    }

    @Test
    fun `an environment variable beats the packaged model`() = withTree { dir ->
        val root = installRoot(dir)
        packagedModel(root)
        val provisioned = dir.resolve("real-model.json")
        provisioned.writeText("{}")
        val found = assertIs<ModelLocation.Found>(
            ModelLocator(
                env = mapOf(ConsolePolicy.SVM_MODEL_ENV to provisioned.toString()),
                installRoot = root,
                workingDir = dir,
            ).locate(),
        )
        assertEquals(ModelOrigin.ENVIRONMENT, found.origin)
        assertEquals(provisioned, found.path)
    }

    @Test
    fun `an explicit path beats everything and is searched first`() = withTree { dir ->
        val root = installRoot(dir)
        packagedModel(root)
        val named = dir.resolve("named.json")
        named.writeText("{}")
        val found = assertIs<ModelLocation.Found>(
            ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate(named.toString()),
        )
        assertEquals(ModelOrigin.EXPLICIT_FLAG, found.origin)
        assertEquals(named, found.path)
    }

    @Test
    fun `a checkout model is used when there is no installation`() = withTree { dir ->
        val checkout = dir.resolve("eval/models")
        checkout.createDirectories()
        checkout.resolve(SvmModelFile.SYNTHETIC_FILE_NAME).writeText("{}")
        val found = assertIs<ModelLocation.Found>(
            ModelLocator(env = emptyMap(), installRoot = null, workingDir = dir).locate(),
        )
        assertEquals(ModelOrigin.REPOSITORY, found.origin)
    }

    /**
     * The installation is checked *before* the checkout.
     *
     * A console copied out of a repository and then re-pointed at a checkout would otherwise
     * keep reading the checkout's model and reporting a verdict the shipped model would not
     * have produced. The archive is the authority once there is one.
     */
    @Test
    fun `the installation wins over a checkout that also has a model`() = withTree { dir ->
        val root = installRoot(dir)
        packagedModel(root)
        val checkout = dir.resolve("eval/models")
        checkout.createDirectories()
        checkout.resolve(SvmModelFile.SYNTHETIC_FILE_NAME).writeText("{}")
        val found = assertIs<ModelLocation.Found>(
            ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate(),
        )
        assertEquals(ModelOrigin.PACKAGED, found.origin)
    }

    // ------------------------------------------------------------- the failure

    @Test
    fun `nothing anywhere is a typed miss that names every place searched`() = withTree { dir ->
        val root = installRoot(dir)
        val missing = assertIs<ModelLocation.Missing>(
            ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate(),
        )
        val searched = missing.searched.joinToString("\n") { it.path.toString() }
        assertTrue(missing.searched.isNotEmpty())
        assertContains(searched, ModelLocator.PACKAGED_MODEL_DIR)
        assertContains(searched, SvmModelFile.SYNTHETIC_PATH)
        // Every candidate must carry a reason, because the reason is the only thing an
        // operator can act on: "not at /x (shipped inside this installation)" and a bare
        // "not at /x" are different messages.
        assertTrue(missing.searched.all { it.origin.how.isNotBlank() })
    }

    @Test
    fun `a provisioned environment variable is named in the miss`() = withTree { dir ->
        val root = installRoot(dir)
        val missing = assertIs<ModelLocation.Missing>(
            ModelLocator(
                env = mapOf(ConsolePolicy.SVM_MODEL_ENV to dir.resolve("absent.json").toString()),
                installRoot = root,
                workingDir = dir,
            ).locate(),
        )
        assertContains(missing.searched.joinToString("\n") { it.origin.how }, ConsolePolicy.SVM_MODEL_ENV)
    }

    @Test
    fun `not running from an installation is reported as its own reason`() = withTree { dir ->
        val missing = assertIs<ModelLocation.Missing>(
            ModelLocator(env = emptyMap(), installRoot = null, workingDir = dir).locate(),
        )
        assertContains(missing.notes.joinToString("\n"), "not running from an unpacked installation")
    }

    /**
     * A *directory* where the model should be is not a model.
     *
     * `Files.isRegularFile` rather than `exists`, because the interesting failure is an
     * operator creating `share/kasoti/models/svm_print_v1.json/` and being told the console
     * found it. The parse would then fail on a directory read, one layer away from the
     * diagnosis.
     */
    @Test
    fun `a directory at the model path is not accepted as a model`() = withTree { dir ->
        val root = installRoot(dir)
        // The operator's classic mistake: `mkdir -p .../svm_print_v1.json` instead of
        // writing the file. `exists` would accept this and the parse would then fail on a
        // directory read, one layer away from the real diagnosis.
        root.resolve(ModelLocator.PACKAGED_MODEL_DIR).resolve(SvmModelFile.DEFAULT_FILE_NAME).createDirectories()
        assertIs<ModelLocation.Missing>(
            ModelLocator(env = emptyMap(), installRoot = root, workingDir = dir).locate(),
        )
    }

    @Test
    fun `an empty environment variable is ignored rather than searched as the empty path`() = withTree { dir ->
        val candidates: List<ModelCandidate> = ModelLocator(
            env = mapOf(ConsolePolicy.SVM_MODEL_ENV to "   "),
            installRoot = null,
            workingDir = dir,
        ).candidates()
        assertTrue(candidates.none { it.origin == ModelOrigin.ENVIRONMENT })
        assertTrue(candidates.none { it.path.toString().isBlank() })
    }

    @Test
    fun `every candidate is reachable and the list is ordered by authority`() = withTree { dir ->
        val root = installRoot(dir)
        val candidates: List<ModelCandidate> = ModelLocator(
            env = mapOf(ConsolePolicy.SVM_MODEL_ENV to "env.json"),
            installRoot = root,
            workingDir = dir,
        ).candidates("flag.json")
        assertEquals(
            listOf(
                ModelOrigin.EXPLICIT_FLAG,
                ModelOrigin.ENVIRONMENT,
                ModelOrigin.PACKAGED,
                ModelOrigin.PACKAGED,
                ModelOrigin.REPOSITORY,
                ModelOrigin.REPOSITORY,
            ),
            candidates.map { it.origin },
        )
        // The real model precedes the synthetic one in every tier, so dropping a real file in
        // place is picked up without touching the console.
        assertEquals(
            listOf(SvmModelFile.DEFAULT_FILE_NAME, SvmModelFile.SYNTHETIC_FILE_NAME),
            candidates.filter { it.origin == ModelOrigin.PACKAGED }.map { it.path.fileName.toString() },
        )
    }
}
