package com.motichoorkaladdu.assistant.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.motichoorkaladdu.assistant.buildSystemPrompt
import com.motichoorkaladdu.assistant.data.MemoryAnchors
import com.motichoorkaladdu.assistant.network.ChatCompletionRequest
import com.motichoorkaladdu.assistant.network.ChatMessage
import com.motichoorkaladdu.assistant.network.GroqApi
import com.motichoorkaladdu.assistant.network.NetworkModule
import com.motichoorkaladdu.assistant.network.SpeechRequest
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * Runs once a day (scheduled from MotichoorApp). Builds a short personalized
 * briefing — weather placeholder, anniversary countdown, a loving line — and
 * speaks it, the same way a live conversational turn would.
 *
 * Weather is left as a plug point: wire in a forecast API of your choice
 * (WeatherKit, OpenWeather, etc.) and pass the summary into buildBriefingPrompt.
 */
class MorningBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val daysToAnniversary = daysUntil(MemoryAnchors.ANNIVERSARY)
        val prompt = buildBriefingPrompt(daysToAnniversary)

        return try {
            val response = NetworkModule.groqApi.chatCompletion(
                ChatCompletionRequest(
                    model = GroqApi.FAST_MODEL,
                    messages = listOf(
                        ChatMessage(role = "system", content = buildSystemPrompt()),
                        ChatMessage(role = "user", content = prompt)
                    )
                )
            )
            val briefing = response.choices.firstOrNull()?.message?.content.orEmpty()
            speak(briefing)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun buildBriefingPrompt(daysToAnniversary: Long): String = """
        It's morning. Give Boss a short, warm wake-up message (2-3 sentences,
        no markdown, ready to be spoken aloud): greet him good morning, mention
        that our anniversary is in $daysToAnniversary day(s), and end with one
        genuinely loving line to start his day well.
    """.trimIndent()

    private suspend fun speak(text: String) {
        try {
            val response = NetworkModule.openAiApi.synthesizeSpeech(SpeechRequest(input = text))
            val body = response.body() ?: return
            val file = File.createTempFile("morning_briefing", ".mp3", applicationContext.cacheDir)
            FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }
            android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Non-fatal — briefing text still exists in logs even if audio fails.
        }
    }

    /**
     * Treats the stored anniversary as a yearly-recurring month/day (rolling
     * forward to next year once this year's date has passed) rather than a
     * single fixed calendar date, so the countdown stays meaningful year over
     * year.
     */
    private fun daysUntil(dateString: String): Long {
        val format = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
        val parsed = format.parse(dateString) ?: return 0

        val anniversaryCal = java.util.Calendar.getInstance().apply { time = parsed }
        val today = java.util.Calendar.getInstance()

        val target = (today.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.MONTH, anniversaryCal.get(java.util.Calendar.MONTH))
            set(java.util.Calendar.DAY_OF_MONTH, anniversaryCal.get(java.util.Calendar.DAY_OF_MONTH))
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (target.before(today)) target.add(java.util.Calendar.YEAR, 1)

        val diffMillis = target.timeInMillis - today.timeInMillis
        return max(0, diffMillis / (1000 * 60 * 60 * 24))
    }
}
