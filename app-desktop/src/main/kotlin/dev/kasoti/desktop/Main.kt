package dev.kasoti.desktop

/**
 * `kasoti` — the Post-Console entry point.
 *
 * The only place in the module that knows about exit codes and `System.exit`. Every
 * command returns a [CommandResult] and is testable in-process, so the wiring here is
 * thin enough to be obviously right: parse, dispatch, print, exit.
 *
 * Two rules this file exists to enforce. A [UsageException] prints the message and exits
 * `USAGE` — an operator who mistyped a flag gets a sentence, not a stack trace, because
 * the person reading it is standing at a post and not a developer. And every line is
 * pushed through the log scrubber before it reaches stdout, including lines built from
 * case metadata, so invariant I5 does not depend on remembering to call it.
 */
object Main

fun main(args: Array<String>) {
    val code = runConsole(args, ConsoleHome.default())
    kotlin.system.exitProcess(code)
}

/** The console's `main`, minus the `exitProcess`. Exits 0 when given no arguments. */
fun runConsole(args: Array<String>, home: ConsoleHome): Int {
    val console = Console(home)
    if (args.isEmpty()) {
        console.help()
        return ConsolePolicy.Exit.OK
    }
    return try {
        val result = console.run(CommandLine.parse(args))
        if (result.summary.isNotEmpty()) {
            println("  => ${LogScrubber.scrub(result.summary)}")
        }
        result.exitCode
    } catch (e: UsageException) {
        System.err.println("kasoti: ${e.message}")
        System.err.println("try `kasoti help`")
        ConsolePolicy.Exit.USAGE
    } catch (e: IllegalArgumentException) {
        System.err.println("kasoti: ${e.message}")
        ConsolePolicy.Exit.USAGE
    } catch (e: dev.kasoti.desktop.screen.SvmModelException) {
        System.err.println("kasoti: ${e.message}")
        ConsolePolicy.Exit.REFUSED
    } catch (e: dev.kasoti.platform.ml.ModelLoadException) {
        System.err.println("kasoti: ${e.code.messageKey} — ${e.modelName} was not loaded")
        ConsolePolicy.Exit.REFUSED
    } catch (e: ChainIntegrityException) {
        System.err.println("kasoti: ${e.message}")
        ConsolePolicy.Exit.INTEGRITY
    } catch (e: dev.kasoti.desktop.bundle.BundleTooLargeException) {
        System.err.println("kasoti: ${e.message}")
        ConsolePolicy.Exit.REFUSED
    } catch (e: java.io.IOException) {
        System.err.println("kasoti: ${e.message}")
        ConsolePolicy.Exit.FAILURE
    }
}
