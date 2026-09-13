package com.motichoorkaladdu.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.motichoorkaladdu.assistant.R
import com.motichoorkaladdu.assistant.buildCotInstruction
import com.motichoorkaladdu.assistant.buildSystemPrompt
import com.motichoorkaladdu.assistant.data.ConversationTurn
import com.motichoorkaladdu.assistant.data.MemoryDatabase
import com.motichoorkaladdu.assistant.network.ChatCompletionRequest
import com.motichoorkaladdu.assistant.network.ChatMessage
import com.motichoorkaladdu.assistant.network.GroqApi
import com.motichoorkaladdu.assistant.network.NetworkModule
import com.motichoorkaladdu.assistant.network.SpeechRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that keeps a mic session alive, listens for the wake
 * word, streams the follow-up utterance to Groq, and speaks the answer back
 * via OpenAI TTS.
 *
 * NOTE ON "WAKE WORD": Android's SpeechRecognizer is not a true low-power
 * always-on wake-word engine — it's a one-shot recognition session. Looping
 * it approximates "always listening" but drains battery faster and can miss
 * words spoken during the brief gap between sessions. For a production-grade
 * always-on wake word ("Hey Laddu"), swap WAKE_WORD_MODE for an on-device
 * engine like Picovoice Porcupine, which is built for exactly this and sips
 * far less power. This implementation is a solid, dependency-free starting
 * point.
 */
class VoiceAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isAwaitingWakeWord = true

    private val wakeWords = listOf("hey laddu", "laddu", "ओके लड्डू")

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startListeningCycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recognizer?.destroy()
        mediaPlayer?.release()
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------
    // Listening loop
    // -------------------------------------------------------------------

    private fun startListeningCycle() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    val heard = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    handleHeardText(heard)
                    restartListening()
                }

                override fun onError(error: Int) {
                    // No speech / timeout is expected constantly in a listening
                    // loop — just restart quietly instead of logging noise.
                    restartListening()
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
        beginListeningSession()
    }

    private fun beginListeningSession() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
        recognizer?.startListening(intent)
    }

    private fun restartListening() {
        // Small gap avoids hammering the recognizer; a real wake-word engine
        // wouldn't need this cycle at all.
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
        )
    }

    private fun handleHeardText(heard: String) {
        if (heard.isBlank()) return
        val lower = heard.lowercase()

        if (isAwaitingWakeWord) {
            if (wakeWords.any { lower.contains(it) }) {
                isAwaitingWakeWord = false
                val strippedCommand = wakeWords.fold(lower) { acc, w -> acc.replace(w, "") }.trim()
                if (strippedCommand.isNotBlank()) {
                    processUserUtterance(strippedCommand)
                }
            }
            return
        }

        processUserUtterance(heard)
        isAwaitingWakeWord = true
    }

    // -------------------------------------------------------------------
    // Groq round trip + chain-of-thought parsing
    // -------------------------------------------------------------------

    private fun processUserUtterance(text: String) {
        scope.launch {
            val db = MemoryDatabase.get(applicationContext)
            db.conversationDao().insert(ConversationTurn(role = "user", content = text))

            val history = db.conversationDao().recent(12).reversed().map {
                ChatMessage(role = it.role, content = it.content)
            }

            val messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt() + "\n\n" + buildCotInstruction())
            ) + history

            try {
                val response = NetworkModule.groqApi.chatCompletion(
                    ChatCompletionRequest(
                        model = GroqApi.REASONING_MODEL,
                        messages = messages
                    )
                )
                val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
                val spoken = extractReply(raw)

                db.conversationDao().insert(ConversationTurn(role = "assistant", content = spoken))
                speak(spoken)
            } catch (e: Exception) {
                speak("Sorry Boss, I couldn't reach the server just now.")
            }
        }
    }

    /** Pulls the <reply>…</reply> block out of the chain-of-thought output. */
    private fun extractReply(raw: String): String {
        val match = Regex("<reply>(.*?)</reply>", RegexOption.DOT_MATCHES_ALL).find(raw)
        return match?.groupValues?.get(1)?.trim() ?: raw.trim()
    }

    // -------------------------------------------------------------------
    // Text-to-speech via OpenAI
    // -------------------------------------------------------------------

    private fun speak(text: String) {
        scope.launch {
            try {
                val response = NetworkModule.openAiApi.synthesizeSpeech(
                    SpeechRequest(input = text)
                )
                val body = response.body() ?: return@launch
                val file = File.createTempFile("laddu_tts", ".mp3", cacheDir)
                FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                // Swallow — a failed TTS call shouldn't crash a background service.
            }
        }
    }

    // -------------------------------------------------------------------
    // Notification (required for a foreground service)
    // -------------------------------------------------------------------

    private fun buildNotification(): android.app.Notification {
        val channelId = "assistant_listening"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_listening))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
