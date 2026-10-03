package dev.kasoti.desktop

/**
 * Policy constants for the Post-Console.
 *
 * These are *operational* policy, not evaluation thresholds. The distinction matters
 * because AGENTS.md §2 puts every tunable that affects a verdict in
 * `ThresholdRegistry`, and a reviewer should be able to tell at a glance that nothing in
 * this file can change a GREEN into a RED. Nothing here is read by the cascade.
 */
object ConsolePolicy {

    /**
     * Case-bundle size budget (DESIGN.md §4, NFR-P2).
     *
     * A bundle is the artefact that leaves the consent boundary, so its size is a privacy
     * control as much as a usability one: if the crops are the reason a bundle is large,
     * the crops are too much. Exceeding the budget fails the export rather than warning.
     */
    const val BUNDLE_SIZE_BUDGET_BYTES = 500_000L

    /**
     * The mandatory reason vocabulary for an override (FR-S4).
     *
     * A closed list is the entire point. "Other, with a note" is allowed; "other" alone is
     * not, because it is the reason code that turns into a shrug six months later.
     */
    val OVERRIDE_REASONS: List<String> = listOf(
        "SUP_DOC_AUTHENTIC",
        "SUP_PRINT_DEFECT",
        "SUP_DATA_ENTRY_ERROR",
        "SUP_TRANSLITERATION",
        "SUP_DEVICE_FAULT",
        "SUP_OTHER_JUSTIFIED",
    )

    /** The one reason code that additionally demands a written note. */
    const val REASON_REQUIRING_NOTE = "SUP_OTHER_JUSTIFIED"

    /** Environment variable holding the supervisor PIN for the one-tap wipe (FR-S3). */
    const val SUPERVISOR_PIN_ENV = "KASOTI_SUPERVISOR_PIN"

    /**
     * Environment variable naming a macro (print-process) model to use.
     *
     * Provisioning knob, not a threshold: it changes *which file* is read, never what the
     * cascade does with what came back. The same file is found automatically inside an
     * unpacked installation, so this exists for deployments that keep models on a read-only
     * share rather than inside the artefact.
     */
    const val SVM_MODEL_ENV = "KASOTI_SVM_MODEL"

    /**
     * Environment variable overriding the console home (audit log, case store, PIN file).
     *
     * Defaults to `$HOME/.kasoti-console`. It was previously the *working directory*, which
     * meant the same `wipe` deleted a different store depending on where it was typed, and an
     * unpacked distribution shipped with a `.kasoti-console` already in it.
     */
    const val CONSOLE_HOME_ENV = "KASOTI_CONSOLE_HOME"

    /** Minimum PIN length. A four-digit post PIN on a laptop is not a control. */
    const val MIN_SUPERVISOR_PIN_LENGTH = 6

    /** Process exit codes. Distinct so a wrapper script can tell a refusal from a crash. */
    object Exit {
        const val OK = 0
        const val USAGE = 1
        const val REFUSED = 2
        const val INTEGRITY = 3
        const val FAILURE = 4
    }
}

/**
 * Supervisor PIN comparison (FR-S3).
 *
 * Constant time over a SHA-256 digest, so a timing observer learns nothing about how many
 * leading characters were right. The stored PIN may be a plain string (the provisioning
 * file) or an `sha256:<hex>` digest; both are accepted so the file never has to hold a
 * secret in the clear if the deployment would rather it did not.
 */
object SupervisorPin {

    const val PREFIX = "sha256:"

    fun digestOf(pin: String): String =
        dev.kasoti.crypto.Hex.encode(
            dev.kasoti.platform.crypto.JcaDigest().sha256(pin.toByteArray(Charsets.UTF_8)),
        )

    fun matches(supplied: String, stored: String?): Boolean {
        if (stored == null) return false
        if (supplied.length < ConsolePolicy.MIN_SUPERVISOR_PIN_LENGTH) return false
        val suppliedHash = digestOf(supplied).toByteArray(Charsets.US_ASCII)
        val expected = if (stored.startsWith(PREFIX)) {
            stored.removePrefix(PREFIX).toByteArray(Charsets.US_ASCII)
        } else {
            digestOf(stored).toByteArray(Charsets.US_ASCII)
        }
        return dev.kasoti.crypto.constantTimeEquals(suppliedHash, expected)
    }
}
