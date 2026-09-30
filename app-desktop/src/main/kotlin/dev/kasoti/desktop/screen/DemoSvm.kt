package dev.kasoti.desktop.screen

import dev.kasoti.factory.MacroFeatures
import dev.kasoti.factory.ProcessLabel
import dev.kasoti.factory.SvmModel

/**
 * The untrained macro stub, available only under an explicit `--demo`.
 *
 * Every weight is zero, so all seven classes score identically and the softmax margin is
 * exactly `0.000` — below every floor in the registry. The consequence is deliberate: the
 * system reads the patch as `UNKNOWN`, `DocAggregator` would call it `UNCERTAIN`, and the
 * case lands on `A-WORN-01` / AMBER rather than on a process verdict.
 *
 * That is the behaviour worth demonstrating. A demo that printed "OFFSET" would be showing a
 * classifier that does not exist. `SvmModelFile.requireUsable` refuses this model unless the
 * operator passed `--demo`, and the decision is stamped `DEMO RUN` on the console and in the
 * case bundle (invariant I7), so a demo reading can never be mistaken for a tuned one.
 */
object DemoSvm {

    const val TRAINING_RUN_ID = "demo-untrained"

    fun untrained(): SvmModel = SvmModel(
        version = "demo-untrained-v0",
        labels = ProcessLabel.entries.toList(),
        weights = Array(ProcessLabel.entries.size) { FloatArray(MacroFeatures.TOTAL) },
        bias = FloatArray(ProcessLabel.entries.size),
        featureNames = SvmModelFile.expectedFeatureNames(),
        trainingRunId = TRAINING_RUN_ID,
    )
}
