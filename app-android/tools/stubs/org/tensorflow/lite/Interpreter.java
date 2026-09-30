// WHY THESE STUBS EXIST — read before adding or "fixing" one
//
// `:app-android` cannot be compiled on a machine with no Android SDK, and this module was written
// on exactly such a machine. The cost of that was concrete, not theoretical: when
// `TfliteFaceDetector` was first type-checked against these stubs it had **four** independent
// compile errors — a `Digest { … }` SAM conversion that does not exist, a call to `align` with too
// few arguments, a property assignment on a Java options object that resolved to the wrong
// variable and so set nothing, and a `/*` inside a KDoc block that Kotlin's lexer rejects. A
// module that has never been compiled accumulates all of them, and none of them is visible from
// reading the code.
//
// So `verify-offline.sh` compiles the TFLite/Android-facing part of this module against these
// declarations on a bare JVM.
//
// WHAT THIS PROVES
//   · the Kotlin is syntactically and lexically valid — comments, string templates, nesting
//   · every name it references inside this module and in `:core` resolves, with the right arity
//     and types (`:core`, `:ui` and `:platform` are on the classpath as *real* compiled classes)
//   · the calls it makes have the signatures it assumes, for the members listed below
//   · the pure `dev.kasoti.android.ml` decode that the binding delegates to compiles too
//
// WHAT THIS DOES **NOT** PROVE — read this before trusting a green run
//   · that the signatures below match the real Android SDK or the real TFLite AAR. These are our
//     reading of them. A member we got wrong would type-check here and fail on a device.
//   · anything about resources, the manifest, aapt2, d8, R8, Compose, dependency resolution
//   · that `Interpreter.runForMultipleInputsOutputs` behaves as the Javadoc says
//   · any device behaviour whatsoever
//
// In short this is a *syntax-and-name-resolution* gate, not a build. It is a strictly larger tier
// than "this file is not compiled at all", and a strictly smaller one than `./gradlew
// :app-android:assembleDebug` on a machine with the SDK.
//
// If you change a call here, change the stub in the same commit, and say why in the PR. A stub
// that is silently widened to make a file compile is worse than no stub: it converts a loud
// failure into a quiet one.

package org.tensorflow.lite;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * The subset of `org.tensorflow.lite.Interpreter` that `TfliteFaceDetector` touches.
 *
 * Two members are the reason the multi-output path is spelled the way it is, and both are recorded
 * because getting either wrong was one of the four defects this tier found:
 *
 *  - `Options` exposes **setters only** (`setNumThreads`, `setUseXNNPACK`) and no getters, so
 *    Kotlin synthesises no property for either. `apply { numThreads = numThreads }` therefore
 *    resolves the left-hand side to the enclosing function's parameter and silently does nothing.
 *  - the two-output call is `runForMultipleInputsOutputs(Object[], Map<Integer, Object>)`. The
 *    two-argument `run(Object, Object)` picks its output accessor from the *input*'s type, writes
 *    output 0, and then fails on output 1's shape — so a two-output model cannot use it.
 */
public class Interpreter {

    public Interpreter(ByteBuffer buffer, Options options) {}

    public int getInputTensorCount() { return 0; }

    public Tensor getInputTensor(int index) { return null; }

    public int getOutputTensorCount() { return 0; }

    public Tensor getOutputTensor(int index) { return null; }

    public void run(Object input, Object output) {}

    public void runForMultipleInputsOutputs(Object[] inputs, Map<Integer, Object> outputs) {}

    public long getLastNativeInferenceDurationNanoseconds() { return 0L; }

    public void close() {}

    /** Setters, no getters — deliberately, so the property-assignment mistake cannot hide. */
    public static class Options {
        public Options() {}

        public Options setNumThreads(int numThreads) { return this; }

        public Options setUseXNNPACK(boolean useXNNPACK) { return this; }
    }
}
