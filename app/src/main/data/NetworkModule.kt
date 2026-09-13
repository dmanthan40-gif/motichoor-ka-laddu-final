package com.duggu.laddu.data

import com.duggu.laddu.BuildConfig
import com.duggu.laddu.persona.SystemPrompt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

// ---------- DTOs ----------

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.8,
    val max_tokens: Int = 512,
    val response_format: ResponseFormat? = null
)

@Serializable
data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
data class Choice(val message: ChatMessage? = null)

@Serializable
data class SpeechRequest(
    val model: String,
    val input: String,
    val voice: String,
    val response_format: String = "mp3"
)

/** Structured chain-of-thought payload. Only [speech] is ever spoken aloud. */
@Serializable
data class ReasonedReply(val thought: String = "", val speech: String)

// ---------- API interfaces ----------

interface GroqApi {
    @POST("chat/completions")
    suspend fun chat(@Body body: ChatRequest): ChatResponse
}

interface OpenAiApi {
    @Streaming
    @POST("audio/speech")
    suspend fun synthesize(@Body body: SpeechRequest): Response<ResponseBody>
}

// ---------- Module ----------

object NetworkModule {
    const val GROQ_MODEL = "llama-3.3-70b-versatile" // llama-3.1-70b / mixtral are RETIRED
    const val TTS_MODEL = "tts-1"
    const val TTS_VOICE = "coral"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun okHttp(apiKey: String) = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()
            )
        }
        .build()

    val groq: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/v1/")
            .client(okHttp(BuildConfig.GROQ_API_KEY))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GroqApi::class.java)
    }

    val openai: OpenAiApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttp(BuildConfig.OPENAI_API_KEY))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiApi::class.java)
    }

    /** One reasoning turn against Groq with the master persona prompt. */
    suspend fun reason(history: List<ChatMessage>): ReasonedReply {
        val request = ChatRequest(
            model = GROQ_MODEL,
            messages = listOf(ChatMessage("system", SystemPrompt.MASTER)) + history,
            response_format = ResponseFormat("json_object")
        )
        val raw = groq.chat(request).choices.firstOrNull()?.message?.content.orEmpty()
        return parseReply(raw)
    }

    /** Robust parse: JSON → stripped code-fence JSON → speak raw text as last resort. */
    fun parseReply(raw: String): ReasonedReply {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return runCatching { json.decodeFromString<ReasonedReply>(cleaned) }
            .getOrElse { ReasonedReply(speech = raw) }
    }
}
