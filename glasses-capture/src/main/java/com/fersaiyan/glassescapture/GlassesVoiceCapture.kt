package com.fersaiyan.glassescapture

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Standalone, UI-free voice-capture pipeline for the glasses.
 *
 * Mirrors the vendor app's near-real-time voice flow, extracted from the vendor app:
 *
 *   1. User presses the mic / AI button on the glasses.
 *   2. Glasses emit a BLE notify (loadData[6] == 0x03, loadData[7] == 1).
 *   3. The glasses act as a Bluetooth SCO headset, so we open the phone mic over Bluetooth.
 *   4. Android [SpeechRecognizer] transcribes the live audio (near real-time; ends on silence).
 *   5. The final transcript is delivered to [onTranscript] — plug your LLM reasoning in there.
 *   6. Optionally, speak the LLM response back through the glasses via [GlassesTts].
 *
 * This class is deliberately decoupled from any UI. Wire it up from an Activity/Service:
 *
 * ```
 * val capture = GlassesVoiceCapture(context)
 * capture.onTranscript = { text ->
 *     val answer = myLlm.reason(text)   // your reasoning
 *     capture.speak(answer)             // optional voice reply
 * }
 * capture.start()
 * ...
 * capture.stop()
 * ```
 */
class GlassesVoiceCapture(
    private val appContext: Context,
    /** How long to wait for the user to begin speaking before giving up. */
    private val listenTimeoutMs: Long = 6_000L,
    /** Silence (ms) that ends an utterance. Lower = snappier, higher = fewer cut-offs. */
    private val endOfSpeechSilenceMs: Long = 3_000L,
) {
    /**
     * Called on a background coroutine with the final transcript when the user finishes speaking.
     * Plug your LLM reasoning here. Return normally; call [speak] if you want a spoken reply.
     */
    var onTranscript: (suspend (String) -> Unit)? = null

    /** Called when the mic button is pressed but nothing intelligible was heard. */
    var onNoSpeech: (() -> Unit)? = null

    /** Called when the glasses report end-of-voice-activation (notify 0x0a). Informational. */
    var onVoiceEnd: (() -> Unit)? = null

    /** Called on any error, with the SpeechRecognizer error code (see [SpeechRecognizer]). */
    var onError: ((Int) -> Unit)? = null

    /**
     * Called on a background coroutine with the captured photo when the glasses' photo button is
     * pressed. Null if the pull failed/timed out.
     */
    var onPhoto: (suspend (java.io.File?) -> Unit)? = null

    /** Progress while waiting for a glasses-button photo: (attempt, maxAttempts). */
    var onPhotoProgress: ((Int, Int) -> Unit)? = null

    private val tts = GlassesTts(appContext)
    private val photoCapture = GlassesPhotoCapture(appContext)
    private val photoBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val voiceBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var started = false

    /**
     * Debug hook: fired for EVERY notify frame from the glasses, with loadData[6] (the event id)
     * and the full byte array as a readable string. Use this to see exactly what a button sends.
     */
    var onRawNotify: ((eventId: Int, bytes: String) -> Unit)? = null

    private val notifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val load = response.loadData
            if (load.size <= 7) return
            val eventId = load[6].toInt()
            onRawNotify?.invoke(eventId, load.joinToString(",") { it.toInt().toString() })
            when (eventId) {
                // 0x02 == glasses AI-photo event: the photo button on the glasses was pressed and
                // the device has (or is about to have) the shot ready in its thumbnail buffer.
                // This matches the official flow, which pulls thumbnails right after this event.
                0x02 -> {
                    Log.i(TAG, "Glasses photo event (0x02) → pulling photo")
                    onPhotoButtonPressed()
                }
                // 0x03 == voice activation START (user began speaking on the glasses).
                0x03 -> {
                    Log.i(TAG, "Voice activation start → capturing from glasses mic")
                    onMicButtonPressed()
                }
                // 0x0a == voice activation END (user stopped). SpeechRecognizer's own
                // end-of-speech silence detection already resolves the transcript, so this is
                // informational; surfaced via onVoiceEnd for callers that want it.
                0x0a -> {
                    Log.i(TAG, "Voice activation end")
                    onVoiceEnd?.invoke()
                }
                // 0x01 fires around capture as well (button-down / mode change) but BEFORE the
                // image exists — pulling on it yields 0 bytes for tens of seconds. Log only.
                0x01 -> Log.i(TAG, "Capture-related event 0x01 (ignored; waiting for 0x02)")
            }
        }
    }

    /** Register the BLE notify listener and initialise TTS. Safe to call once. */
    fun start() {
        if (started) return
        started = true
        tts.init()
        LargeDataHandler.getInstance().addOutDeviceListener(LISTENER_KEY, notifyListener)
        Log.i(TAG, "GlassesVoiceCapture started")
    }

    /** Unregister and release resources. */
    fun stop() {
        if (!started) return
        started = false
        runCatching {
            LargeDataHandler.getInstance().removeOutDeviceListener(LISTENER_KEY)
        }
        tts.shutdown()
        Log.i(TAG, "GlassesVoiceCapture stopped")
    }

    /** Streams we muted to suppress the SpeechRecognizer's beeps; restored after recognition. */
    private val mutedStreams = mutableListOf<Int>()

    /**
     * Mute the streams Android's SpeechRecognizer beeps on (MUSIC, SYSTEM, NOTIFICATION) so the
     * built-in start/stop/error tones — which re-fire on internal restarts — are silent. We only
     * mute streams that were audible, and remember them so [unmuteRecognizerBeeps] restores exactly
     * those. The mic-cue beep we play ourselves is on STREAM_VOICE_CALL, so it is unaffected.
     */
    private fun muteRecognizerBeeps(am: AudioManager) {
        val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
        for (s in streams) {
            runCatching {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(s)
                }
            }
        }
    }

    /** Restore the streams muted by [muteRecognizerBeeps]. */
    private fun unmuteRecognizerBeeps(am: AudioManager) {
        for (s in mutedStreams) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        mutedStreams.clear()
    }

    /** Speak [text] back through the glasses (Bluetooth TTS route). */
    fun speak(text: String) = tts.speak(text)

    /** Stop any spoken reply immediately (e.g. the user interrupted with a new question). */
    fun stopSpeaking() = tts.stopSpeaking()

    /** True while a reply is being spoken aloud. */
    fun isSpeaking(): Boolean = tts.isSpeaking()

    /** Select the spoken language/accent (e.g. "en-US", "en-GB", "en-IN"). Null = system default. */
    fun setVoiceLanguage(tag: String?) = tts.setLanguageTag(tag)

    /** Select a specific voice by name (from [voicesForLanguage]). Null = engine default. */
    fun setVoiceName(name: String?) = tts.setVoiceName(name)

    /** Available voices (name → label) for [languageTag], or all if null. */
    fun voicesForLanguage(languageTag: String? = null): List<Pair<String, String>> =
        tts.availableVoices(languageTag)

    /** Language tags with at least one installed voice (e.g. "en-US"). */
    fun availableVoiceLanguages(): List<String> = tts.availableLanguageTags()

    private fun onPhotoButtonPressed() {
        val cb = onPhoto ?: return
        if (!photoBusy.compareAndSet(false, true)) {
            Log.i(TAG, "Photo pull already in progress; ignoring repeat notify")
            return
        }
        scope.launch {
            try {
                // The glasses already took the photo when the button was pressed. Pull THAT photo
                // (no second shutter) by polling the thumbnail buffer until the device finishes
                // writing it. This avoids the "double click" of re-capturing.
                val file = photoCapture.downloadLatest { attempt, max -> onPhotoProgress?.invoke(attempt, max) }
                cb(file)
            } finally {
                photoBusy.set(false)
            }
        }
    }

    private fun onMicButtonPressed() {
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "Mic pressed but glasses not connected; ignoring")
            return
        }
        if (!voiceBusy.compareAndSet(false, true)) {
            Log.i(TAG, "Voice capture already in progress; ignoring repeat notify")
            return
        }
        scope.launch {
            try {
                // Tell the glasses to stop their proprietary on-device AI audio stream first,
                // so the mic audio routes to the phone instead.
                runCatching {
                    LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ -> }
                }
                val transcript = transcribeFromBluetoothMic()
                if (transcript.isNullOrBlank()) {
                    onNoSpeech?.invoke()
                } else {
                    Log.i(TAG, "Transcript: $transcript")
                    onTranscript?.invoke(transcript)
                }
            } finally {
                voiceBusy.set(false)
            }
        }
    }

    /**
     * Opens the glasses' Bluetooth SCO mic and runs [SpeechRecognizer] on the live stream.
     * Returns the recognized text, or null on silence/error. Near-real-time: resolves as soon
     * as the user stops speaking.
     */
    private suspend fun transcribeFromBluetoothMic(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var recognizer: SpeechRecognizer? = null
            var timeoutJob: Job? = null
            var scoTimeoutJob: Job? = null
            var scoReceiver: android.content.BroadcastReceiver? = null
            var finished = false
            var heardSpeech = false
            var started = false

            fun cleanup() {
                runCatching { recognizer?.destroy() }
                recognizer = null
                runCatching { scoReceiver?.let { appContext.unregisterReceiver(it) } }
                scoReceiver = null
                unmuteRecognizerBeeps(audioManager) // restore streams muted during recognition
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = false
                        @Suppress("DEPRECATION")
                        audioManager.stopBluetoothSco()
                    }
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }

            fun finish(result: String?) {
                if (finished) return
                finished = true
                timeoutJob?.cancel(); timeoutJob = null
                scoTimeoutJob?.cancel(); scoTimeoutJob = null
                cleanup()
                if (cont.isActive) cont.resume(result?.trim()?.takeIf { it.isNotBlank() })
            }

            // Actually start recognition — only called once the SCO mic link is up.
            fun beginRecognition() {
                if (started || finished) return
                started = true

                // Android's SpeechRecognizer plays its own start/stop/error beeps on the MUSIC and
                // SYSTEM streams, and re-fires them on internal restarts — the source of the "lots of
                // beeps". Mute those streams for the duration of recognition; restored in cleanup().
                muteRecognizerBeeps(audioManager)

                // One short beep so the user knows to start talking.
                runCatching {
                    val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                    scope.launch { kotlinx.coroutines.delay(250); runCatching { tone.release() } }
                }

                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Force the mic source to the (SCO) voice-communication input, not the phone mic.
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra("android.speech.extra.AUDIO_SOURCE", android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechSilenceMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechSilenceMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        heardSpeech = true
                        timeoutJob?.cancel(); timeoutJob = null
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.i(TAG, "Recognition ended with error code=$error")
                        onError?.invoke(error)
                        finish(null)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(matches?.firstOrNull())
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                timeoutJob = scope.launch {
                    kotlinx.coroutines.delay(listenTimeoutMs)
                    if (!heardSpeech) finish(null)
                }
                recognizer?.startListening(intent)
            }

            // Put the audio system in communication mode so input can come over Bluetooth SCO.
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Android 12+: the reliable way to force input onto the glasses is to explicitly
                // select the Bluetooth SCO output as the communication device. startBluetoothSco()
                // is deprecated and often no-ops here, which is why the phone mic was being used.
                val btDevice = audioManager
                    .availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (btDevice != null) {
                    val ok = runCatching { audioManager.setCommunicationDevice(btDevice) }.getOrDefault(false)
                    Log.i(TAG, "setCommunicationDevice(BT_SCO)=$ok on '${btDevice.productName}'")
                    // The route is active synchronously here; give it a brief beat then start.
                    scoTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(if (ok) 350 else SCO_CONNECT_TIMEOUT_MS)
                        beginRecognition()
                    }
                } else {
                    Log.w(TAG, "No Bluetooth SCO communication device found; glasses not connected as a headset?")
                    // Nothing to route to — start anyway (will use whatever the system picks).
                    scoTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(300)
                        beginRecognition()
                    }
                }
                cont.invokeOnCancellation { finish(null) }
                return@suspendCancellableCoroutine
            }

            // Legacy (API < 31): wait for the SCO link to actually connect before starting.
            // Starting too early makes Android fall back to the built-in phone mic.
            scoReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        Log.i(TAG, "SCO connected → glasses mic active; starting recognition")
                        beginRecognition()
                    }
                }
            }
            runCatching {
                appContext.registerReceiver(
                    scoReceiver,
                    android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                )
            }

            runCatching {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }

            // Fallback: if SCO never reports connected (some devices don't broadcast reliably),
            // start recognition anyway after a short grace period.
            scoTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(SCO_CONNECT_TIMEOUT_MS)
                if (!started && !finished) {
                    Log.w(TAG, "SCO connect not confirmed in ${SCO_CONNECT_TIMEOUT_MS}ms; starting anyway")
                    beginRecognition()
                }
            }

            cont.invokeOnCancellation { finish(null) }
        }
    }

    companion object {
        private const val TAG = "GlassesVoiceCapture"
        // Arbitrary, stable key for addOutDeviceListener (matches the app's convention of 100).
        private const val LISTENER_KEY = 100
        // How long to wait for the Bluetooth SCO mic link before falling back to starting anyway.
        private const val SCO_CONNECT_TIMEOUT_MS = 2_500L
    }
}
