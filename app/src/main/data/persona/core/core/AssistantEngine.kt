package com.duggu.laddu.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.duggu.laddu.data.ChatMessage
import com.duggu.laddu.data.NetworkModule
import com.duggu.laddu.data.ReasonedReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class TtsSpeaker(private val context: Context) {

    /** Downloads OpenAI TTS mp3 to cache, plays it, suspends until playback finishes. */
    suspend fun speak(text: String) {
        val file = withContext(Dispatchers.IO) {
            val response = NetworkModule.openai.synthesize(
                SpeechRequest(model = NetworkModule.TTS_MODEL, input = text.take(4000), voice = NetworkModule.TTS_VOICE)
            )
            check(response.isSuccessful) { "TTS failed: HTTP ${response.code()}" }
            val body = response.body() ?: error("Empty TTS body")
            val f = File(context.cacheDir, "tts_${System.nanoTime()}.mp3")
            body.use { src -> f.outputStream().use { src.byteStream().copyTo(it) } }
            f
        }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                cont.invokeOnCancellation { runCatching { player.release() }; file.delete() }
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA).build()
                )
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { mp -> mp.release(); file.delete(); cont.resume(Unit) }
                player.setOnErrorListener { mp, _, _ -> mp.release(); file.delete(); cont.resume(Unit); true }
                player.prepareAsync()
            }
        }
    }
}

object AssistantEngine {
    private val history = mutableListOf<ChatMessage>()
    private const val WAKE_WORDS = "laddu|ladoo|laddoo|motichoor|motichur"

    /** Returns true if [text] contains the wake word. */
    fun hasWakeWord(text: String) =
        Regex("($WAKE_WORDS)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Strips everything up to and including the wake word; returns the command (may be blank). */
    fun stripWakeWord(text: String): String =
        Regex("^.*?($WAKE_WORDS)\\s*", RegexOption.IGNORE_CASE)
            .replace(text.trim(), "")
            .trim(' ', ',', '.', '!')

    /** Full brain turn: history -> Groq CoT -> speak. Suspends until speech completes. */
    suspend fun respond(context: Context, userText: String): ReasonedReply {
        AssistantBus.setPartial("")
        AssistantBus.addMessage(ChatMessage("user", userText))
        AssistantBus.setState(AssistantState.THINKING)
        history += ChatMessage("user", userText)
        if (history.size > 20) history.subList(0, history.size - 20).clear()

        val reply = runCatching { NetworkModule.reason(history) }
            .getOrElse {
                ReasonedReply(
                    speech = "Sorry Boss, network ka masla hai. Thodi der baad phir try karta hoon, theek hai?"
                )
            }

        history += ChatMessage("assistant", reply.speech)
        AssistantBus.setThought(reply.thought)
        AssistantBus.addMessage(ChatMessage("assistant", reply.speech))
        AssistantBus.setState(AssistantState.SPEAKING)
        TtsSpeaker(context.applicationContext).speak(reply.speech)
        return reply
    }

    fun reset() { history.clear(); AssistantBus.clear() }
}
