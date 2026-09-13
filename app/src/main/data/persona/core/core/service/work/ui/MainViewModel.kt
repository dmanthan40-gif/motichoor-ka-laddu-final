package com.duggu.laddu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duggu.laddu.core.AssistantBus
import com.duggu.laddu.core.AssistantEngine
import com.duggu.laddu.core.AssistantState
import com.duggu.laddu.service.VoiceAssistantService
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state = AssistantBus.state.asStateFlow()
    val messages = AssistantBus.messages.asStateFlow()
    val partial = AssistantBus.partial.asStateFlow()
    val lastThought = AssistantBus.lastThought.asStateFlow()

    val isRunning: Boolean get() = state.value != AssistantState.IDLE

    fun toggleAssistant() {
        if (isRunning) VoiceAssistantService.stop(getApplication())
        else VoiceAssistantService.start(getApplication())
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { AssistantEngine.respond(getApplication(), text.trim()) }
    }

    fun scheduleMorningBriefingNow() {
        com.duggu.laddu.work.AlarmReceiver.scheduleNext(getApplication())
    }

    fun clearChat() = AssistantEngine.reset()
}
