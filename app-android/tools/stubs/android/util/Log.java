// See the header in org/tensorflow/lite/Interpreter.java for what these stubs are and are not.
package android.util;

/**
 * The subset of `android.util.Log`.
 *
 * Note for whoever wires this up: `Log.i`/`Log.e` on a model digest is a *tagged* string, not a
 * PII leak, but it is still the model identity going to logcat. THREAT_MODEL §5 and AGENTS.md §5
 * forbid logging names, dates of birth and embeddings; a SHA-256 of a pinned public artefact is
 * none of those, and the refusal path in `ModelPinLoader` logs the *expected* digest on purpose so
 * a mismatch is diagnosable.
 */
public final class Log {
    public static int v(String tag, String message) { return 0; }

    public static int d(String tag, String message) { return 0; }

    public static int i(String tag, String message) { return 0; }

    public static int w(String tag, String message) { return 0; }

    public static int e(String tag, String message) { return 0; }
}
