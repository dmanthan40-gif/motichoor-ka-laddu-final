package com.motichoorkaladdu.assistant.network

import com.motichoorkaladdu.assistant.BuildConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Chat payloads — Groq's /openai/v1/chat/completions is OpenAI-schema compatible
// ---------------------------------------------------------------------------

data class ChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 1024,
    val stream: Boolean = false
)

data class ChatChoice(val index: Int, val message: ChatMessage, val finish_reason: String?)
data class ChatCompletionResponse(val id: String, val model: String, val choices: List<ChatChoice>)

interface GroqApi {
    @POST("openai/v1/chat/completions")
    suspend fun chatCompletion(@Body request: ChatCompletionRequest): ChatCompletionResponse

    companion object {
        // llama-3.1-70b / mixtral-8x7b have both been retired by Groq. gpt-oss-120b is
        // the current recommended high-quality replacement; swap here if Groq's lineup
        // changes again — check https://console.groq.com/docs/models before shipping.
        const val REASONING_MODEL = "openai/gpt-oss-120b"
        const val FAST_MODEL = "openai/gpt-oss-20b"
    }
}

// ---------------------------------------------------------------------------
// OpenAI — Whisper transcription (STT) + tts-1 speech synthesis (TTS)
// ---------------------------------------------------------------------------

data class TranscriptionResponse(val text: String)

data class SpeechRequest(
    val model: String = "tts-1",
    val input: String,
    val voice: String = "shimmer",
    val response_format: String = "mp3",
    val speed: Double = 1.0
)

interface OpenAiApi {

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part model: MultipartBody.Part
    ): TranscriptionResponse

    @Headers("Content-Type: application/json")
    @POST("v1/audio/speech")
    suspend fun synthesizeSpeech(@Body request: SpeechRequest): Response<ResponseBody>
}

// ---------------------------------------------------------------------------
// Retrofit / OkHttp wiring
// ---------------------------------------------------------------------------

object NetworkModule {

    private fun authInterceptor(tokenProvider: () -> String): Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${tokenProvider()}")
            .build()
        chain.proceed(request)
    }

    private fun buildClient(tokenProvider: () -> String): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor(tokenProvider))
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val groqApi: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(buildClient { BuildConfig.GROQ_API_KEY })
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }

    val openAiApi: OpenAiApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(buildClient { BuildConfig.OPENAI_API_KEY })
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }
}
