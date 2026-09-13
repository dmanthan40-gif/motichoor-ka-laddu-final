package com.duggu.laddu.core

import com.duggu.laddu.data.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

object AssistantBus {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial = _partial.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _lastThought = MutableStateFlow("")
    val lastThought = _lastThought.asStateFlow()

    fun setState(s: AssistantState) { _state.value = s }
    fun setPartial(text: String) { _partial.value = text }
    fun setThought(text: String) { _lastThought.value = text }
    fun addMessage(msg: ChatMessage) = _messages.update { it + msg }
    fun clear() { _messages.value = emptyList(); _partial.value = ""; _lastThought.value = "" }
}
