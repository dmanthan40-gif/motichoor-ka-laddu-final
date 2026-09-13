package com.motichoorkaladdu.assistant.ui

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motichoorkaladdu.assistant.buildSystemPrompt
import com.motichoorkaladdu.assistant.data.ConversationTurn
import com.motichoorkaladdu.assistant.data.MemoryDatabase
import com.motichoorkaladdu.assistant.network.ChatCompletionRequest
import com.motichoorkaladdu.assistant.network.ChatMessage
import com.motichoorkaladdu.assistant.network.GroqApi
import com.motichoorkaladdu.assistant.network.NetworkModule
import com.motichoorkaladdu.assistant.service.VoiceAssistantService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatLine(val role: String, val text: String)

data class MainUiState(
    val isListening: Boolean = false,
    val transcript: List<ChatLine> = emptyList(),
    val isSending: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Simple 0f..1f level the Compose visualizer animates toward. Wire this up
     *  to SpeechRecognizer's onRmsChanged (via a shared StateFlow/broadcast) for
     *  a visualizer that reacts to Boss's real voice instead of a canned pulse. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun setAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    fun toggleListening() {
        val turningOn = !_uiState.value.isListening
        val context = getApplication<Application>()
        val intent = android.content.Intent(context, VoiceAssistantService::class.java)

        if (turningOn) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
        _uiState.value = _uiState.value.copy(isListening = turningOn)
    }

    /** Manual typed fallback for testing without the mic loop. */
    fun sendTypedMessage(text: String) {
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(
            transcript = _uiState.value.transcript + ChatLine("user", text),
            isSending = true
        )

        viewModelScope.launch {
            val db = MemoryDatabase.get(getApplication())
            db.conversationDao().insert(ConversationTurn(role = "user", content = text))
            val history = db.conversationDao().recent(12).reversed().map {
                ChatMessage(role = it.role, content = it.content)
            }

            try {
                val response = NetworkModule.groqApi.chatCompletion(
                    ChatCompletionRequest(
                        model = GroqApi.REASONING_MODEL,
                        messages = listOf(ChatMessage("system", buildSystemPrompt())) + history
                    )
                )
                val reply = response.choices.firstOrNull()?.message?.content
                    ?.substringAfter("<reply>").substringBefore("</reply>")
                    ?.trim().takeUnless { it.isNullOrBlank() }
                    ?: response.choices.firstOrNull()?.message?.content.orEmpty()

                db.conversationDao().insert(ConversationTurn(role = "assistant", content = reply))
                _uiState.value = _uiState.value.copy(
                    transcript = _uiState.value.transcript + ChatLine("assistant", reply),
                    isSending = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transcript = _uiState.value.transcript + ChatLine("assistant", "Couldn't reach the server."),
                    isSending = false
                )
            }
        }
    }
}
