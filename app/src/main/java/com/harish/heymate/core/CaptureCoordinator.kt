package com.harish.heymate.core

import android.content.Context
import android.util.Log
import com.fersaiyan.glassescapture.GlassesVoiceCapture
import com.harish.heymate.actions.LocalActions
import com.harish.heymate.agent.AgentClient
import com.harish.heymate.agent.AgentRequest
import com.harish.heymate.agent.GeminiAgentClient
import com.harish.heymate.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The heart of HeyMate: wires glasses events into the automation pipeline.
 *
 *   glasses mic button  → live transcription → Gemini agent → TTS + notification + feed
 *   glasses photo button → BLE photo pull → feed (+ optionally attached to next agent call)
 */
object CaptureCoordinator {

    private const val TAG = "CaptureCoordinator"

    /** Fixed vision prompt used when a photo is auto-described on arrival. */
    private const val DESCRIBE_PROMPT =
        "Describe what you see in this photo concisely and helpfully, as if telling the person wearing the glasses. If there is readable text, read out the key parts."
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appContext: Context
    private lateinit var capture: GlassesVoiceCapture
    private lateinit var prefs: Prefs
    private lateinit var agent: AgentClient

    private val _lastPhotoPath = MutableStateFlow<String?>(null)
    val lastPhotoPath: StateFlow<String?> = _lastPhotoPath.asStateFlow()

    /**
     * Path of a freshly captured photo "armed" to ride along with the NEXT voice query.
     * Set when the user captures a photo; consumed (and cleared) by the next transcript so it
     * attaches exactly once. Exposed so the UI can show a "photo will be sent" hint.
     */
    private val _pendingPhotoPath = MutableStateFlow<String?>(null)
    val pendingPhotoPath: StateFlow<String?> = _pendingPhotoPath.asStateFlow()

    private val _lastTranscript = MutableStateFlow<String?>(null)
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()

    private val _lastReply = MutableStateFlow<String?>(null)
    val lastReply: StateFlow<String?> = _lastReply.asStateFlow()

    /** Human-readable live status ("listening…", "thinking…", "pulling photo 2/8"), or null when idle. */
    private val _liveStatus = MutableStateFlow<String?>(null)
    val liveStatus: StateFlow<String?> = _liveStatus.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        agent = GeminiAgentClient(prefs)

        capture = GlassesVoiceCapture(appContext).apply {
            onTranscript = { text -> handleTranscript(text) }
            onNoSpeech = {
                _liveStatus.value = null
                EventFeed.status("Mic activated — no speech detected")
            }
            onError = { code ->
                _liveStatus.value = null
                EventFeed.error("Speech recognition error (code $code)")
            }
            onVoiceEnd = {
                EventFeed.voiceEnd()
            }
            onPhoto = { file ->
                if (file != null) {
                    _lastPhotoPath.value = file.absolutePath
                    _pendingPhotoPath.value = file.absolutePath
                    EventFeed.photo(file.absolutePath)
                    // Don't auto-describe: arm the photo to ride along with the next voice query.
                    _liveStatus.value = null
                } else {
                    _liveStatus.value = null
                    EventFeed.error("Photo pull failed")
                }
            }
            onPhotoProgress = { attempt, max ->
                _liveStatus.value = "Receiving photo… ($attempt/$max)"
            }
            onRawNotify = { eventId, _ ->
                if (eventId == 0x03) {
                    // User pressed the mic again — interrupt any reply still being spoken so it
                    // doesn't talk over the new question.
                    capture.stopSpeaking()
                    _liveStatus.value = "Listening…"
                    EventFeed.voiceStart()
                }
            }
        }
        capture.start()
        // Apply any saved voice/language selection once TTS is up.
        scope.launch { applyVoiceSettings() }
        Log.i(TAG, "Capture pipeline started")
    }

    /** Read saved voice + language from prefs and apply them to the TTS engine. */
    suspend fun applyVoiceSettings() {
        val lang = prefs.voiceLanguage.first().takeIf { it.isNotBlank() }
        val voice = prefs.voiceName.first().takeIf { it.isNotBlank() }
        capture.setVoiceLanguage(lang)
        capture.setVoiceName(voice)
    }

    /** Voices (name → label) installed for [languageTag], or all if null. Empty if TTS not ready. */
    fun availableVoices(languageTag: String? = null): List<Pair<String, String>> =
        if (::capture.isInitialized) capture.voicesForLanguage(languageTag) else emptyList()

    /** Language tags with at least one installed voice. Empty if TTS not ready. */
    fun availableVoiceLanguages(): List<String> =
        if (::capture.isInitialized) capture.availableVoiceLanguages() else emptyList()

    /** Speak a short sample so the user can preview the current voice selection. */
    fun previewVoice() {
        if (::capture.isInitialized) capture.speak("This is how HeyMate will sound.")
    }

    /** Whether a reply is currently being spoken aloud, for showing a Stop control. */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Stop the spoken reply immediately (in-app Stop button / interrupt). */
    fun stopSpeaking() {
        if (::capture.isInitialized) capture.stopSpeaking()
        _speaking.value = false
    }

    private suspend fun handleTranscript(text: String) {
        _lastTranscript.value = text
        _liveStatus.value = "Thinking…"
        EventFeed.transcript(text)

        // A freshly captured photo is armed to ride along with this voice query. Consume it once,
        // then clear so it doesn't stick to later questions. Otherwise fall back to the Settings
        // toggle that attaches the most recent photo to every voice query.
        val pending = _pendingPhotoPath.value
        val photoPath = when {
            pending != null -> { _pendingPhotoPath.value = null; pending }
            prefs.sendPhotosToAgent.first() -> _lastPhotoPath.value
            else -> null
        }
        val request = AgentRequest(transcript = text, photoPath = photoPath)

        val result = agent.reason(request)
        _liveStatus.value = null
        dispatchReply(result)
    }

    /**
     * Auto-describe a freshly captured photo: send THAT photo (not the stale "last photo") to
     * Gemini with a fixed vision prompt, then speak/notify the description. Triggered the moment
     * a glasses-button photo arrives — no voice query required.
     */
    private fun describePhoto(photoPath: String) {
        scope.launch {
            _liveStatus.value = "Looking…"
            val request = AgentRequest(transcript = DESCRIBE_PROMPT, photoPath = photoPath)
            val result = agent.reason(request)
            _liveStatus.value = null
            dispatchReply(result)
        }
    }

    /** Shared reply handling: surface to UI feed, optionally speak and notify. */
    private fun dispatchReply(result: Result<com.harish.heymate.agent.AgentReply>) {
        result.fold(
            onSuccess = { reply ->
                _lastReply.value = reply.text
                EventFeed.agentReply(reply.text)
                scope.launch {
                    if (prefs.speakReplies.first()) {
                        _speaking.value = true
                        capture.speak(reply.text)
                        // Clear the speaking flag once playback finishes (poll; TTS has no simple
                        // completion callback wired here). Keeps the Stop control accurate.
                        scope.launch {
                            kotlinx.coroutines.delay(400) // let playback actually begin
                            while (capture.isSpeaking()) kotlinx.coroutines.delay(300)
                            _speaking.value = false
                        }
                    }
                    if (prefs.notifyReplies.first()) LocalActions.notify(appContext, "HeyMate", reply.text)
                }
            },
            onFailure = { e ->
                val msg = e.message ?: "Agent call failed"
                EventFeed.error(msg)
            },
        )
    }

    /** Manually trigger a photo capture from the phone (same flow the glasses button uses). */
    fun capturePhotoFromPhone() {
        scope.launch {
            _liveStatus.value = "Capturing photo…"
            val file = com.fersaiyan.glassescapture.GlassesPhotoCapture(appContext).captureNow()
            _liveStatus.value = null
            if (file != null) {
                _lastPhotoPath.value = file.absolutePath
                EventFeed.photo(file.absolutePath)
                describePhoto(file.absolutePath)
            } else {
                EventFeed.error("Photo capture failed")
            }
        }
    }

    /**
     * Capture a photo and arm it to ride along with the NEXT voice query, instead of
     * auto-describing it. The user captures, then speaks their question — the photo attaches once.
     */
    fun capturePhotoForMessage() {
        scope.launch {
            _liveStatus.value = "Capturing photo…"
            val file = com.fersaiyan.glassescapture.GlassesPhotoCapture(appContext).captureNow()
            _liveStatus.value = null
            if (file != null) {
                _lastPhotoPath.value = file.absolutePath
                _pendingPhotoPath.value = file.absolutePath
                EventFeed.photo(file.absolutePath)
            } else {
                EventFeed.error("Photo capture failed")
            }
        }
    }
}
