package dev.kasoti.desktop.screen

import dev.kasoti.factory.LoadedSvmModel
import dev.kasoti.factory.ProcessClassifier
import dev.kasoti.factory.SvmModel
import dev.kasoti.factory.SvmModelFormatException
import dev.kasoti.factory.SvmModelReader
import java.nio.file.Files
import java.nio.file.Path

object SvmModelFile {

    /**
     * Reads `svm_print_v1.json` through `:core` (DESIGN.md §6).
     *
     * ## The parser moved, and this is now a delegate
     *
     * The console used to carry its own reader. It is now `dev.kasoti.factory.SvmModelReader`, in
     * `:core`, next to the Android path and the eval harness — because three readers of a weights
     * file is two too many, and the failure they share is invisible: a reader that reorders
     * nothing still returns a model, and a model over a permuted vector still scores a plausible
     * number for every patch. `:core` also *reorders* weight rows into `ProcessLabel` declaration
     * order, which this reader did not; a file whose `labels` were in a different order used to
     * load here and then be indexed as if it were in declaration order.
     *
     * ## What is still rejected here, and why not in `:core`
     *
     * The named-weight form (`{"peakFrequency": 3.1, ...}`) is still refused. Supporting it would
     * mean this class had to know the feature scaling `train_svm.py` used, and getting that wrong
     * would leave every threshold looking healthy while every prediction was nonsense — the one
     * failure mode nobody catches in review. The file carries `featureNames` so the flat order is
     * self-documenting instead.
     */
    fun load(path: Path): SvmModel = read { SvmModelReader.parse(Files.readString(path), path.toString()) }.model

    fun parse(text: String, origin: String = "<inline>"): SvmModel = read { SvmModelReader.parse(text, origin) }.model

    /**
     * Load and keep the provenance, which is what a screen has to be able to print.
     *
     * A model fitted on generated textures is fine for a demo and worthless as evidence, and the
     * difference has to reach the operator rather than being re-derived from a filename.
     */
    fun loadWithProvenance(path: Path): LoadedSvmModel =
        read { SvmModelReader.parse(Files.readString(path), path.toString()) }

    /**
     * A model may only be used when the operator has said so explicitly.
     *
     * The demo stub has no discriminative power at all, and a screen that printed
     * "OFFSET (margin 0.000)" would read as a real reading. Refusing it unless `--demo` is
     * present means an accidental run cannot put a meaningless label in front of anyone.
     */
    fun requireUsable(model: SvmModel, demoMode: Boolean) {
        if (!isDiscriminative(model) && !demoMode) {
            throw SvmModelException(
                "'${model.version}' is the untrained demo stub; pass --demo to use it, " +
                    "or provision the real $DEFAULT_FILE_NAME",
            )
        }
    }

    /**
     * Whether this model can actually distinguish a print process.
     *
     * The demo stub's weights are all zero, so it returns an argmax for every patch with a
     * margin of exactly `0.000` — a label with no information behind it. That is the whole
     * test, and it is keyed on the stub's own training-run id rather than on a flag passed
     * alongside the model, so "is this thing trained?" cannot drift from "was the model
     * usable?" — the two used to be separate questions and answering them differently is how
     * `--demo` came to print `model=OFFSET` as though it were a reading.
     */
    fun isDiscriminative(model: SvmModel): Boolean = model.trainingRunId != DemoSvm.TRAINING_RUN_ID

    fun classifierFor(model: SvmModel): ProcessClassifier = ProcessClassifier(model)

    /** Canonical order for the file, so `train_svm.py` and this reader cannot drift. */
    fun expectedFeatureNames(): List<String> = SvmModelReader.expectedFeatureNames()

    /**
     * The D-MACRO model file name, and the committed synthetic one after it.
     *
     * Checked in that order by [ModelLocator]. A real model is always preferred even when the
     * synthetic file is present, because the alternative is a console that keeps reporting the
     * synthetic number after real data has landed.
     */
    const val DEFAULT_FILE_NAME: String = "svm_print_v1.json"
    const val SYNTHETIC_FILE_NAME: String = "svm_print_v1_synthetic.json"

    /**
     * The same two files, addressed as a source checkout holds them.
     *
     * These are **relative on purpose and are only ever resolved against an explicit working
     * directory** ([ModelLocator] does the joining; nothing here calls `Path.of(DEFAULT_PATH)`
     * and hopes). They used to be consumed as bare CWD-relative strings by `Console`, which is
     * what made a provisioned console abstain on every patch: the path was resolved against
     * whatever directory the operator happened to be standing in, and an unpacked distribution
     * has no `eval/` at all. A repository-relative path is correct for a developer at a
     * checkout and wrong everywhere else, so it is a *last* resort with a name attached
     * rather than the first thing tried.
     */
    const val DEFAULT_PATH: String = "eval/models/$DEFAULT_FILE_NAME"
    const val SYNTHETIC_PATH: String = "eval/models/$SYNTHETIC_FILE_NAME"

    /**
     * Re-raise `:core`'s format refusal as [SvmModelException].
     *
     * The console has one exception type for "this model cannot be used" and the harness has
     * another; two names for one condition would leave a caller catching only one of them. The
     * message is `:core`'s, verbatim, so the reason is not paraphrased on the way out.
     */
    private inline fun <T> read(block: () -> T): T = try {
        block()
    } catch (e: SvmModelFormatException) {
        throw SvmModelException(e.message ?: "unusable svm model file")
    }
}

/** A model file that cannot be used. Never falls back to a default. */
class SvmModelException(message: String) : Exception(message)
