package dev.kasoti.android.view

import dev.kasoti.i18n.Language
import dev.kasoti.ui.FieldStrings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the `cd_*` accessibility resources honest now that the renderer reads `FieldStrings`.
 *
 * ## The situation this file describes rather than papers over
 *
 * `app-android/src/main/res/values/strings.xml` carries ten `cd_*` accessibility strings with
 * Hindi counterparts in `values-hi/strings.xml`. Until this change, **not one of them was read by
 * any Kotlin file** — `R.string.*` had zero references outside `AndroidManifest.xml`, and the
 * Compose code hardcoded its TalkBack descriptions in English instead.
 *
 * They were not wired to `stringResource`, and the reason is load-bearing rather than cosmetic:
 *
 *  * the app's language is a **user toggle** held in `AppState.language` and dispatched as
 *    `UiEvent.SetLanguage`;
 *  * `stringResource` resolves against the **device locale**.
 *
 * Those are two different sources of truth. Wiring the overlay's description to `stringResource`
 * would have left a Hindi-speaking officer with a Hindi verdict screen and an English crop-overlay
 * description — the identical defect this change set exists to fix, moved down one layer. So the
 * descriptions moved into `FieldStrings`, which is keyed by the toggle and is unit-testable on a
 * bare JVM, exactly as `values/strings.xml`'s own header says the composables should be wired.
 *
 * ## What this test then does about it
 *
 * Leaving ten translated resources unused would let them drift silently against the catalogue
 * that replaced them, so this file binds the two together. It asserts:
 *
 *  1. every `cd_*` key in `values/strings.xml` has a `values-hi` entry — the translations are
 *     already paid for, and a missing one is a lint `MissingTranslation` away; and
 *  2. where a `cd_*` resource and a `FieldStrings` key are the *same string*, their values are
 *     byte-identical in both languages.
 *
 * The second assertion is deliberately limited to the pairs that really are the same string. Four
 * `cd_*` resources are generic descriptions ("Live capture quality meter") where the renderer
 * needs a specific one ("Capture quality 42%"), and substituting the generic text would lose
 * information a screen-reader user needs. Those are listed in [RICHER_THAN_RESOURCE] with the
 * reason, so the gap is declared rather than hidden.
 */
class AccessibilityStringParityTest {

    private val english = resourceFile("values/strings.xml")
    private val hindi = resourceFile("values-hi/strings.xml")

    /**
     * `cd_*` resource name → the `FieldStrings` key that carries the same string, for the pairs
     * that are the same string.
     */
    private val exactTwins = mapOf(
        "cd_crop_overlay" to FieldStrings.Key.CD_CROP_OVERLAY,
        "cd_verdict_headline" to FieldStrings.Key.CD_VERDICT_HEADLINE,
        "cd_layer_status" to FieldStrings.Key.CD_LAYER_STATUS,
    )

    /**
     * Where the renderer deliberately says *more* than its `cd_*` resource twin. Each of these
     * would lose the specific value a screen-reader user needs if the generic resource text
     * replaced it, so they are declared here rather than forced into [exactTwins].
     */
    private val richerThanResource = mapOf(
        "cd_quality_meter" to "CD_QUALITY_METER names the live percentage (42%), not just the meter",
        "cd_sharpness_meter" to "CD_SHARPNESS_METER names which patch (photo zone / text zone)",
        "cd_capture_preview" to "not read anywhere: the preview carries no contentDescription yet",
        "cd_shutter" to "not read anywhere: the shutter button has no contentDescription yet",
        "cd_next_step" to "not read anywhere: no step button carries one yet",
        "cd_voice_repeat" to "not read anywhere: the read-again button has none yet",
        "cd_evidence_thumbnail" to "not read anywhere: no evidence thumbnail view exists yet",
    )

    @Test
    fun `every accessibility resource has a Hindi entry`() {
        val keys = Regex("""<string name="(cd_[a-z_]+)">""").findAll(english).map { it.groupValues[1] }.toList()
        assertTrue(keys.isNotEmpty(), "no cd_* keys parsed out of values/strings.xml — the " +
            "parity assertions below would pass vacuously")
        assertEquals(10, keys.size, "expected the ten documented cd_* keys, found $keys")

        val hindiKeys = Regex("""<string name="(cd_[a-z_]+)">""").findAll(hindi).map { it.groupValues[1] }.toSet()
        val untranslated = keys.filterNot { it in hindiKeys }
        assertEquals(
            emptyList(),
            untranslated,
            "these accessibility descriptions are English-only in values-hi, so a Hindi-speaking " +
                "screen-reader user hears English (FR-R1)",
        )
    }

    @Test
    fun `the Hindi accessibility resources are translated, not copied from the English`() {
        for (key in Regex("""<string name="(cd_[a-z_]+)">""").findAll(english).map { it.groupValues[1] }) {
            val en = value(english, key)
            val hi = value(hindi, key)
            assertTrue(hi.isNotBlank(), "$key has a blank Hindi string")
            if (!en.contains("%")) {
                assertTrue(en != hi, "$key is byte-identical in both languages — English was copied over")
            }
        }
    }

    @Test
    fun `a resource and the catalogue key that replaced it say the same thing`() {
        for ((resource, key) in exactTwins) {
            assertEquals(
                value(english, resource),
                FieldStrings.of(key, Language.ENGLISH),
                "$resource and FieldStrings.$key are the same string and must not drift. The " +
                    "resource is the pre-Compose copy; the catalogue key is what the renderer reads.",
            )
            assertEquals(
                value(hindi, resource),
                FieldStrings.of(key, Language.HINDI),
                "$resource and FieldStrings.$key must agree in Hindi too",
            )
        }
    }

    @Test
    fun `the resources declared richer than their twin are the ones declared`() {
        // Stops the `richerThanResource` block from quietly becoming a place where real parity
        // stops being enforced.
        val declared = exactTwins.keys + richerThanResource.keys
        val actual = Regex("""<string name="(cd_[a-z_]+)">""").findAll(english).map { it.groupValues[1] }.toSet()
        assertEquals(actual, declared, "every cd_* resource must be accounted for as either an " +
            "exact twin or a declared richer variant — see AccessibilityStringParityTest")
    }

    @Test
    fun `the renderer reads the catalogue for every description it draws`() {
        // The four descriptions the renderer actually uses, pinned to keys rather than to text so
        // that re-introducing an inline literal is what fails.
        val code = stripComments(modulePath("view/ComposeFieldView.kt").readText()) +
            stripComments(modulePath("view/QuadHandleOverlay.kt").readText())
        for (key in listOf(
            FieldStrings.Key.CD_CROP_OVERLAY,
            FieldStrings.Key.CD_VERDICT_HEADLINE,
            FieldStrings.Key.CD_LAYER_STATUS,
            FieldStrings.Key.CD_QUALITY_METER,
            FieldStrings.Key.CD_SHARPNESS_METER,
            FieldStrings.Key.CD_DEMO_MODE,
        )) {
            assertTrue(
                code.contains("FieldStrings.Key.${key.name}"),
                "FieldStrings.Key.${key.name} is defined and translated but no composable uses it",
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun modulePath(relative: String): File =
        File(moduleDir(), "src/main/java/dev/kasoti/android/$relative")

    private fun resourceFile(relative: String): String =
        File(moduleDir(), "src/main/res/$relative").readText()

    /** See `VerdictActionLanguageTest.moduleDir` for why the root is located rather than assumed. */
    private fun moduleDir(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?.resolve("app-android")
        ?: error("no settings.gradle.kts above ${File("").absolutePath}")

    /** @return the text content of `<string name="[name]">`, or a failure if it is absent. */
    private fun value(xml: String, name: String): String {
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
        assertTrue(match != null, "no <string name=\"$name\"> in the parsed resource file")
        return match!!.groupValues[1]
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\\'", "'")
            .trim()
    }

    /** Comments are stripped so the assertions test code and not the prose describing it. */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?<!:)//[^\n]*"""), " ")
}