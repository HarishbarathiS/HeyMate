package com.fersaiyan.glassescapture

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android [TextToSpeech] used to speak LLM responses back through the
 * glasses' Bluetooth audio route. Extracted from the vendor app's speak() helper.
 *
 * Supports selecting a specific voice (e.g. male/female) and language/accent. The desired
 * voice/locale can be set before or after init; they are applied as soon as the engine is ready.
 */
class GlassesTts(private val appContext: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    /** Name of the desired [Voice] (from [availableVoices]), or null for the engine default. */
    private var desiredVoiceName: String? = null
    /** Desired language tag (e.g. "en-US", "en-GB", "en-IN"), or null for the system default. */
    private var desiredLocaleTag: String? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoiceAndLocale()
                // Flush anything queued before init finished.
                pending.forEach { speak(it) }
                pending.clear()
            } else {
                Log.w(TAG, "TextToSpeech init failed: $status")
            }
        }
    }

    /** Set the language/accent (e.g. "en-US"). Applied immediately if ready. Null = system default. */
    fun setLanguageTag(tag: String?) {
        desiredLocaleTag = tag?.takeIf { it.isNotBlank() }
        if (ready) applyVoiceAndLocale()
    }

    /** Set the voice by its [Voice.getName]. Applied immediately if ready. Null = engine default. */
    fun setVoiceName(name: String?) {
        desiredVoiceName = name?.takeIf { it.isNotBlank() }
        if (ready) applyVoiceAndLocale()
    }

    private fun applyVoiceAndLocale() {
        val engine = tts ?: return
        val locale = desiredLocaleTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        runCatching { engine.language = locale }

        val voiceName = desiredVoiceName
        if (voiceName != null) {
            val match = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == voiceName }
            if (match != null) {
                runCatching { engine.voice = match }
            } else {
                Log.w(TAG, "Requested voice '$voiceName' not available; using default")
            }
        }
    }

    /**
     * Voices installed for [languageTag] (or all voices if null), as (name, label) pairs.
     * The label is a human-friendly hint (locale + quality). Only usable, non-network voices.
     */
    fun availableVoices(languageTag: String? = null): List<Pair<String, String>> {
        val engine = tts ?: return emptyList()
        val voices = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        val filterTag = languageTag?.let { Locale.forLanguageTag(it) }
        return voices
            .asSequence()
            .filter { !it.isNetworkConnectionRequired }
            .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .filter { filterTag == null || it.locale.language == filterTag.language }
            .sortedBy { it.name }
            .map { it.name to voiceLabel(it) }
            .toList()
    }

    /** Language tags for which at least one voice is installed (e.g. "en-US", "en-GB"). */
    fun availableLanguageTags(): List<String> {
        val engine = tts ?: return emptyList()
        val voices = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        return voices
            .asSequence()
            .filter { !it.isNetworkConnectionRequired }
            .map { it.locale.toLanguageTag() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun voiceLabel(v: Voice): String {
        val quality = if (v.quality >= Voice.QUALITY_HIGH) "HD" else null
        return listOfNotNull(v.locale.toLanguageTag(), quality).joinToString(" · ")
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || !ready) {
            pending.add(text)
            return
        }
        val id = "utt_${text.hashCode()}"
        val bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, id)
    }

    /** Stop any in-progress or queued speech immediately (e.g. when the user interrupts). */
    fun stopSpeaking() {
        pending.clear()
        runCatching { tts?.stop() }
    }

    /** True while the engine is actively speaking. */
    fun isSpeaking(): Boolean = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        pending.clear()
    }

    companion object {
        private const val TAG = "GlassesTts"
    }
}
