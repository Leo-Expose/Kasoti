package dev.kasoti.desktop.screen

import dev.kasoti.factory.ProcessClassifier

/**
 * The macro model a screening will actually use, and everything the console has to say out
 * loud about it.
 *
 * ## Why this is not a bare `ProcessClassifier?`
 *
 * The cascade used to receive a bare `ProcessClassifier?` and that is exactly why a missing
 * model and a useless model looked identical downstream. `null` meant "the operator was told
 * the layer is unavailable"; a *non-null but untrained* classifier produced a `RAN` row
 * reading `photo=UNKNOWN(margin=0.000, model=OFFSET)` — an argmax from a model whose weights
 * are all zero, printed in the shape of a measurement. `--demo` made that reachable by
 * accident, because the demo branch substituted the stub whenever no file was found rather
 * than only when one was asked for.
 *
 * The provenance and the *discriminative* flag therefore travel with the classifier, so a
 * layer that cannot produce a real reading has the information to say so instead of printing a
 * number that looks like one.
 */
data class MacroModel(
    val classifier: ProcessClassifier,
    /** The file the weights came from, as the console will print it. */
    val source: String,
    /** `:core`'s own training-run string. Printed verbatim so the run can be cited. */
    val provenance: String,
    val origin: ModelOrigin,
    val synthetic: Boolean,
    /** False for the untrained demo stub: weights all zero, no opinion on any patch. */
    val discriminative: Boolean,
) {
    /**
     * The standing of this model, in one word, for the layer table.
     *
     * A model with no discriminative power is `UNAVAILABLE` and not `RAN` even though it
     * technically loaded and ran. The distinction the layer table has to carry is "this layer
     * produced a reading" versus "this layer has nothing to say", and an all-zero argmax is
     * the latter wearing the former's clothes.
     */
    val standing: String get() = when {
        !discriminative -> "UNTRAINED-STUB"
        synthetic -> "SYNTHETIC"
        else -> "TRAINED"
    }
}
