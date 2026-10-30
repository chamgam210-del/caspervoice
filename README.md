# CasperVoice

A full-duplex voice for Android apps, with the speech-to-text and text-to-speech
models of your choice. It does what a realtime voice API does on the phone
itself:

- **Echo-cancelled microphone.** It records in voice-call mode, with the phone's
  echo canceller and noise suppressor, so the assistant can talk on
  speakerphone without hearing itself.
- **Speech detection on the phone** with Silero VAD v5 (bundled).
- **Turn-taking.** An utterance starts once speech has lasted 200 ms and ends
  after 1.5 s of silence. The 0.7 s before speech is kept, so the first word
  is not clipped.
- **Barge-in, done carefully.** While the assistant talks, detection is
  stricter:
  - The first hint of speech lowers its volume.
  - Speech that lasts 750 ms stops it.
  - A cough gives the volume back and is never transcribed.
  - If the barge-in turns out to be nothing (a blank transcript or its own
    echo), the line resumes from the sentence that was cut.
- **Its own echo dropped.** A transcript that is just what the assistant
  said is ignored.
- **What was heard.** When a line is cut off, you get the words that got out,
  for the conversation history.
- **Audio routing.** It uses the loudspeaker, or a Bluetooth or wired headset
  when one is connected.
- **Swappable providers.** Models, voices and keys can change between two
  sentences.
- **An optional reply hook** to make it a complete voice agent with any
  OpenAI-style chat model.

## Install

CasperVoice is on [JitPack](https://jitpack.io/#chamgam210-del/caspervoice).
Add the repository and the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies { implementation("com.github.chamgam210-del:caspervoice:0.1.0") }
```

To build the AAR yourself, run
`./gradlew :voice:publishReleasePublicationToLocalRepository`; it writes
`voice/build/repo`.

The minimum SDK is 29. The library's manifest adds `RECORD_AUDIO`,
`INTERNET` and `MODIFY_AUDIO_SETTINGS`. Your app asks for the microphone
permission at run time. To keep listening in the background, it also needs
a foreground service of type `microphone`.

## A voice agent in a few lines

```kotlin
val agent = VoiceAgent.create(context) {
    keys = KeySource { provider -> myKeys[provider] }   // "openai", "deepgram", ...
    hearing = TranscriptionSettings(provider = "deepgram", language = "en")
    speaking = SpeechSettings(provider = "elevenlabs", voice = "JBFqnCBsd6RMkjVDRZzb")
    responder = OpenAiChat({ ChatSettings(model = "gpt-6-luna") }, TokenSource { myKeys["openai"] })
}
lifecycleScope.launch { agent.start() }
// ...
agent.stop(); agent.close()
```

If your app decides what to say itself, leave out `responder`, then listen
and answer:

```kotlin
agent.events.collect { e ->
    when (e) {
        is VoiceEvent.Heard -> agent.say(Utterance(answerFor(e.text)))
        is VoiceEvent.Interrupted -> history.add(e.heard)   // only what got out
        else -> Unit
    }
}
```

`agent.speak(text)` does the same as `say`, but waits and returns how the line
went: finished, interrupted (with `heard` and `unheard`), or failed.

## Providers

Pick a provider by name in the settings. A key comes from `apiKey` in the
settings, or else from `keys` by provider name. The library never stores
keys. In a shipped app, have your server hand out short-lived keys rather
than putting long-lived ones in the APK.

### Hearing: `TranscriptionSettings(provider = ...)`

| Provider | What it is | Default model |
| --- | --- | --- |
| `openai` | OpenAI streaming transcription, with partial text | `gpt-transcribe` |
| `deepgram` | Deepgram live transcription, with partial text; `prompt` is sent as key terms | `nova-3` |
| `groq` | Groq Whisper (file upload) | `whisper-large-v3-turbo` |
| `openai-compatible` | Any `/audio/transcriptions` server: whisper.cpp, faster-whisper, Speaches, Together, Fireworks... Set `baseUrl` and `model`. | — |

`language` pins the language, which stops short or noisy utterances from
coming back as another language. `prompt` carries names to spell right.

### Speaking: `SpeechSettings(provider = ...)`

| Provider | What it is | Default |
| --- | --- | --- |
| `openai` | OpenAI `/audio/speech` | `gpt-4o-mini-tts`, voice `cedar` |
| `openai-compatible` | Any server that copies it, such as a local Kokoro or Piper server. Set `baseUrl`. | — |
| `elevenlabs` | ElevenLabs streaming; `voice` is a voice ID | `eleven_flash_v2_5` |
| `system` | The phone's own engine: free, offline, no key. `voice` is a voice name or a language tag such as `en-GB`. | — |

### Your own

Implement `SpeechToText` or `TextToSpeech` and register it:

```kotlin
TranscriptionProviders.register("mine") { settings, keys -> MyEars(settings, keys) }
SpeechProviders.register("mine") { settings, keys -> MyVoice(settings, keys) }
```

You can also replace the microphone (`microphone = { myFrames }`, 16 kHz mono
PCM16 in 512-sample frames) or the speech detector (`detector = ...`).

### Changing models while running

```kotlin
VoiceAgent.create(context) {
    hearingFrom { settingsStore.hearing() }    // read before every utterance
    speakingFrom { settingsStore.speaking() }  // read before every line
}
```

## Tuning

`rules = TurnTaking.DEFAULT.copy(...)` changes the following:

- the detection threshold and the silence that ends a turn, both while
  listening and while the assistant talks;
- the barge-in hold time;
- whether a false barge-in resumes the line.

`agent.stats` counts turns heard, barge-ins, false interruptions and dropped
echoes, and records time to first sound and transcription time.
`onUtterance` hands you every utterance's audio, for checking what the ears
got.

## Try it: the sample app

`voice-sample` is a talk-back demo that uses nothing but the library. Pick
the hearing provider, speaking provider, reply mode and audio route, press
Start, and talk. For the reply, it can say back what it heard or answer with
an OpenAI chat model. Every event shows with its time: what you said,
barge-ins, cut-off lines, resumes and failures. The numbers underneath are
turns heard, barge-ins, false interruptions, dropped echoes, time to first
sound and transcription time.

```
./gradlew :voice-sample:installDebug
```

Keys typed into the app stay in its private storage on the phone.

## Tests

- **Unit tests** check the logic on the computer, with fake audio, ears and
  voices:
  - turn-taking and barge-in, including resuming after a false barge-in;
  - echo dropping and the retry;
  - settings parsing, and keys never appearing in `toString`;
  - provider requests, resampling and sentence splitting.

  ```
  ./gradlew :voice:testDebugUnitTest
  ```

- **On-phone tests** run the real library on a connected phone with real
  models and no microphone. Each one makes speech with a text-to-speech
  model, then feeds it in as if it came from the microphone, in real time. It
  goes through the bundled Silero model, turn-taking and the real
  transcriber. They check that:
  - questions are heard word for word, through OpenAI's streaming session
    and through the file-upload API, and through Deepgram and Groq when
    those keys exist;
  - each voice streams speech;
  - silence and a low hum are never sent to be transcribed;
  - the chat responder answers;
  - the whole loop (hear, answer, speak) works.

  Keys come from an `env.properties` file (`OPENAI_API_KEY`,
  `ELEVENLABS_API_KEY`, `DEEPGRAM_API_KEY`, `GROQ_API_KEY`) in `test-keys/`
  (git-ignored), or in the folder named by `-PvoiceTestKeys`. It is packed
  into the test APK only (and the sample's debug build, to prefill it), and a
  test whose key is missing is skipped. The loop test plays sound; to leave
  it out, add
  `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.casper.voice.PlaysAudio`.

  ```
  ./gradlew :voice:connectedDebugAndroidTest
  ```

Measured on a Galaxy A52, from the end of speech to the text:

| Provider | Time |
| --- | --- |
| OpenAI streaming (`gpt-transcribe`) | 0.5 s |
| OpenAI file upload (`gpt-4o-mini-transcribe`) | 1.0 s |

First audio from the voices:

| Voice | Time |
| --- | --- |
| OpenAI | 1.0 s |
| System voice (including engine start-up) | 1.5 s |
| ElevenLabs | 1.6 s |

## Privacy

The library sends audio and text only to the providers you choose. ONNX
Runtime's Android package normally reports usage to Microsoft at start-up;
the library's manifest removes that, because it only runs a local model.

## Licence

MIT; see [LICENSE](LICENSE). Third-party notices, including Silero VAD (MIT),
are in [NOTICE](NOTICE).
