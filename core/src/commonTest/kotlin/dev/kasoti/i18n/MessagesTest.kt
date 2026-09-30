package dev.kasoti.i18n

import dev.kasoti.fusion.FindingCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-R1: every finding code must have an operator-facing line in English and Hindi.
 *
 * This is deliberately a whole-enum sweep rather than per-case tests: a new `FindingCode`
 * that ships without strings is a build failure, not a demo-day surprise.
 */
class MessagesCompletenessTest {

    @Test
    fun `every finding code has an English string`() {
        val missing = Messages.missingEnglish()
        assertTrue(missing.isEmpty(), "missing English strings for: $missing")
    }

    @Test
    fun `every finding code has a Hindi string`() {
        val missing = Messages.missingHindi()
        assertTrue(missing.isEmpty(), "missing Hindi strings for: $missing")
    }

    @Test
    fun `no string is blank or just the code`() {
        for (code in FindingCode.entries) {
            for (lang in Language.entries) {
                val text = Messages.of(code, lang)
                assertTrue(text.isNotBlank(), "$code/$lang is blank")
                assertTrue(text != code.name, "$code/$lang fell back to the raw code")
            }
        }
    }

    @Test
    fun `language codes parse`() {
        assertEquals(Language.HINDI, Language.from("hi"))
        assertEquals(Language.ENGLISH, Language.from("en"))
        assertEquals(Language.ENGLISH, Language.from("xx"), "unknown codes fall back to English")
    }
}
