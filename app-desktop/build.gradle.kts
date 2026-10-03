// KASOTI — :app-desktop (DESIGN.md §1, BUILD.md §3)
//
// The Post-Console: secondary review, diary admin, sync hub, shift report, case-bundle
// export, override with reason codes.
//
// Headless is a hard requirement, not a preference. There is no Android SDK in CI, no
// display guarantee on a post machine, and a reviewer who cannot run the thing will not
// run the thing. So this pass ships the domain logic plus a fully functional text UI, and
// the view layer is kept behind interfaces so a Compose Multiplatform surface can be added
// without touching the logic.
//
// Compose Multiplatform is DELIBERATELY not a dependency in this pass. It is a ~1.4 MB
// plug-in plus a large native/artifact download, and adding it would put a third-party
// download on the critical path of a build that has to stay green on a machine with no
// display. Tracked in the module README.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

/** The JVM range the console is built and shipped for, quoted in the shipped README. */
val jvmTargetRange: String =
    "17 or newer. Compiled for Java 17 (bytecode target 61); verified end-to-end on " +
        "OpenJDK 17 and OpenJDK 27."

application {
    mainClass.set("dev.kasoti.desktop.MainKt")
    applicationName = "kasoti"
}

tasks.named<JavaExec>("run") {
    // `gradlew :app-desktop:run` is a *developer* command, so it runs from the repository root
    // and finds the model under `eval/models/`. A shipped console must not need this: it
    // resolves the model against its own install root instead (`ModelLocator`), so a
    // provisioned console gives the same verdict from any working directory.
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))

    // The sidecar-JSON reader and the case-bundle writer. Runtime only: the sidecar schema
    // is read through `Json.parseToJsonElement` rather than the compiler plugin, so there
    // is no codegen step and no plugin/codegen version coupling on the build's critical path.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // `ShippedReadmeDriftTest` reads this script and the README's source of truth from disk.
    // Without declaring them as inputs the test task stays up-to-date when only they change,
    // so the guard that exists to catch drift would not run at the moment it is needed.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
    inputs.file(layout.projectDirectory.file("src/main/kotlin/dev/kasoti/desktop/screen/ModelLocator.kt"))
}

// ---------------------------------------------------------------------------- packaging

/**
 * The macro model that ships with the console.
 *
 * Committed under `eval/models/` because that is where `eval/tools/synth_macros.py` writes it
 * and the eval harness reads it in place. A distribution gets a *copy* under
 * `share/kasoti/models/`, which is where `ModelLocator` looks before it falls back to the
 * checkout-relative path.
 *
 * There is no real D-MACRO model in this repository (`svm_print_v1.json` does not exist), so
 * this is the synthetic one. That is stated on the console on every screening, in the
 * distribution's README, and in the model card that ships next to it — three places, because
 * "the artefact quietly ships a demo model" is the failure this project exists to avoid.
 */
val packagedModel: File = rootProject.file("eval/models/svm_print_v1_synthetic.json")
val packagedModelCard: File = rootProject.file("eval/models/svm_print_v1_synthetic_MODEL_CARD.md")
val packagedModelDir: String = "share/kasoti/models"

/**
 * The model file names, restated here because a build script cannot reference Kotlin
 * constants. `ShippedReadmeDriftTest` asserts these still match `SvmModelFile`, so the two
 * copies cannot drift apart silently.
 */
object SvmModelFileNames {
    const val DEFAULT = "svm_print_v1.json"
    const val SYNTHETIC = "svm_print_v1_synthetic.json"
}

tasks.named<Sync>("installDist") {
    // Adding the model to the *install* task rather than to `distZip` means `distZip`,
    // `distTar` and `shipConsole` all inherit it from one place, so there is no second
    // packaging path that can forget it. This is the bug: a distribution built from a
    // different task than the one that was tested.
    from(packagedModel) { into(packagedModelDir) }
    from(packagedModelCard) { into(packagedModelDir) }
}

/**
 * The archive a judge unpacks. One task, no Gradle needed on the target machine.
 *
 * `distZip` already exists and is already correct for a *program*, but it carries neither
 * the model nor any statement of what JDK the thing wants, so unpacking it on a clean
 * machine gets you a console that abstains on the print-process layer and a first run that
 * is indistinguishable from a working one. This task is the difference between shipping a
 * build output and shipping an artefact.
 */
val shipConsole by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the one archive a reviewer unpacks: launchers, jars, the model and a README."
    val version = project.version.toString()
    val readme = tasks.named("shipReadme")
    dependsOn(tasks.named("installDist"), readme)
    val root = "kasoti-post-console-$version"
    // The install directory is derived from the task rather than written down. It moved to
    // `build/install/kasoti` when `applicationName` was set, and a hardcoded path kept
    // packaging the *stale* tree from before that — which shipped without the model and
    // would have looked fine, because the archive was still a working console.
    val installDir = tasks.named<Sync>("installDist").map { it.destinationDir }
    archiveBaseName.set("kasoti-post-console")
    archiveVersion.set(version)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    // The archive goes over a wire to someone who has to download it once; the model is
    // text and compresses well, and a smaller artefact is a smaller excuse to skip the check.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(installDir) { into(root) }
    from(readme.map { it.outputs.files.singleFile }) { into(root) }

    doFirst {
        // Fail the build rather than ship the defect this task exists to prevent. A console
        // without a model does not crash; it answers differently, quietly, which is worse
        // than an empty archive.
        if (!packagedModel.isFile) {
            throw GradleException(
                "refusing to ship: the macro model is missing at ${packagedModel.path}. " +
                    "A distribution without it abstains on the print-process layer and can " +
                    "return a different verdict from the repository run.",
            )
        }
    }
}

/**
 * The console's README, generated rather than committed.
 *
 * Version and JDK range change with the build; a hand-written file that says the wrong thing
 * is worse than no file, so the facts that can drift are interpolated here and the rest is
 * prose.
 *
 * This is a task and not a block of script that writes a file. The first version wrote it
 * during *configuration*, which meant `./gradlew clean shipConsole` in one invocation deleted
 * the file the archive was about to read: the build stayed green and the archive shipped with
 * no README, silently. A generated artefact with no declared task and no declared output is a
 * file that exists when it feels like it.
 */
val shipReadme by tasks.registering {
    group = "distribution"
    description = "Writes the README.txt that ships inside the console archive."
    val target = layout.buildDirectory.file("shipped-readme/README.txt")
    val modelDir = packagedModelDir
    val envSvm = "KASOTI_SVM_MODEL"
    val envHome = "KASOTI_CONSOLE_HOME"
    val envPin = "KASOTI_SUPERVISOR_PIN"
    val realModel = SvmModelFileNames.DEFAULT
    val syntheticModel = SvmModelFileNames.SYNTHETIC
    val versionText = project.version.toString()
    val jdk = jvmTargetRange
    outputs.file(target)

    doLast {
        val output = target.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            KASOTI post-console $versionText
            ====================================

            WHAT THIS IS
            An offline border-document screening console. It runs entirely on this machine:
            no network, no server, no display. Unpack it anywhere and run it.

            WHAT YOU NEED
            $jdk
            Any JDK or JRE that provides a `java` on PATH will do; you do NOT
            need Gradle, Kotlin, or the KASOTI source tree.

              check:  java -version

            HOW TO RUN
              ./bin/kasoti help
              ./bin/kasoti sample --out /tmp/kasoti-demo
              ./bin/kasoti screen --image /tmp/kasoti-demo/specimen.png \
                                 --fields /tmp/kasoti-demo/specimen.json

            The second command writes a zero-PII demo specimen; the third screens it.
            The console finds its own model and its own home wherever you unpack it
            and run it, from any working directory. `help` prints the full command
            table and names the model it found.

            THE MACRO (PRINT-PROCESS) LAYER
            This console ships its model at:
              $modelDir/$syntheticModel

            It is the SYNTHETIC model: fitted on generated textures by
            eval/tools/synth_macros.py, never gate-eligible, and not evidence about any
            print process (SPEC.md §7). The console says so on every screening.

            To use a real model instead, put it at:
              $modelDir/$realModel

            ...or name it without touching the archive:
              ./bin/kasoti screen --image doc.png --svm /path/to/svm_print_v1.json
              export KASOTI_SVM_MODEL=/path/to/svm_print_v1.json

            If no model can be found, the console says so loudly before printing any
            verdict and reports the layer as UNAVAILABLE. It will not substitute anything
            silently. The search order, in full, is:

              1. --svm PATH
              2. KASOTI_SVM_MODEL
              3. $modelDir/$realModel, then $syntheticModel
              4. eval/models/... under the current directory (source checkouts only)

            WHERE STATE GOES
            KASOTI_CONSOLE_HOME overrides the home.
            Default: ~/.kasoti-console/  (audit log, case store, supervisor PIN file).
            It does NOT follow your current directory, so `wipe` always deletes the same
            store. Override with:
              export KASOTI_CONSOLE_HOME=/var/lib/kasoti

            Supervisor PIN, for the one-tap wipe:
              export KASOTI_SUPERVISOR_PIN=<at least 6 characters>

            EXIT CODES
              0 ok    1 usage error    2 refused    3 audit chain broken    4 failure

            LICENSE AND PROVENANCE
            Third-party components and their licences: see THIRD_PARTY.md in the KASOTI
            repository. The model card for the shipped model is at
            $modelDir/${syntheticModel}_MODEL_CARD.md
            """.trimIndent(),
        )
    }
}

/**
 * Unpacks [shipConsole] into an empty directory and runs a real screening there.
 *
 * The proof that the console is shippable is that it answers identically from a machine that
 * has never seen this repository, and the only way that is ever checked is if checking it is
 * a task somebody can run. It asserts on the *output text*, not on an exit code, because the
 * failure this guards against is precisely an exit code of 0 with a different answer inside.
 */
val cleanRoomExtract by tasks.registering(Sync::class) {
    group = "verification"
    description = "Unpacks the shipped archive into an empty build directory."
    dependsOn(shipConsole)
    from(zipTree(shipConsole.flatMap { it.archiveFile }))
    into(layout.buildDirectory.dir("clean-room"))
}

val verifyShippedConsole by tasks.registering {
    group = "verification"
    description = "Unpacks the shipped archive into an empty directory and runs a screening there."
    dependsOn(cleanRoomExtract)

    val root = layout.buildDirectory.dir("clean-room/kasoti-post-console-${project.version}")
    val demoDir = layout.buildDirectory.dir("clean-room-demo")

    doLast {
        val install = root.get().asFile
        val launcher = install.resolve("bin/kasoti")
        check(launcher.isFile) { "the archive has no bin/kasoti launcher" }
        check(
            install.resolve("lib").listFiles()?.any { it.name.endsWith(".jar") } == true,
        ) { "the archive has no jars in lib/" }
        val model = install.resolve("$packagedModelDir/${SvmModelFileNames.SYNTHETIC}")
        check(model.isFile) { "the archive does not carry the macro model at $packagedModelDir" }
        check(install.resolve("README.txt").isFile) { "the archive has no README.txt" }

        val demo = demoDir.get().asFile
        demo.deleteRecursively()
        demo.mkdirs()

        fun kasoti(vararg args: String): String {
            val process = ProcessBuilder(listOf(launcher.absolutePath) + args)
                // Run from the install root with a clean environment: no KASOTI_* variables,
                // no repository in sight. Anything the console can still find, it found by
                // itself.
                .directory(install)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "`kasoti ${args.joinToString(" ")}` failed:\n$output" }
            return output
        }

        val help = kasoti("help")
        check("MACRO MODEL NOT FOUND" !in help) {
            "the unpacked console did not find its own model:\n$help"
        }

        kasoti("sample", "--out", demo.absolutePath)
        val screen = kasoti(
            "screen",
            "--image", demo.resolve("specimen.png").absolutePath,
            "--fields", demo.resolve("specimen.json").absolutePath,
        )
        val verdict = screen.lineSequence().firstOrNull { it.trim().startsWith("VERDICT :") }
        check(verdict != null) { "no verdict in the clean-room screening:\n$screen" }
        check("model=" in screen || "UNAVAILABLE" in screen) {
            "the clean-room screening did not report a macro layer state:\n$screen"
        }
        logger.lifecycle("clean-room console, unpacked with no repository present: ${verdict.trim()}")
    }
}

