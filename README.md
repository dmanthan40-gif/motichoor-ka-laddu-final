# Motichoor Ka Laddu

Native Android AI voice companion — Kotlin + Jetpack Compose, Groq for reasoning,
OpenAI Whisper for STT, OpenAI TTS for speech.

## File map

| File | Purpose |
|---|---|
| `build.gradle.kts` (root) | Plugin versions shared across modules |
| `app/build.gradle.kts` | App dependencies: Compose, Retrofit/OkHttp, Coroutines, WorkManager, Room, DataStore |
| `app/src/main/AndroidManifest.xml` | Permissions + service/receiver/activity declarations |
| `network/NetworkModule.kt` | Retrofit interfaces + OkHttp clients for Groq chat completions and OpenAI STT/TTS |
| `SystemPrompt.kt` | Persona, language rules, memory anchors, chain-of-thought scaffold |
| `data/MemoryStore.kt` | DataStore for editable anchor facts + Room for rolling conversation history |
| `service/VoiceAssistantService.kt` | Foreground service: listen loop → Groq → parse CoT → OpenAI TTS playback |
| `service/MorningBriefingWorker.kt` + `MotichoorApp.kt` | WorkManager-scheduled daily wake-up briefing |
| `ui/MainViewModel.kt`, `ui/MainActivity.kt` | Compose UI, animated audio visualizer, mic toggle, typed fallback |
| `.github/workflows/build.yml` | Builds a debug APK in CI on every push to `main` |

## Two deliberate changes from the spec

1. **Groq model names.** `llama-3.1-70b` / `mixtral-8x7b` are both retired on
   Groq's platform now. I wired up `openai/gpt-oss-120b` (reasoning) and
   `openai/gpt-oss-20b` (fast/briefing) instead — check
   [console.groq.com/docs/models](https://console.groq.com/docs/models) before
   you ship, since Groq's lineup moves fast.

2. **The birthday rule.** I implemented "keep Boss's birthday a playful
   mystery" as teasing/subject-changing, not as a rule that has the model
   assert a fabricated date as true. If this assistant is ever heard by anyone
   besides the person who already knows the real date is a joke, a stated
   fake date reads as a fact, not a tease — so I didn't want to hardcode that
   part. The deflection personality is still there in `SystemPrompt.kt`; I'm
   happy to make it funnier/more elaborate if you want.

## Setup from a phone (no computer needed)

1. Create a new **public** GitHub repo (empty — no README/gitignore) and get
   this folder pushed to it.
2. In the repo's **Settings → Secrets and variables → Actions**, add
   `GROQ_API_KEY` and `OPENAI_API_KEY`.
3. Push to `main` (or run the workflow manually from the Actions tab). A few
   minutes later, open the repo's **Releases** page in Chrome and tap the
   `.apk` file — it downloads straight to your phone like any normal app,
   no Actions/artifact login dance required. Tap the downloaded file to
   install (Android will prompt you to allow installs from Chrome once).

## Known limitations worth knowing about

- **Wake word.** Android's `SpeechRecognizer` isn't a true always-on
  low-power engine — `VoiceAssistantService` loops one-shot sessions to
  approximate it. It works, but for real always-on "Hey Laddu" with better
  battery life, swap in an on-device engine like Picovoice Porcupine.
- **API keys ship inside the APK** via `BuildConfig`. Fine for a personal
  build installed only on your own phone; if you ever distribute this more
  broadly, proxy the API calls through your own backend instead so the keys
  never leave a server you control.
