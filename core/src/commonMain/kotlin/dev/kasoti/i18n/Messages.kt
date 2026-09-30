package dev.kasoti.i18n

import dev.kasoti.fusion.FindingCode

/** Shipped languages. Nepali is P1 (SPEC §9) and intentionally absent until the strings land. */
enum class Language(val code: String) {
    ENGLISH("en"),
    HINDI("hi"),
    ;

    companion object {
        fun from(code: String): Language =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/**
 * Resolves a finding code to a localised operator-facing line (FR-R1).
 *
 * The point of this type is the *test*, not the lookup: [missing] must be empty, which is
 * asserted against the whole [FindingCode] enum. That turns "we forgot a Hindi string" from
 * a demo-day embarrassment into a build failure.
 */
object Messages {

    private val en: Map<FindingCode, String> = mapOf(
        FindingCode.R_MATH_01 to "Machine-readable zone check digit failed",
        FindingCode.R_MATH_02 to "Document is expired or its dates are impossible",
        FindingCode.R_QR_01 to "QR signature is not valid",
        FindingCode.R_QR_02 to "QR data does not match the printed document",
        FindingCode.R_CHIP_01 to "Chip authentication failed",
        FindingCode.R_FACE_01 to "Face does not match the document photo",
        FindingCode.R_ALIAS_01 to "Possible alias: earlier crossing under different details",
        FindingCode.R_TRAV_01 to "Impossible travel between posts",
        FindingCode.R_PROC_01 to "Document appears to be a screen, not paper",
        FindingCode.R_PROC_02 to "Print process differs between photo and text zones",

        FindingCode.A_PROC_01 to "Print process looks unusual for this card type",
        FindingCode.A_FACE_01 to "Face match is inconclusive",
        FindingCode.A_LIVE_01 to "Liveness check was weak",
        FindingCode.A_QR_01 to "Unsigned QR data is inconsistent",
        FindingCode.A_FAC_01 to "Possible facilitator pattern",
        FindingCode.A_WL_01 to "Possible watchlist match",
        FindingCode.A_WORN_01 to "Document is too worn to judge",
        FindingCode.A_GREY3 to "Repeated retakes detected",
        FindingCode.A_VIZ_01 to "Printed details differ from the machine-readable zone",
        FindingCode.A_MISSING_LAYER to "A required check could not be completed",

        FindingCode.G_BLUR to "Image is blurry",
        FindingCode.G_GLARE to "Too much glare",
        FindingCode.G_DARK to "Too dark",
        FindingCode.G_POSE to "Hold the camera straight",
        FindingCode.G_OCCLUDE to "Something is covering the document",
        FindingCode.G_OCRLOW to "Text could not be read clearly",
        FindingCode.G_FOCUS to "Clip is out of focus",
        FindingCode.G_NOCLIP to "Macro clip is required for this document",
        FindingCode.G_NOEVIDENCE to "Nothing could be read from this capture",

        FindingCode.M_OK to "Math checks passed",
        FindingCode.Q_SIG_OK to "QR signature verified",
        FindingCode.CHIP_OK to "Chip verified",
        FindingCode.FACE_OK to "Face matches",
        FindingCode.DIARY_CLEAR to "No diary conflicts",
        FindingCode.MACRO_OK to "Print process consistent",
        FindingCode.TRUST_OK to "Trusted lane",
        FindingCode.R_PLAIN_TEXT to "No document found in the image",

        FindingCode.SYS_MODEL_MISMATCH to "Face model version does not match",
        FindingCode.SYS_KEYS_STALE to "Signature keys need rotation",
        FindingCode.SYS_THRESHOLDS_EDITED to "Threshold file was modified",
        FindingCode.SYS_CAPTURE_FAILED to "Camera could not capture",
        FindingCode.SYS_UNSUPPORTED_TRACK to "Document type not supported",
    )

    private val hi: Map<FindingCode, String> = mapOf(
        FindingCode.R_MATH_01 to "मशीन-पठन क्षेत्र की जाँच अंक विफल",
        FindingCode.R_MATH_02 to "दस्तावेज़ की तारीख़ समाप्त या असंभव है",
        FindingCode.R_QR_01 to "QR हस्ताक्षर मान्य नहीं है",
        FindingCode.R_QR_02 to "QR डेटा मुद्रित दस्तावेज़ से मेल नहीं खाता",
        FindingCode.R_CHIP_01 to "चिप प्रमाणीकरण विफल",
        FindingCode.R_FACE_01 to "चेहरा दस्तावेज़ चित्र से मेल नहीं खाता",
        FindingCode.R_ALIAS_01 to "संभावित उपनाम: पहले अलग विवरण से प्रवेश",
        FindingCode.R_TRAV_01 to "चौकियों के बीच असंभव यात्रा",
        FindingCode.R_PROC_01 to "दस्तावेज़ कागज़ नहीं, स्क्रीन दिखता है",
        FindingCode.R_PROC_02 to "चित्र और टेक्स्ट क्षेत्र की छपाई भिन्न है",

        FindingCode.A_PROC_01 to "इस कार्ड के लिए छपाई असामान्य लगती है",
        FindingCode.A_FACE_01 to "चेहरे का मिलान अनिर्णयात्मक है",
        FindingCode.A_LIVE_01 to "जीवितता जाँच कमज़ोर थी",
        FindingCode.A_QR_01 to "बिना हस्ताक्षरित QR डेटा असंगत है",
        FindingCode.A_FAC_01 to "संभावित दलाल/सहायक पैटर्न",
        FindingCode.A_WL_01 to "संभावित निगरानी सूची मिलान",
        FindingCode.A_WORN_01 to "दस्तावेज़ बहुत घिसा है, निर्णय संभव नहीं",
        FindingCode.A_GREY3 to "बार-बार पुनः-फोटो लिया गया",
        FindingCode.A_VIZ_01 to "मुद्रित विवरण मशीन-पठन क्षेत्र से भिन्न है",
        FindingCode.A_MISSING_LAYER to "आवश्यक जाँच पूरी नहीं हो सकी",

        FindingCode.G_BLUR to "चित्र धुँधला है",
        FindingCode.G_GLARE to "अधिक चमक है",
        FindingCode.G_DARK to "बहुत अँधेरा है",
        FindingCode.G_POSE to "कैमरा सीधा रखें",
        FindingCode.G_OCCLUDE to "दस्तावेज़ का कुछ भाग ढका है",
        FindingCode.G_OCRLOW to "पाठ स्पष्ट नहीं पढ़ा जा सका",
        FindingCode.G_FOCUS to "क्लिप फोकस से बाहर है",
        FindingCode.G_NOCLIP to "इस दस्तावेज़ के लिए मैक्रो क्लिप आवश्यक है",
        FindingCode.G_NOEVIDENCE to "इस तस्वीर से कुछ भी पढ़ा नहीं जा सका",

        FindingCode.M_OK to "गणित जाँच सफल",
        FindingCode.Q_SIG_OK to "QR हस्ताक्षर सत्यापित",
        FindingCode.CHIP_OK to "चिप सत्यापित",
        FindingCode.FACE_OK to "चेहरा मेल खाता है",
        FindingCode.DIARY_CLEAR to "डायरी में कोई टकराव नहीं",
        FindingCode.MACRO_OK to "छपाई प्रक्रिया संगत है",
        FindingCode.TRUST_OK to "विश्वसनीय लेन",
        FindingCode.R_PLAIN_TEXT to "चित्र में कोई दस्तावेज़ नहीं मिला",

        FindingCode.SYS_MODEL_MISMATCH to "चेहरा मॉडल संस्करण मेल नहीं खाता",
        FindingCode.SYS_KEYS_STALE to "हस्ताक्षर कुंजियों का चक्रण आवश्यक",
        FindingCode.SYS_THRESHOLDS_EDITED to "थ्रेशहोल्ड फ़ाइल बदली गई",
        FindingCode.SYS_CAPTURE_FAILED to "कैमरा से चित्र नहीं लिया जा सका",
        FindingCode.SYS_UNSUPPORTED_TRACK to "दस्तावेज़ प्रकार समर्थित नहीं",
    )

    /** @return the localised line, or the code itself when untranslated (never blank). */
    fun of(code: FindingCode, language: Language): String =
        when (language) {
            Language.ENGLISH -> en[code] ?: code.name
            Language.HINDI -> hi[code] ?: en[code] ?: code.name
        }

    /** Codes with no English string. Must stay empty. */
    fun missingEnglish(): Set<FindingCode> = FindingCode.entries.filterNot { en.containsKey(it) }.toSet()

    /** Codes with no Hindi string. Must stay empty for P0 (FR-R1). */
    fun missingHindi(): Set<FindingCode> = FindingCode.entries.filterNot { hi.containsKey(it) }.toSet()
}
