package com.duggu.laddu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.duggu.laddu.core.AssistantBus
import com.duggu.laddu.core.AssistantEngine
import com.duggu.laddu.core.AssistantState
import com.duggu.laddu.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var processing = false
    private var awaitingCommand = false
    private var backoffMs = 400L

    companion object {
        const val ACTION_START = "com.duggu.laddu.START"
        const val ACTION_STOP = "com.duggu.laddu.STOP"
        private const val CHANNEL_ID = "laddu_assistant"
        private const val NOTIFICATION_ID = 42
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, VoiceAssistantService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(context: Context) {
            context.startService(
                Intent(context, VoiceAssistantService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Laddu Assistant", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AssistantBus.setState(AssistantState.IDLE)
                destroyRecognizer()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground("Listening for \"Laddu\"…")
                AssistantBus.setState(AssistantState.LISTENING)
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(text: String) {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Motichoor Ka Laddu")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun listenIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN") // understands Hindi/Hinglish too
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    private fun startListening() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        recognizer?.startListening(listenIntent())
        AssistantBus.setState(AssistantState.LISTENING)
    }

    private fun restartListening() {
        if (processing) return
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(4000L)
            startListening()
        }
    }

    private fun handleUtterance(text: String) {
        val lower = text.lowercase()
        val command = if (AssistantEngine.hasWakeWord(lower)) {
            AssistantEngine.stripWakeWord(text)
        } else if (awaitingCommand) {
            text
        } else {
            restartListening(); return
        }
        if (command.isBlank()) { awaitingCommand = true; restartListening(); return }
        awaitingCommand = false
        processing = true
        scope.launch {
            try {
                startAsForeground("Thinking…")
                AssistantEngine.respond(applicationContext, command)
            } finally {
                processing = false
                backoffMs = 400L
                startAsForeground("Listening for \"Laddu\"…")
                startListening()
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { backoffMs = 400L }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (!processing) AssistantBus.setPartial(text)
        }
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) handleUtterance(text) else restartListening()
        }
        override fun onError(error: Int) {
            if (processing) return
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartListening()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Unit
                else -> restartListening() // NO_MATCH, SPEECH_TIMEOUT, etc. = keep the loop alive
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    override fun onDestroy() {
        destroyRecognizer()
        scope.cancel()
        super.onDestroy()
    }
}
