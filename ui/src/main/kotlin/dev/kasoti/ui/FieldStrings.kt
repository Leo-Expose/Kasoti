package dev.kasoti.ui

import dev.kasoti.i18n.Language

/**
 * UI chrome strings — the labels, buttons and step titles.
 *
 * Deliberately a *separate* catalogue from `dev.kasoti.i18n.Messages`.
 *
 * `Messages` is keyed by `FindingCode` and is a shared, `:core`-owned contract: those strings
 * are what a finding says about a document, they are asserted complete against the whole
 * `FindingCode` enum by `:core`'s own test, and adding one is a `:core` change. This catalogue
 * is the app's own furniture — "Retake photo", "Step 2 of 4", "Supervisor PIN" — and it belongs
 * with the module that renders it.
 *
 * The split matters most for the finding rows: those MUST come from `Messages.of(code, language)`
 * and never from here, because a code is the contract and a code's string is not (AGENTS.md §2).
 * [VerdictPresenter] is the only place that resolves them, and there is no way to configure it
 * to do otherwise.
 *
 * Hindi is P0 (FR-R1, SPEC §3 persona P1 is Hindi-first). Nepali is P1 and deliberately
 * absent: [missingHindi] exists so a Hindi gap is a test failure rather than a demo-day
 * surprise in front of a Hindi-speaking operator.
 */
object FieldStrings {

    private val en: Map<Key, String> = mapOf(
        Key.APP_NAME to "KASOTI",
        Key.LANGUAGE to "Language",

        Key.STEP_OF to "Step %1\$d of %2\$d",
        Key.STEP_DOCUMENT to "Show the document",
        Key.STEP_DOCUMENT_HINT to "Fill the frame. Hold steady.",
        Key.STEP_MRZ to "Machine-readable zone",
        Key.STEP_MRZ_HINT to "Two lines at the bottom. Hold still.",
        Key.STEP_MACRO to "Macro patches",
        Key.STEP_MACRO_HINT to "Place the clip over the photo, then the text.",
        Key.STEP_FACE to "Face",
        Key.STEP_FACE_HINT to "Look at the camera. One second.",

        Key.QUALITY_HOLD to "Hold steady",
        Key.QUALITY_GOOD to "Good",
        Key.QUALITY_CAPTURE to "Capture",

        Key.MACRO_PHOTO_ZONE to "Photo zone",
        Key.MACRO_TEXT_ZONE to "Text zone",
        Key.MACRO_SHARPNESS to "Sharpness",
        Key.MACRO_USE_CLIP to "Use the clip shroud for both patches",
        Key.MACRO_FOCUS_LOCKED to "Focus locked",
        Key.MACRO_FOCUS_TAP to "Tap to lock focus",

        Key.QUAD_AUTO to "Auto",
        Key.QUAD_MANUAL to "Adjust corners",
        Key.QUAD_ACCEPT to "Accept crop",
        Key.QUAD_RECAPTURE to "Recapture",

        Key.VERDICT_GREEN to "CLEARED",
        Key.VERDICT_AMBER to "SECONDARY CHECK",
        Key.VERDICT_RED to "DO NOT CLEAR",
        Key.VERDICT_GREY to "RETAKE",

        Key.ACTION_NEXT to "Next person",
        Key.ACTION_RETAKE to "Retake",
        Key.ACTION_DETAILS to "Why?",
        Key.ACTION_SUPERVISOR to "Call supervisor",
        Key.ACTION_REPEAT_VOICE to "Read again",

        Key.DEMO_WATERMARK to "DEMO",
        Key.DEMO_BANNER to "DEMO MODE — not a real screening",
        Key.DEMO_LOAD to "Load demo case",
        Key.DEMO_RESET to "Reset demo",

        Key.TRUST_ENROL to "Enrol in trust lane",
        Key.TRUST_PIN to "Supervisor PIN",
        Key.TRUST_FAST to "Trust lane",
        Key.TRUST_REVOKE to "Revoke",
        Key.TRUST_RECHECK to "Random re-check",

        Key.PERMISSION_TITLE to "Camera and storage access",
        Key.PERMISSION_BODY to "KASOTI needs the camera. It never uses the network.",
        Key.PERMISSION_GRANT to "Grant",
        Key.PERMISSION_DENIED to "Screening needs the camera. No camera, no screening.",

        Key.NO_EVIDENCE_TITLE to "Nothing to look at",
        Key.NO_EVIDENCE_BODY to "This capture could not be read. It is not a result about the document.",
    )

    private val hi: Map<Key, String> = mapOf(
        Key.APP_NAME to "कसोटी",
        Key.LANGUAGE to "भाषा",

        Key.STEP_OF to "चरण %1\$d / %2\$d",
        Key.STEP_DOCUMENT to "दस्तावेज़ दिखाएँ",
        Key.STEP_DOCUMENT_HINT to "फ़्रेम भरें। स्थिर रखें।",
        Key.STEP_MRZ to "मशीन-पठन क्षेत्र",
        Key.STEP_MRZ_HINT to "नीचे की दो पंक्तियाँ। स्थिर रखें।",
        Key.STEP_MACRO to "मैक्रो पैच",
        Key.STEP_MACRO_HINT to "क्लिप चित्र पर रखें, फिर टेक्स्ट पर।",
        Key.STEP_FACE to "चेहरा",
        Key.STEP_FACE_HINT to "कैमरे की ओर देखें। एक सेकंड।",

        Key.QUALITY_HOLD to "स्थिर रखें",
        Key.QUALITY_GOOD to "ठीक है",
        Key.QUALITY_CAPTURE to "फोटो लें",

        Key.MACRO_PHOTO_ZONE to "चित्र क्षेत्र",
        Key.MACRO_TEXT_ZONE to "टेक्स्ट क्षेत्र",
        Key.MACRO_SHARPNESS to "तीव्रता",
        Key.MACRO_USE_CLIP to "दोनों पैच के लिए क्लिप शेल्टर लगाएँ",
        Key.MACRO_FOCUS_LOCKED to "फोकस लॉक है",
        Key.MACRO_FOCUS_TAP to "फोकस लॉक करने के लिए टैप करें",

        Key.QUAD_AUTO to "स्वतः",
        Key.QUAD_MANUAL to "कोने समायोजित करें",
        Key.QUAD_ACCEPT to "क्रॉप स्वीकारें",
        Key.QUAD_RECAPTURE to "दोबारा फोटो",

        Key.VERDICT_GREEN to "पास",
        Key.VERDICT_AMBER to "दूसरी जाँच",
        Key.VERDICT_RED to "जारी रखें नहीं",
        Key.VERDICT_GREY to "दोबारा फोटो",

        Key.ACTION_NEXT to "अगला व्यक्ति",
        Key.ACTION_RETAKE to "दोबारा फोटो",
        Key.ACTION_DETAILS to "कारण?",
        Key.ACTION_SUPERVISOR to "पर्यवेक्षक को बुलाएँ",
        Key.ACTION_REPEAT_VOICE to "फिर सुनाएँ",

        Key.DEMO_WATERMARK to "डेमो",
        Key.DEMO_BANNER to "डेमो मोड — यह वास्तविक जाँच नहीं है",
        Key.DEMO_LOAD to "डेमो केस लोड करें",
        Key.DEMO_RESET to "डेमो रीसेट",

        Key.TRUST_ENROL to "विश्वसनीय लेन में दर्ज करें",
        Key.TRUST_PIN to "पर्यवेक्षक पिन",
        Key.TRUST_FAST to "विश्वसनीय लेन",
        Key.TRUST_REVOKE to "रद्द करें",
        Key.TRUST_RECHECK to "यादृच्छिक पुनः-जाँच",

        Key.PERMISSION_TITLE to "कैमरा और संग्रहण अनुमति",
        Key.PERMISSION_BODY to "कसोटी को कैमरा चाहिए। यह कभी नेटवर्क का उपयोग नहीं करती।",
        Key.PERMISSION_GRANT to "अनुमति दें",
        Key.PERMISSION_DENIED to "जाँच के लिए कैमरा आवश्यक है। कैमरा नहीं, जाँच नहीं।",

        Key.NO_EVIDENCE_TITLE to "देखने को कुछ नहीं",
        Key.NO_EVIDENCE_BODY to "इस तस्वीर को पढ़ा नहीं जा सका। यह दस्तावेज़ के बारे में कोई नतीजा नहीं है।",
    )

    /**
     * @return the formatted string, falling back to English and then to the key name. Never
     *   blank and never a raw finding message (AGENTS.md §2).
     */
    fun of(key: Key, language: Language, vararg args: Any): String {
        val template = when (language) {
            Language.ENGLISH -> en[key]
            Language.HINDI -> hi[key] ?: en[key]
        } ?: key.name
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    /** Keys with no Hindi string. FR-R1 makes this a build failure, not a TODO. */
    fun missingHindi(): Set<Key> = Key.entries.filterNot { hi.containsKey(it) }.toSet()

    /** Keys with no English string. Must stay empty. */
    fun missingEnglish(): Set<Key> = Key.entries.filterNot { en.containsKey(it) }.toSet()

    enum class Key {
        APP_NAME, LANGUAGE,
        STEP_OF, STEP_DOCUMENT, STEP_DOCUMENT_HINT, STEP_MRZ, STEP_MRZ_HINT,
        STEP_MACRO, STEP_MACRO_HINT, STEP_FACE, STEP_FACE_HINT,
        QUALITY_HOLD, QUALITY_GOOD, QUALITY_CAPTURE,
        MACRO_PHOTO_ZONE, MACRO_TEXT_ZONE, MACRO_SHARPNESS, MACRO_USE_CLIP,
        MACRO_FOCUS_LOCKED, MACRO_FOCUS_TAP,
        QUAD_AUTO, QUAD_MANUAL, QUAD_ACCEPT, QUAD_RECAPTURE,
        VERDICT_GREEN, VERDICT_AMBER, VERDICT_RED, VERDICT_GREY,
        ACTION_NEXT, ACTION_RETAKE, ACTION_DETAILS, ACTION_SUPERVISOR, ACTION_REPEAT_VOICE,
        DEMO_WATERMARK, DEMO_BANNER, DEMO_LOAD, DEMO_RESET,
        TRUST_ENROL, TRUST_PIN, TRUST_FAST, TRUST_REVOKE, TRUST_RECHECK,
        PERMISSION_TITLE, PERMISSION_BODY, PERMISSION_GRANT, PERMISSION_DENIED,
        NO_EVIDENCE_TITLE, NO_EVIDENCE_BODY,
    }
}
