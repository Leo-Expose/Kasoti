package dev.kasoti.i18n

import dev.kasoti.log.RedactionResult
import dev.kasoti.log.RedactionRule

/**
 * Operator-facing lines for the scrubber's *counts* (FR-R1, owner: @privacy).
 *
 * ## Why this exists at all, and why it is so small
 *
 * [dev.kasoti.log.REDACTION_MARKER] is deliberately NOT localised — it is a stable token
 * because it gets grepped, counted and compared across logs written under different locale
 * settings, and a marker that moved with the UI language would make all three of those
 * locale-dependent. That reasoning stops at the marker, though.
 *
 * The *count* is different. "This line had three things removed from it" is prose for a human
 * reading a console, it has no stable wire form, and it is the number an operator acts on.
 * Presenting it only in English in an operator-facing tool whose findings are already
 * bilingual would be a real gap, so it gets a Hindi twin like every other operator string.
 *
 * The strings are plural-aware in the way that matters for the two shipped languages: English
 * has a singular form, Hindi does not inflect for number in the noun, so `hi` carries one form
 * and the count is rendered as a numeral in both. Inventing a Hindi dual here would be
 * linguistically gratuitous.
 */
object RedactionMessages {

    private val en: Map<RedactionRule, String> = mapOf(
        RedactionRule.PII_KEY to "a personal field was removed",
        RedactionRule.MRZ_ROW to "a machine-readable zone line was removed",
        RedactionRule.DATE to "a date was removed",
        RedactionRule.AADHAAR to "a 12-digit identifier was removed",
        RedactionRule.PHONE to "a phone number was removed",
        RedactionRule.EMAIL to "an email address was removed",
        RedactionRule.DOC_NUMBER to "a document number was removed",
        RedactionRule.HEX_BLOB to "a long hex value was removed",
        RedactionRule.BASE64_BLOB to "a long encoded value was removed",
        RedactionRule.JWT to "a signed token was removed",
        RedactionRule.CONTROL_CHAR to "a control character was replaced",
    )

    private val hi: Map<RedactionRule, String> = mapOf(
        RedactionRule.PII_KEY to "एक निजी फ़ील्ड हटाया गया",
        RedactionRule.MRZ_ROW to "मशीन-पठन क्षेत्र की एक पंक्ति हटाई गई",
        RedactionRule.DATE to "एक तारीख़ हटाई गई",
        RedactionRule.AADHAAR to "12 अंकों की एक पहचान संख्या हटाई गई",
        RedactionRule.PHONE to "एक फ़ोन नंबर हटाया गया",
        RedactionRule.EMAIL to "एक ईमेल पता हटाया गया",
        RedactionRule.DOC_NUMBER to "एक दस्तावेज़ संख्या हटाई गई",
        RedactionRule.HEX_BLOB to "एक लंबा हेक्स मान हटाया गया",
        RedactionRule.BASE64_BLOB to "एक लंबा एन्कोड किया गया मान हटाया गया",
        RedactionRule.JWT to "एक हस्ताक्षरित टोकन हटाया गया",
        RedactionRule.CONTROL_CHAR to "एक नियंत्रण वर्ण बदला गया",
    )

    /**
     * The one-line summary an operator reads under a scrubbed field.
     *
     * Falls back to the primary rule's English line rather than to the wire name: a line
     * that says `hex-blob` is a debugging aid, not an explanation, and the fallback exists
     * only so a future rule cannot ship without a string and produce an empty line.
     */
    fun summaryOf(result: RedactionResult, language: Language): String {
        val rule = result.primaryRule ?: return cleanOf(language)
        val count = result.byRule[rule] ?: 0
        val reason = when (language) {
            Language.ENGLISH -> en[rule] ?: rule.wireName
            Language.HINDI -> hi[rule] ?: en[rule] ?: rule.wireName
        }
        val noun = when (language) {
            Language.ENGLISH -> if (count == 1) "1 item" else "$count items"
            Language.HINDI -> "$count"
        }
        return if (language == Language.ENGLISH) "$noun — $reason" else "$count — $reason"
    }

    /** What to print when nothing was removed. Never blank, so a caller can print it always. */
    fun cleanOf(language: Language): String = when (language) {
        Language.ENGLISH -> "nothing removed"
        Language.HINDI -> "कुछ भी नहीं हटाया गया"
    }

    /** Rules with no English string. Must stay empty — mirrors [Messages.missingEnglish]. */
    fun missingEnglish(): Set<RedactionRule> =
        RedactionRule.entries.filterNot { en.containsKey(it) }.toSet()

    /** Rules with no Hindi string. Must stay empty for P0 (FR-R1). */
    fun missingHindi(): Set<RedactionRule> =
        RedactionRule.entries.filterNot { hi.containsKey(it) }.toSet()
}
