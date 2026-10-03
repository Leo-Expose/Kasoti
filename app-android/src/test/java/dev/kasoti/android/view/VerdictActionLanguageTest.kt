package dev.kasoti.android.view

import dev.kasoti.i18n.Language
import dev.kasoti.ui.FieldStrings
import dev.kasoti.ui.VerdictScreen
import dev.kasoti.ui.VerdictTone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Hindi language toggle is silently defeated on the verdict screen.
 *
 * ## The defect this file exists to prevent
 *
 * `KasotiScreen` dispatched `ScreenState.Macro` and `ScreenState.Trust` to composables that took
 * a `Language`, and dispatched `ScreenState.Verdict` to `VerdictCard`, which took none — it
 * declared `val language = Language.ENGLISH` for itself. So an officer who switched the UI to
 * Hindi saw Hindi on every screen and, at the moment the verdict arrived, English again: the
 * action button, and the voice-repeat button beside it.
 *
 * That is the worst possible place for the bug. SPEC §3's persona is Hindi-first, and the verdict
 * screen is the one screen the officer *acts* on.
 *
 * ## Why this test reads the source as well as calling the resolver
 *
 * A composable cannot be rendered by this project's tests. There is no Robolectric and no
 * `compose-ui-test` dependency, deliberately — `:ui`'s whole design is that string resolution is
 * unit-testable on a bare JVM — and adding one needs the five-line ADR AGENTS.md §5 requires.
 *
 * So the choice of string was moved out of the composable and onto
 * [VerdictScreen.actionLabel], which is a pure function on data in `:ui` and is directly testable.
 * But a pure-function test alone would keep passing if `VerdictCard` were handed a language and
 * then ignored it, so this file closes that hole two more ways:
 *
 *  1. by reflecting on `VerdictCard`'s compiled signature and requiring a `Language` parameter —
 *     without one, the composable *cannot* be pinned to English by construction; and
 *  2. by scanning the renderer for a hardcoded `Language.X` reference, which is the exact shape
 *     the defect took.
 *
 * Source scanning is not a substitute for a behavioural assertion and is not pretending to be
 * one; it is here because it catches precisely the edits a behavioural test structurally cannot
 * see. The repo already gates on source text for the same reason — `check_no_magic_thresholds.sh`
 * and the `:core` network-import ban both do — so this is an existing idiom here, not a new one.
 */
class VerdictActionLanguageTest {

    private fun screen(
        tone: VerdictTone = VerdictTone.CLEAR,
        supervisorRequired: Boolean = false,
    ) = VerdictScreen(
        verdictName = tone.name,
        headline = FieldStrings.of(headlineKey(tone), Language.ENGLISH),
        tone = tone,
        findings = emptyList(),
        retakeInstructions = emptyList(),
        carriedOver = emptyList(),
        layers = emptyList(),
        supervisorRequired = supervisorRequired,
        trustFastPath = false,
        demoMode = false,
        policyLine = "policy",
        thumbnailKey = null,
    )

    private fun headlineKey(tone: VerdictTone) = when (tone) {
        VerdictTone.CLEAR -> FieldStrings.Key.VERDICT_GREEN
        VerdictTone.SECONDARY -> FieldStrings.Key.VERDICT_AMBER
        VerdictTone.STOP -> FieldStrings.Key.VERDICT_RED
        VerdictTone.RETAKE -> FieldStrings.Key.VERDICT_GREY
    }

    // ------------------------------------------------------------------ behaviour

    @Test
    fun `the verdict action button is Hindi when the officer chose Hindi`() {
        // The regression assertion. Against the old code — `VerdictCard` hardcoding English — the
        // screen showed "Next person" here.
        val label = screen().actionLabel(Language.HINDI)
        assertEquals(FieldStrings.of(FieldStrings.Key.ACTION_NEXT, Language.HINDI), label)
        assertEquals("अगला व्यक्ति", label)
        assertTrue(label != FieldStrings.of(FieldStrings.Key.ACTION_NEXT, Language.ENGLISH))
    }

    @Test
    fun `adversarial - the retake branch is Hindi too, not just the default`() {
        // The branch an officer hits when the quality gate failed, i.e. the branch they are most
        // likely to be looking at when the toggle is still fresh.
        val label = screen(tone = VerdictTone.RETAKE).actionLabel(Language.HINDI)
        assertEquals(FieldStrings.of(FieldStrings.Key.ACTION_RETAKE, Language.HINDI), label)
        assertEquals("दोबारा फोटो", label)
    }

    @Test
    fun `adversarial - the supervisor-required branch is Hindi, and retake still beats it`() {
        // Two cases, because only testing the default branch would have passed against
        // English-only code.
        val supervisor = screen(tone = VerdictTone.STOP, supervisorRequired = true).actionLabel(Language.HINDI)
        assertEquals(FieldStrings.of(FieldStrings.Key.ACTION_SUPERVISOR, Language.HINDI), supervisor)
        assertEquals("पर्यवेक्षक को बुलाएँ", supervisor)

        // A GREY that also set supervisorRequired must still say "retake": the operator's next
        // action on a GREY is another photograph, not a phone call.
        val grey = screen(tone = VerdictTone.RETAKE, supervisorRequired = true).actionLabel(Language.HINDI)
        assertEquals(FieldStrings.of(FieldStrings.Key.ACTION_RETAKE, Language.HINDI), grey)
    }

    @Test
    fun `adversarial - no tone and no supervisor flag yields English under Hindi`() {
        for (tone in VerdictTone.entries) {
            for (supervisor in listOf(false, true)) {
                val label = screen(tone, supervisor).actionLabel(Language.HINDI)
                val english = screen(tone, supervisor).actionLabel(Language.ENGLISH)
                assertTrue(
                    label != english,
                    "$tone/supervisor=$supervisor rendered \"$label\" for both languages",
                )
                assertTrue(
                    label.any { it.code > 0x0900 },
                    "$tone/supervisor=$supervisor returned no Devanagari for Hindi: \"$label\"",
                )
            }
        }
    }

    @Test
    fun `English is still English - the fix is not a blanket Hindi`() {
        assertEquals("Next person", screen().actionLabel(Language.ENGLISH))
        assertEquals("Retake", screen(tone = VerdictTone.RETAKE).actionLabel(Language.ENGLISH))
        assertEquals("Call supervisor", screen(supervisorRequired = true).actionLabel(Language.ENGLISH))
    }

    // ------------------------------------------------------------------ the wiring

    @Test
    fun `VerdictCard declares a Language parameter, so it cannot pin itself to English`() {
        val method = assertNotNull(
            VerdictActionLanguageTest::class.java.classLoader!!
                .loadClass("dev.kasoti.android.view.ComposeFieldViewKt")
                .declaredMethods
                .firstOrNull { it.name == "VerdictCard" },
            "VerdictCard is no longer a top-level function in ComposeFieldView.kt",
        )
        val declared = method.parameterTypes.map { it.simpleName }
        assertTrue(
            declared.contains("Language"),
            "VerdictCard takes no Language parameter (got $declared) — the language cannot reach " +
                "the verdict screen, which is the defect this test exists to catch",
        )
    }

    @Test
    fun `KasotiScreen hands the app state language to VerdictCard`() {
        val code = stripComments(rendererSource())
        // Paren-matched rather than a fixed window around the name: a 300-character window around
        // the real call site also reaches `TrustCardScreen(state.language, …)` two lines below it,
        // so this assertion passed against the defective code until it was tightened. Only the
        // call's *own* argument list counts.
        val calls = Regex("""(?<!fun )\bVerdictCard\(""").findAll(code).map { it.range.first }.toList()
        assertTrue(calls.isNotEmpty(), "KasotiScreen no longer calls VerdictCard")

        val withoutLanguage = calls.filterNot { balancedArguments(code, it).contains("state.language") }
        assertEquals(
            emptyList(),
            withoutLanguage.map { balancedArguments(code, it).replace('\n', ' ') },
            "every VerdictCard call site must pass state.language — the toggle cannot reach the " +
                "verdict screen otherwise",
        )
    }

    @Test
    fun `the renderer hardcodes no Language, no user-facing literal and no content description`() {
        val code = stripComments(rendererSource())

        val languages = Regex("""\bLanguage\.[A-Z_]+""").findAll(code).map { it.value }.toList()
        assertEquals(
            emptyList(),
            languages,
            "ComposeFieldView.kt resolves a language literally. Every screen must use the " +
                "Language it is handed, or the toggle is defeated again.",
        )

        val textLiterals = Regex("""\bText\(\s*"(?!\$)""").findAll(code).count()
        assertEquals(0, textLiterals, "a Text() call still renders a bare literal")

        // `"(?!\$"` so an interpolation of already-localised data — `"${step.label} ${step.title}"`
        // in `StepStrip` — is not counted: nothing is hardcoded there, it is two localised values
        // concatenated. A literal *starting* the string is what a hardcoded UI string looks like.
        val descriptions = Regex("""contentDescription\s*=\s*"(?!\$)""").findAll(code).count()
        assertEquals(0, descriptions, "a contentDescription is still a bare literal — TalkBack " +
            "would read English after the officer chose Hindi")
    }

    @Test
    fun `the crop overlay description is keyed by the app toggle, not the device locale`() {
        // `stringResource` would follow the device locale and reintroduce the same defect one
        // layer down, so the overlay takes the toggle's Language as a parameter. Same guard as
        // above, applied to the second file that renders user-facing text.
        val code = stripComments(overlaySource())
        assertEquals(
            emptyList(),
            Regex("""\bLanguage\.[A-Z_]+""").findAll(code).map { it.value }.toList(),
            "QuadHandleOverlay.kt resolves a language literally",
        )
        assertEquals(
            0,
            Regex("""contentDescription\s*=\s*"(?!\$)""").findAll(code).count(),
            "the crop overlay's accessibility description is still a bare literal",
        )
        assertTrue(
            code.contains("fun CameraSurface(\n        language: Language,") ||
                Regex("""fun CameraSurface\([\s\S]{0,200}?language: Language,""").containsMatchIn(code),
            "CameraSurface does not accept a Language, so the overlay cannot be localised",
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun rendererSource(): String = moduleFile("view/ComposeFieldView.kt")

    private fun overlaySource(): String = moduleFile("view/QuadHandleOverlay.kt")

    private fun moduleFile(relative: String): String = File(moduleDir(), "src/main/java/dev/kasoti/android/$relative").readText()

    /**
     * The module directory, found by walking up to the Gradle settings file.
     *
     * Gradle runs a module's unit tests with the module directory as the working directory, but
     * relying on that makes every assertion in this file fail with a bare `FileNotFoundException`
     * if it ever changes, and a test that cannot find its own fixture is a test that has quietly
     * stopped testing anything.
     */
    private fun moduleDir(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?.resolve("app-android")
        ?: error("no settings.gradle.kts above ${File("").absolutePath}")

    /** The text between the `(` at [open] and its matching `)`. */
    private fun balancedArguments(source: String, open: Int): String {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        return source.substring(open)
    }

    /**
     * Drop comments so the assertions above test code rather than prose.
     *
     * Necessary because this file's own KDoc has to *name* the defect it describes
     * (`Language.ENGLISH` is the literal the renderer used to carry), and a scanner that cannot
     * tell a KDoc from code cannot be written without either weakening the assertion or
     * forbidding the file from documenting itself.
     */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        // `://` so a URL in a comment does not truncate the rest of the line.
        .replace(Regex("""(?<!:)//[^\n]*"""), " ")
}