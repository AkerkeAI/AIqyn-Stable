package com.aiqyn.safestudio.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.regex.Pattern
import com.aiqyn.safestudio.data.UserPreferencesRepository
import com.aiqyn.safestudio.data.UserState
import com.aiqyn.safestudio.data.ContextReminder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import com.aiqyn.safestudio.service.ContextRelevanceEngine

/**
 * Foreground microphone service that runs TensorFlow Lite YAMNet inference.
 * Prioritizes environmental danger sound detection (Sirens, Barking, Alarms, etc.).
 * Includes a lightweight, non-blocking Name Keyword Spotter using phonetic rhythm analysis.
 */
class AudioMonitoringService : Service(), TextToSpeech.OnInitListener {

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    private val alertCooldownUntil = mutableMapOf<DetectedSoundKind, Long>()
    private val lastAlertScore = mutableMapOf<DetectedSoundKind, Float>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Name Recognition state
    private var nameAlertCooldown = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false
    private var contextAlertCooldown = 0L

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        tts = TextToSpeech(this, this)
        _isRunning.value = true
        
        val prefs = UserPreferencesRepository(this)
        updateTtsLanguage(prefs.getUserState().language)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.0f)
            ttsReady = true
            val prefs = UserPreferencesRepository(this)
            updateTtsLanguage(prefs.getUserState().language)
        }
    }

    private fun updateTtsLanguage(language: com.aiqyn.safestudio.data.Language): Boolean {
        if (!ttsReady) return false
        val targetLocales = when (language) {
            com.aiqyn.safestudio.data.Language.ENGLISH -> listOf(Locale.US, Locale.UK, Locale.ENGLISH)
            com.aiqyn.safestudio.data.Language.RUSSIAN -> listOf(Locale("ru", "RU"), Locale("ru"))
            com.aiqyn.safestudio.data.Language.KAZAKH -> listOf(Locale("kk", "KZ"), Locale("kk"))
            com.aiqyn.safestudio.data.Language.TURKISH -> listOf(Locale("tr", "TR"), Locale("tr"))
            com.aiqyn.safestudio.data.Language.SPANISH -> listOf(Locale("es", "ES"), Locale("es"))
            com.aiqyn.safestudio.data.Language.ARABIC -> listOf(Locale("ar", "SA"), Locale("ar"))
            com.aiqyn.safestudio.data.Language.CHINESE -> listOf(Locale.SIMPLIFIED_CHINESE, Locale("zh", "CN"), Locale.CHINESE)
            com.aiqyn.safestudio.data.Language.GERMAN -> listOf(Locale.GERMANY, Locale.GERMAN)
        }
        for (locale in targetLocales) {
            val availability = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                if (tts?.setLanguage(locale) != TextToSpeech.LANG_NOT_SUPPORTED) {
                    selectBestVoice(locale)
                    return true
                }
            }
        }
        return false
    }

    private fun selectBestVoice(locale: Locale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val voices = tts?.voices ?: return
            val bestVoice = voices.filter { it.locale.language == locale.language }
                .sortedWith(compareByDescending { 
                    val isLocal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) !it.isNetworkConnectionRequired else true
                    (if (isLocal) 100 else 0) + it.quality 
                }).firstOrNull()
            if (bestVoice != null) tts?.voice = bestVoice
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isRunning.value = true
        startForeground(NotificationHelper.MONITORING_NOTIFICATION_ID, NotificationHelper.buildMonitoringNotification(this))
        synchronized(this) {
            if (workerThread?.isAlive == true) return START_STICKY
            running = true
            workerThread = thread(name = "AIqynYamNet") { runClassificationLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        _isRunning.value = false
        workerThread?.interrupt()
        try { workerThread?.join(5000) } catch (_: Exception) {}
        workerThread = null

        // Cleanup SpeechRecognizer
        speechRecognizer?.runCatching {
            stopListening()
            destroy()
        }
        speechRecognizer = null

        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runClassificationLoop() {
        var classifier: AudioClassifier? = null
        var record: AudioRecord? = null
        try {
            Log.d(TAG, "Initializing AudioClassifier...")
            classifier = AudioClassifier.createFromFileAndOptions(this, MODEL_ASSET,
                AudioClassifier.AudioClassifierOptions.builder().setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                    .setMaxResults(20).setScoreThreshold(0.005f).build())

            val tensorAudio = classifier.createInputTensorAudio()
            val sampleRate = 16000 
            
             val bufferSize = classifier.requiredInputBufferSize.toInt()
            
            // Use MIC source for environmental sounds; falling back to classifier default if failed
            record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), bufferSize * 2))
            } catch (e: Exception) {
                classifier.createAudioRecord()
            } catch (e: SecurityException) {
                classifier.createAudioRecord()
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                stopSelf()
                return
            }
            
            Log.d(TAG, "Starting recording...")
            record.startRecording()

            val prefs = UserPreferencesRepository(this)
            val history = mutableListOf<List<org.tensorflow.lite.support.label.Category>>()
            val audioData = ShortArray(bufferSize)

            Log.d(TAG, "Classification loop started")
            while (running && !Thread.currentThread().isInterrupted) {
                val read = record.read(audioData, 0, audioData.size)
                if (read > 0) {
                    // 1. PRIMARY PIPELINE: Environmental Sound Classification (YAMNet)
                    tensorAudio.load(audioData)
                    val results = classifier.classify(tensorAudio)
                    val categories = results.firstOrNull()?.categories ?: emptyList()
                    
                    history.add(categories)
                    if (history.size > 3) history.removeAt(0)
                    val averagedCategories = averageCategories(history)
                    val state = prefs.getUserState()

                    // Environmental Alerts (Siren, Dog, Knocking, etc.)
                    val environmentalDetection = YamNetCategoryMatcher.findDetection(averagedCategories, state.selectedSounds)
                    if (environmentalDetection != null) {
                        Log.i(TAG, "Environmental Danger: ${environmentalDetection.kind}")
                        maybeNotify(state, environmentalDetection)
                    }

                    // 2. SECONDARY PIPELINE: Name Recognition using Speech-to-Text
                    // We trigger SpeechRecognizer ONLY when Speech/Shouting is detected by YAMNet.
                    val speechScore = averagedCategories.find { it.label == "Speech" }?.score ?: 0f
                    val shoutScore = averagedCategories.find { it.label == "Shouting" || it.label == "Screaming" }?.score ?: 0f

                    if (state.nameRecognitionEnabled && (speechScore > 0.45f || shoutScore > 0.45f)) {
                        checkNameWithSpeechRecognition(state)
                    }
                }
                
                try {
                    Thread.sleep(CLASSIFY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Monitoring loop fatal error", e)
            stopSelf()
        } finally {
            Log.d(TAG, "Cleaning up AudioRecord and Classifier...")
            record?.runCatching { 
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release() 
            }
            classifier?.runCatching { close() }
        }
    }

    /**
     * Name Recognition using Speech-to-Text with improved fuzzy matching.
     * Uses Android SpeechRecognizer to transcribe incoming speech and match against
     * the user's saved name and phonetic variants from training.
     * IMPORTANT: This is called from the background worker thread. SpeechRecognizer
     * must be created and managed carefully to avoid conflicts with AudioRecord.
     */
    private fun checkNameWithSpeechRecognition(state: UserState) {
        val now = System.currentTimeMillis()
        if (now < nameAlertCooldown) return
        if (isRecognizing) return // Don't start if already recognizing

        val targetName = state.name.lowercase(Locale.ROOT).trim()
        if (targetName.isEmpty()) return

        // Check if SpeechRecognizer is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        Log.d(TAG, "SpeechRecognizer.isRecognitionAvailable: $isAvailable")
        if (!isAvailable) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }

        try {
            isRecognizing = true
            Log.d(TAG, "Creating SpeechRecognizer instance")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            if (speechRecognizer == null) {
                Log.e(TAG, "Failed to create SpeechRecognizer instance")
                isRecognizing = false
                return
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, state.language.code)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Speech recognizer ready for name detection")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech started for name detection")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech ended for name detection")
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                        else -> "ERROR_UNKNOWN($error)"
                    }
                    Log.e(TAG, "Speech recognizer error: $errorMsg")
                    isRecognizing = false
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "Speech recognition results: $matches")

                    if (!matches.isNullOrEmpty()) {
                        val detectedText = matches[0]
                        val normalizedText = normalizeText(detectedText)
                        Log.d(TAG, "Detected speech: '$detectedText' -> normalized: '$normalizedText'")

                        // Check if detected speech matches the target name or any phonetic variant
                        val nameMatch = isNameMatch(normalizedText, targetName, state.namePhoneticVariants)

                        if (nameMatch) {
                            Log.i(TAG, "Name matched: '$detectedText' contains '$targetName'")
                            triggerNameAlert(state)
                            nameAlertCooldown = now + 15000 // 15s cooldown to avoid spam
                        }

                        // Check context relevance for all detected speech
                        checkContextRelevance(detectedText, state)
                    }

                    isRecognizing = false
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Ignore partial results to avoid false positives
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            Log.d(TAG, "Starting SpeechRecognizer listening")
            speechRecognizer?.startListening(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in SpeechRecognizer (permission denied)", e)
            isRecognizing = false
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition for name detection", e)
            isRecognizing = false
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    /**
     * Normalize text for better matching:
     * - Convert to lowercase
     * - Remove punctuation
     * - Normalize spaces
     * - Remove common filler words
     */
    private fun normalizeText(text: String): String {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-zа-яёәғқңөұүіһ0-9\\s]"), "") // Keep only letters, numbers, spaces
            .replace(Regex("\\s+"), " ") // Normalize spaces
            .trim()

        // Remove common filler words (multilingual)
        val fillerWords = setOf(
            "um", "uh", "ah", "er", "like", "you know",
            "э", "м", "ну", "это", "типа", "короче",
            "yani", "şey", "ama", "fakat"
        )

        return fillerWords.fold(normalized) { acc, word ->
            acc.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), "")
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Check if detected text matches the target name with fuzzy matching.
     * Uses word boundary detection and Levenshtein distance for small errors.
     */
    private fun isNameMatch(detectedText: String, targetName: String, variants: Set<String>): Boolean {
        // First, try exact word boundary match
        val words = detectedText.split(Regex("\\s+"))

        // Check for exact word match with word boundaries
        if (words.any { it.equals(targetName, ignoreCase = true) }) {
            Log.d(TAG, "Exact word match found: $targetName")
            return true
        }

        // Check phonetic variants
        for (variant in variants) {
            val normalizedVariant = normalizeText(variant)
            if (words.any { it.equals(normalizedVariant, ignoreCase = true) }) {
                Log.d(TAG, "Variant match found: $normalizedVariant")
                return true
            }
        }

        // Check for substring match with word boundaries (e.g., "Papa" in "Papa, come here")
        val wordPattern = Pattern.compile("\\b${Pattern.quote(targetName)}\\b", Pattern.CASE_INSENSITIVE)
        if (wordPattern.matcher(detectedText).find()) {
            Log.d(TAG, "Word boundary substring match found: $targetName")
            return true
        }

        // Fuzzy matching with Levenshtein distance for small transcription errors
        // Only apply if the word length is similar (within 2 characters)
        for (word in words) {
            if (word.length >= 2 && Math.abs(word.length - targetName.length) <= 2) {
                val distance = levenshteinDistance(word, targetName)
                val maxDistance = maxOf(word.length, targetName.length) / 3 // Allow up to 1/3 character difference
                if (distance <= maxDistance && distance <= 2) {
                    Log.d(TAG, "Fuzzy match found: '$word' vs '$targetName' (distance: $distance)")
                    return true
                }
            }

            // Check variants with fuzzy matching
            for (variant in variants) {
                val normalizedVariant = normalizeText(variant)
                if (word.length >= 2 && Math.abs(word.length - normalizedVariant.length) <= 2) {
                    val distance = levenshteinDistance(word, normalizedVariant)
                    val maxDistance = maxOf(word.length, normalizedVariant.length) / 3
                    if (distance <= maxDistance && distance <= 2) {
                        Log.d(TAG, "Fuzzy variant match found: '$word' vs '$normalizedVariant' (distance: $distance)")
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Used for fuzzy matching to handle small transcription errors.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Check if detected speech is relevant to any active context reminders.
     */
    private fun checkContextRelevance(detectedText: String, state: UserState) {
        val now = System.currentTimeMillis()
        if (now < contextAlertCooldown) return

        val prefs = UserPreferencesRepository(this)
        val reminders = prefs.getContextReminders()

        if (reminders.isEmpty()) return

        val matches = ContextRelevanceEngine.analyzeRelevance(detectedText, reminders)

        if (matches.isNotEmpty()) {
            val highestMatch = ContextRelevanceEngine.getHighestRelevance(matches)
            if (highestMatch != null && highestMatch.relevance.priority >= 2) { // MEDIUM or HIGH
                Log.i(TAG, "Context relevance detected: ${highestMatch.reminder.event} (${highestMatch.relevance})")
                triggerContextAlert(highestMatch, state)
                contextAlertCooldown = now + 20000 // 20s cooldown for context alerts
            }
        }
    }

    /**
     * Trigger a context-relevant alert.
     */
    private fun triggerContextAlert(match: com.aiqyn.safestudio.data.ContextMatch, state: UserState) {
        val message = ContextRelevanceEngine.formatContextMessage(match, state.language.code)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus(audioManager)
        NotificationHelper.sendClassificationAlert(this, message)
        vibrate(state.mode == com.aiqyn.safestudio.data.Mode.DEAF)

        if (state.mode != com.aiqyn.safestudio.data.Mode.DEAF && isHeadphonesConnected(audioManager)) {
            speak(message)
        }
    }

    private fun triggerNameAlert(state: UserState) {
        val message = when(state.language) {
            com.aiqyn.safestudio.data.Language.RUSSIAN -> "Внимание! Кто-то зовет вас по имени: ${state.name}"
            com.aiqyn.safestudio.data.Language.KAZAKH -> "Назар аударыңыз! Біреу сізді есіміңізбен шақырып жатыр: ${state.name}"
            else -> "Attention! Someone is calling your name: ${state.name}"
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus(audioManager)
        NotificationHelper.sendClassificationAlert(this, message)
        vibrate(state.mode == com.aiqyn.safestudio.data.Mode.DEAF)

        if (state.mode != com.aiqyn.safestudio.data.Mode.DEAF && isHeadphonesConnected(audioManager)) {
            speak(message)
        }
    }

    private fun calculateRMS(buffer: ShortArray): Float {
        var sum = 0.0
        for (sample in buffer) sum += (sample.toDouble() / 32768.0).let { it * it }
        return sqrt(sum / buffer.size).toFloat()
    }

    private fun averageCategories(history: List<List<org.tensorflow.lite.support.label.Category>>): List<org.tensorflow.lite.support.label.Category> {
        if (history.isEmpty()) return emptyList()
        val totals = mutableMapOf<String, Float>()
        for (frame in history) {
            for (cat in frame) totals[cat.label] = (totals[cat.label] ?: 0f) + cat.score
        }
        val size = history.size.toFloat()
        return totals.map { (label, sum) -> org.tensorflow.lite.support.label.Category(label, sum / size) }
    }

    private fun maybeNotify(state: UserState, detection: SoundDetection) {
        val now = System.currentTimeMillis()
        synchronized(alertCooldownUntil) {
            val until = alertCooldownUntil[detection.kind] ?: 0L
            val lastScore = lastAlertScore[detection.kind] ?: 0f
            val significantIncrease = detection.score > (lastScore + 0.25f)
            if (now < until && !significantIncrease) return
            alertCooldownUntil[detection.kind] = now + ALERT_COOLDOWN_MS
            lastAlertScore[detection.kind] = detection.score
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val message = YamNetCategoryMatcher.notificationMessage(state.name, detection, state.language)

        requestAudioFocus(audioManager)
        NotificationHelper.sendClassificationAlert(this, message)
        vibrate(state.mode == com.aiqyn.safestudio.data.Mode.DEAF)

        if (state.mode != com.aiqyn.safestudio.data.Mode.DEAF && isHeadphonesConnected(audioManager)) {
            if (state.language == com.aiqyn.safestudio.data.Language.KAZAKH) {
                val res = tts?.isLanguageAvailable(Locale("kk", "KZ")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    speak(message)
                } else {
                    tts?.setLanguage(Locale("ru", "RU"))
                    speak(YamNetCategoryMatcher.transliteratedKazakhMessage(state.name, detection))
                }
            } else speak(message)
        }
    }

    private fun requestAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes).setAcceptsDelayedFocusGain(false).build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun isHeadphonesConnected(audioManager: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AIqynAlert")
    }

    private fun vibrate(isDeaf: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isDeaf) {
                val timings = longArrayOf(0, 500, 200, 500, 200, 500)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            if (isDeaf) vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1) else vibrator.vibrate(600)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, bufferSizeInBytes: Int): AudioRecord {
        return AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes)
    }

    private fun applyAudioEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.enabled = true
    }

    companion object {
        private const val TAG = "AudioMonitoringService"
        private const val MODEL_ASSET = "yamnet.tflite"
        private const val CLASSIFY_INTERVAL_MS = 30L
        private const val ALERT_COOLDOWN_MS = 10_000L
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        fun start(context: Context) {
            val intent = Intent(context, AudioMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, AudioMonitoringService::class.java)) }
    }
}
