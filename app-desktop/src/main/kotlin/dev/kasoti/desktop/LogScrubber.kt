package dev.kasoti.desktop

import dev.kasoti.log.KeyClass
import dev.kasoti.log.PiiScrubber

/**
 * The console's PII chokepoint — **a delegation, not a definition**.
 *
 * The rules live in [PiiScrubber] (`:core`) and this object is the console's single call
 * point into them. It exists at all so that `LogScrubber.scrub(line)` keeps the signature
 * every call site in this module already uses (`Console.kt`, `Main.kt`, `OverrideCommand.kt`):
 * a scrubber that required seven modules to change to adopt would be a scrubber that gets
 * adopted badly.
 *
 * The real property being bought here is that **there is exactly one rule set.** Before this
 * move, `:core` had no scrubber at all while the desktop console had a good one, which is the
 * worst arrangement available: `scripts/pii_scrubber_test.sh` was red (correctly — the
 * control the project's own checklist names was not in the place the checklist looks), and
 * the field-app and Android layers had no control at all. One definition, two consumers.
 *
 * Every console log line goes through [scrub]. See [PiiScrubber] for what it does and, more
 * importantly, for what it must not be allowed to become: a field that reaches this chokepoint
 * and comes back `[redacted]` is a bug at the call site, and this layer bounding the blast
 * radius does not discharge it.
 */
object LogScrubber {

    /**
     * One instance, so the compiled shape patterns are built once per process rather than
     * once per log line.
     */
    private val scrubber: PiiScrubber = PiiScrubber.DEFAULT

    /** @return [line] with every recognised PII value replaced and every control character neutralised. */
    fun scrub(line: String): String = scrubber.scrub(line).text

    /** True when a key name, however spelled, denotes a PII field. */
    fun isPiiKey(key: String): Boolean = PiiScrubber.isPiiKey(key)

    /**
     * How the console's own field labels are classified.
     *
     * Exposed so a call site can *ask* before it logs: `KeyClass.OWN_DIGEST` means the field
     * is safe to log verbatim, `UNKNOWN` means nobody has reviewed it and the shape rules
     * will decide. A console that logs an `UNKNOWN` field is a console that is one refactor
     * away from a name in the audit log, and being able to see that at the call site is the
     * difference between a review finding and a privacy incident.
     */
    fun classifyKey(key: String): KeyClass = PiiScrubber.classify(key)

    /** The rule names, for the console's `--help` and for the test that checks coverage. */
    val patternNames: List<String> = PiiScrubber.patternNames
}
