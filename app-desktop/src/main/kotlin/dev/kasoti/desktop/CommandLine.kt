package dev.kasoti.desktop

/**
 * Argument parsing for the Post-Console.
 *
 * Hand-written rather than pulled from a CLI library, for one reason: every dependency in
 * this project needs a five-line ADR in its PR (AGENTS.md §5), and a console with six
 * subcommands and flat `--key value` flags does not justify one. The shape is deliberately
 * boring — `command --key value --flag` — so that `kasoti screen --image x.png --track
 * PASSPORT` reads the way an officer would expect it to.
 */
class CommandLine private constructor(
    val command: String,
    private val options: Map<String, String>,
    private val flags: Set<String>,
) {

    /** @return the option's value, or `null` when absent. */
    fun option(key: String): String? = options[key]

    fun option(key: String, default: String): String = options[key] ?: default

    /** @return the option's value, or fail with a usage error naming the missing flag. */
    fun required(key: String): String = options[key]
        ?: throw UsageException("missing required option --$key")

    fun has(key: String): Boolean = flags.contains(key) || options.containsKey(key)

    /**
     * `--no-clip` sets a boolean false; the flag form sets it true.
     *
     * An explicit `--flag=maybe` is a usage error rather than a silent `false`. Every
     * boolean here turns a safety control on or off — `--no-clip`, `--demo`,
     * `--include-live-face` — and quietly reading a typo as "off" is the failure that
     * matters: the operator believes a protection is engaged and it is not.
     */
    fun flag(key: String, default: Boolean = false): Boolean {
        val negated = "no-" + key
        if (flags.contains(negated)) return false
        if (flags.contains(key)) return true
        val explicit = options[key] ?: return default
        return explicit.toBooleanStrictOrNull()
            ?: throw UsageException("--$key expects true or false, got '$explicit'")
    }

    fun int(key: String, default: Int): Int = options[key]?.toIntOrNull()
        ?: options[key]?.let { throw UsageException("--$key expects an integer, got '$it'") }
        ?: default

    fun float(key: String): Float? = options[key]?.toFloatOrNull()
        ?: options[key]?.let { throw UsageException("--$key expects a number, got '$it'") }

    fun paths(key: String): List<String> = (options[key] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Reject an option the command does not understand, so a typo never passes silently. */
    fun rejectUnknown(known: Set<String>) {
        val unknown = (options.keys + flags).filterNot { it in known }
        if (unknown.isNotEmpty()) {
            throw UsageException("unknown option(s): ${unknown.joinToString(", ") { "--$it" }}")
        }
    }

    companion object {

        /**
         * `--no-foo` becomes a flag named `nofoo` with an `no-` prefix stripped, and
         * `--foo bar` becomes an option. Everything after the first non-option token is
         * rejected, because a console that silently ignores a positional argument is a
         * console that will eventually screen the wrong document.
         */
        fun parse(args: Array<String>): CommandLine {
            if (args.isEmpty()) throw UsageException("no command given")
            val command = args[0]
            if (command.startsWith("-")) throw UsageException("expected a command, got '$command'")

            val options = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()

            var i = 1
            while (i < args.size) {
                val token = args[i]
                if (!token.startsWith("--")) {
                    throw UsageException("expected an option starting with '--', got '$token'")
                }
                val body = token.removePrefix("--")
                if (body.isEmpty()) throw UsageException("empty option name")
                val eq = body.indexOf('=')
                if (eq >= 0) {
                    options[body.substring(0, eq)] = body.substring(eq + 1)
                    i++
                    continue
                }
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) {
                    options[body] = next
                    i += 2
                } else {
                    flags += body
                    i++
                }
            }
            return CommandLine(command, options, flags)
        }
    }
}

/** A user-facing problem with the command line. Exit code `USAGE`, never a stack trace. */
class UsageException(message: String) : Exception(message)
