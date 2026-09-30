package dev.kasoti.android.view

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.kasoti.android.field.FieldLog
import dev.kasoti.android.field.FieldLogEntry
import dev.kasoti.i18n.Language
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice verdict readout (FR-R1, DESIGN.md §3 "TTS: Android TTS (Hin/Eng)").
 *
 * ## Why it speaks, and why it is short
 *
 * SPEC §3 persona P1 works one-handed, in sun, with gloves, and a jawan's eyes will be on the
 * person in front of them rather than on a screen. The readout is the one channel that does not
 * require looking at anything. That is the whole feature.
 *
 * So the text is *one line* — the worst finding, or the verdict word when there is none — and
 * never the finding table, the layer states or the policy fingerprint. A readout that recites
 * four codes is slower than useless at a counter, and an officer who stops listening to it
 * stops hearing the one sentence that matters.
 *
 * ## The GREY rule, enforced here too
 *
 * `dev.kasoti.ui.AccusationLexicon` checks the assembled screen in `VerdictPresenter`. This
 * class checks the text it is about to *speak*, because TTS is the channel most likely to be
 * mis-heard: a retake instruction delivered in a flat, alarming tone is an accusation with the
 * screen turned away. A GREY is spoken with a neutral intent and a deliberate pause, and if the
 * text somehow contains an accusing word it is refused rather than spoken.
 */
class VoiceReadout(
    private val context: Context,
    private val log: FieldLog = FieldLog.NOOP,
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var language: Language = Language.ENGLISH

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.SYSTEM, detail = "tts init failed"))
            return
        }
        tts = TextToSpeech(context.applicationContext, this)
        ready.set(true)
        applyLanguage(language)
    }

    /** Start the engine. Safe to call before the activity is resumed. */
    fun start() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext, this)
    }

    fun applyLanguage(language: Language) {
        this.language = language
        if (!ready.get()) return
        val engine = tts ?: return
        val locale = if (language == Language.HINDI) Locale("hi", "IN") else Locale("UK", "en")
        // The return code is deliberately not treated as fatal. A handset with no Hindi voice
        // installed will fall back to its default, and a Hindi officer hearing the English
        // string is a much better outcome than a silent app — the string is the same sentence.
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.SYSTEM, detail = "tts locale $locale unavailable"))
        }
    }

    /**
     * Speak one line.
     *
     * @param text the operator-facing line, already localised.
     * @param isRetake `true` for a GREY, which changes the delivery rather than the words.
     * @return `false` when nothing was spoken — unavailable engine, empty text, or a text that
     *   failed the accusation check. Never throws; a silent readout is a degraded experience,
     *   not a failure.
     */
    fun speak(text: String, isRetake: Boolean): Boolean {
        val engine = tts ?: return false
        if (!ready.get()) return false
        if (text.isBlank()) return false

        if (isRetake && dev.kasoti.ui.AccusationLexicon.violations(text).isNotEmpty()) {
            // Belt to `VerdictPresenter`'s braces. The text was assembled somewhere this module
            // cannot see, and speaking it is the last chance to stop an accusation.
            log.write(FieldLogEntry.of(FieldLogEntry.Kind.SYSTEM, detail = "tts refused: retake text reads as an accusation"))
            return false
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit

            @Deprecated("Superseded by onError(String, int)", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) = Unit

            override fun onError(utteranceId: String?, errorCode: Int) {
                log.write(FieldLogEntry.of(FieldLogEntry.Kind.SYSTEM, detail = "tts error $errorCode"))
            }
        })

        val utteranceId = if (isRetake) UTTERANCE_RETAKE else UTTERANCE_VERDICT
        return if (isRetake) {
            // A short pause first. The two sentences a jawan must tell apart — "you are clear"
            // and "take another photo" — are acoustically identical without it, and the pause
            // is the cheapest possible disambiguation.
            engine.speak(PAUSE_PREFIX, TextToSpeech.QUEUE_FLUSH, null, "$utteranceId.pause")
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
        } else {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        }
    }

    fun stop() {
        tts?.stop()
    }

    /** Release the engine. Required, or a shift's worth of screenings leaks one engine each. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private companion object {
        const val UTTERANCE_VERDICT = "kasoti-verdict"
        const val UTTERANCE_RETAKE = "kasoti-retake"

        /** A silent utterance, used as a queue-flushing pause. */
        const val PAUSE_PREFIX = " "
    }
}
