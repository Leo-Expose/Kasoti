package dev.kasoti.desktop.screen

import dev.kasoti.desktop.ConsolePolicy
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a macro (print-process) model can come from, in the order the console may use it.
 *
 * The enum exists so the *reason* a model was used can be printed and asserted on. A console
 * that loaded a model from somewhere unnamed leaves the operator unable to tell a
 * provisioned console from a checkout that happened to be open.
 */
enum class ModelOrigin(val how: String) {
    EXPLICIT_FLAG("named by --svm"),
    ENVIRONMENT("named by ${ConsolePolicy.SVM_MODEL_ENV}"),
    PACKAGED("shipped inside this installation"),
    REPOSITORY("found in a source checkout"),
}

/** One place the console looked. */
data class ModelCandidate(val path: Path, val origin: ModelOrigin)

/**
 * The outcome of the search. There is no third case: either a file was found, or every place
 * that could have held one is named back to the operator.
 */
sealed interface ModelLocation {

    data class Found(val path: Path, val origin: ModelOrigin) : ModelLocation

    /**
     * Nothing was found.
     *
     * [searched] is the *whole* list, not just the near misses, because the one thing an
     * operator needs from a failure like this is where to put the file. [notes] carries the
     * reasons a candidate could not even be formed — chiefly "this is not an installation, so
     * there is no packaged model to look for", which is a different problem from "the
     * installation is missing its model" and looks identical from the outside.
     */
    data class Missing(val searched: List<ModelCandidate>, val notes: List<String>) : ModelLocation
}

/**
 * Finds the macro model without depending on the current working directory.
 *
 * ## Why this exists
 *
 * The console's model lookup used to be two bare relative paths (`eval/models/…`) resolved
 * against `Path.of(relative)`, i.e. against whatever directory the process happened to start
 * in. Inside a checkout that is a convenience. In an unpacked distribution there is no `eval/`
 * directory at all, so the lookup found nothing, the macro layer reported `UNAVAILABLE`, and
 * the cascade produced a **different verdict on the same document** than the repository run
 * produced — RED from the checkout, GREY from the shipped console, with nothing but a warning
 * line to say so. A packaged console that quietly changes a verdict is worse than one that
 * refuses to start, so the model is now searched for along anchors that do not move:
 *
 *  1. `--svm PATH` — the operator named a file. A path that does not exist stays a hard error.
 *  2. `${ConsolePolicy.SVM_MODEL_ENV}` — the deployment provisioned one.
 *  3. The **packaged** model, resolved against the *installation root* derived from this class's
 *     own code source, not from the CWD. This is the case that makes a provisioned console
 *     work from any directory.
 *  4. The repository-relative paths, against an explicit working directory. Last, because they
 *     only mean anything in a checkout.
 *
 * ## Why the install root comes from the code source
 *
 * The obvious alternatives both fail in a way that is invisible in testing. `APP_HOME` from the
 * generated start script only exists when the operator used the start script — `java -jar`, a
 * systemd unit calling the class directly, and `gradlew run` all have no `APP_HOME`. Reading
 * the CWD is the bug being fixed. The code source is on the classpath in every one of those
 * cases, and it moves with the jars, so it is the one anchor that cannot be wrong by accident.
 */
class ModelLocator(
    private val env: Map<String, String> = System.getenv(),
    private val installRoot: Path? = defaultInstallRoot(),
    private val workingDir: Path = Path.of("").toAbsolutePath(),
) {

    /**
     * Every place a model could be, most-authoritative first.
     *
     * Ordered, not filtered: [ModelLocation.Missing] prints this list, so a candidate that was
     * skipped for a reason is more useful as a named line than as an absence.
     */
    fun candidates(explicit: String? = null): List<ModelCandidate> = buildList {
        explicit?.takeIf { it.isNotBlank() }
            ?.let { add(ModelCandidate(Path.of(it), ModelOrigin.EXPLICIT_FLAG)) }
        env[ConsolePolicy.SVM_MODEL_ENV]?.takeIf { it.isNotBlank() }
            ?.let { add(ModelCandidate(Path.of(it), ModelOrigin.ENVIRONMENT)) }
        val root = installRoot
        if (root != null) {
            add(ModelCandidate(packaged(root, SvmModelFile.DEFAULT_FILE_NAME), ModelOrigin.PACKAGED))
            add(ModelCandidate(packaged(root, SvmModelFile.SYNTHETIC_FILE_NAME), ModelOrigin.PACKAGED))
        }
        add(ModelCandidate(workingDir.resolve(SvmModelFile.DEFAULT_PATH), ModelOrigin.REPOSITORY))
        add(ModelCandidate(workingDir.resolve(SvmModelFile.SYNTHETIC_PATH), ModelOrigin.REPOSITORY))
    }

    /** Why a candidate could not be formed, for the failure message. */
    fun notes(): List<String> = if (installRoot == null) {
        listOf(
            "not running from an unpacked installation, so there is no packaged model to " +
                "look for (this is normal under `gradlew :app-desktop:run`)",
        )
    } else {
        emptyList()
    }

    fun locate(explicit: String? = null): ModelLocation {
        val searched = candidates(explicit)
        val hit = searched.firstOrNull { Files.isRegularFile(it.path) }
        return if (hit == null) {
            ModelLocation.Missing(searched, notes())
        } else {
            ModelLocation.Found(hit.path, hit.origin)
        }
    }

    private fun packaged(root: Path, fileName: String): Path = root.resolve(PACKAGED_MODEL_DIR).resolve(fileName)

    companion object {

        /**
         * Where `shipConsole` puts the model inside the archive.
         *
         * Deliberately not `lib/`: `lib/` is a classpath directory, and a data file that looks
         * like a dependency is one somebody will delete during a dependency cleanup.
         */
        const val PACKAGED_MODEL_DIR = "share/kasoti/models"

        fun defaultInstallRoot(): Path? = installRootOf(codeSourceOf())

        internal fun codeSourceOf(): Path? = runCatching {
            ModelLocator::class.java.protectionDomain?.codeSource?.location?.toURI()
        }.getOrNull()?.let { Path.of(it) }

        /**
         * The installation root containing [codeSource], or `null` if it is not one.
         *
         * Derived from the code source's *shape*, never by searching upwards. An earlier
         * version walked ancestors looking for `bin` and `lib`, bounded to a few hops, and an
         * adversarial test caught it doing exactly what a bounded walk must never do: on a
         * Linux host the filesystem root has `/bin` and `/lib` and a jar inside it, so a
         * four-hop walk from a temp directory arrived at `/` and reported the root of the disk
         * as "shipped inside this installation". Every rule below is checked against the
         * code source itself, so there is nothing to widen:
         *
         *  - `<root>/lib/<jar>` — the layout [ModelLocator.PACKAGED_MODEL_DIR] and every
         *    distribution this project produces. `lib` must be *named* `lib`, which is what
         *    makes "one directory up from the classpath directory" unambiguous.
         *  - `<root>/<jar>` with `<root>/share/` — a single-jar run next to its data.
         *
         * A classes directory (`build/classes/kotlin/main`, what `gradlew run` uses) is
         * deliberately `null`: that is a source layout, there is no packaged model in it, and
         * the search falls through to the checkout where the model actually lives.
         */
        internal fun installRootOf(codeSource: Path?): Path? =
            codeSource
                // A jar, not a classes directory.
                ?.takeIf { !Files.isDirectory(it) }
                ?.parent
                ?.let { parent ->
                    // Two layouts, one marker each. Written as a value rather than a ladder of
                    // early returns because the *precedence* between the rules is the real
                    // logic, and a ladder of `return`s is where that precedence goes to hide.
                    val inLibLayout = parent.fileName?.toString() == "lib"
                    val root = if (inLibLayout) parent.parent else parent
                    val marker = if (inLibLayout) "bin" else "share"
                    root?.takeIf { Files.isDirectory(it.resolve(marker)) }
                }
    }
}
