package com.casper.voice

import com.casper.voice.audio.Resampler
import com.casper.voice.chat.OpenAiChat
import com.casper.voice.stt.DeepgramTranscription
import com.casper.voice.stt.HttpTranscription
import com.casper.voice.stt.TranscriptionProviders
import com.casper.voice.tts.ElevenLabsSpeech
import com.casper.voice.tts.SpeechProviders
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    @Test
    fun settingsNeverPrintTheKey() {
        val speech = SpeechSettings(apiKey = "sk-secret-123").toString()
        val hearing = TranscriptionSettings(apiKey = "sk-secret-456", prompt = "Casper").toString()
        assertFalse(speech, speech.contains("secret"))
        assertFalse(hearing, hearing.contains("secret"))
        assertTrue(speech.contains("apiKey=set"))
        assertTrue(SpeechSettings().toString().contains("apiKey=none"))
    }

    @Test
    fun everyBuiltInProviderIsRegistered() {
        assertTrue(TranscriptionProviders.names.containsAll(listOf("openai", "openai-compatible", "groq", "deepgram")))
        assertTrue(SpeechProviders.names.containsAll(listOf("openai", "openai-compatible", "elevenlabs")))
    }

    @Test
    fun groqFillsInItsOwnAddressAndModelButKeepsTheHostsChoices() {
        val groq = HttpTranscription(TranscriptionSettings(provider = "groq", language = "en"), { "k" },
            defaultBaseUrl = HttpTranscription.GROQ_URL, defaultModel = HttpTranscription.GROQ_MODEL)
        val req = groq.request(ByteArray(10), "k")
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", req.url.toString())
        assertEquals("Bearer k", req.header("Authorization"))
        val body = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains(HttpTranscription.GROQ_MODEL))
        assertTrue(body.contains("name=\"language\""))

        val local = HttpTranscription(TranscriptionSettings(baseUrl = "http://10.0.2.2:8000/v1/", model = "small.en"), { null })
        val r2 = local.request(ByteArray(10), null)
        assertEquals("http://10.0.2.2:8000/v1/audio/transcriptions", r2.url.toString())
        assertNull(r2.header("Authorization"))
    }

    @Test
    fun uploadedTranscriptsAreReadFromJsonOrPlainText() {
        assertEquals("hello there", HttpTranscription.textOf("""{"text":" hello there "}"""))
        assertEquals("hello", HttpTranscription.textOf("hello\n"))
    }

    @Test
    fun deepgramUrlCarriesTheAudioFormatAndKeyTerms() {
        val url = DeepgramTranscription.url(TranscriptionSettings(provider = "deepgram", language = "en", prompt = "Casper, Oshawa"))
        assertTrue(url, url.startsWith("wss://api.deepgram.com/v1/listen?model=nova-3"))
        assertTrue(url.contains("encoding=linear16"))
        assertTrue(url.contains("sample_rate=16000"))
        assertTrue(url.contains("language=en"))
        assertTrue(url.contains("keyterm=Casper"))
        assertTrue(url.contains("keyterm=Oshawa"))
    }

    @Test
    fun deepgramResultsAreReadAndMetadataIgnored() {
        val msg = """{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"turn on the lights"}]}}"""
        assertEquals(DeepgramTranscription.Result("turn on the lights", true), DeepgramTranscription.parse(msg))
        assertNull(DeepgramTranscription.parse("""{"type":"Metadata"}"""))
        val bytes = DeepgramTranscription.bytes(shortArrayOf(1, -1))
        assertEquals(listOf<Byte>(1, 0, -1, -1), bytes.toList())
    }

    @Test
    fun elevenLabsUsesAStockVoiceUntilOneIsChosen() {
        assertTrue(ElevenLabsSpeech.url(SpeechSettings(provider = "elevenlabs")).contains("/text-to-speech/${ElevenLabsSpeech.VOICE}/stream"))
        assertTrue(ElevenLabsSpeech.url(SpeechSettings(provider = "elevenlabs", voice = "abc")).contains("/text-to-speech/abc/stream?output_format=pcm_24000"))
        val body = JSONObject(ElevenLabsSpeech.requestBody(SpeechSettings(provider = "elevenlabs", speed = 1.1), "Hi."))
        assertEquals(ElevenLabsSpeech.MODEL, body.getString("model_id"))
        assertEquals(1.1, body.getJSONObject("voice_settings").getDouble("speed"), 0.0)
    }

    @Test
    fun resamplingKeepsTheLengthAndHasNoStepAtTheSeams() {
        val r = Resampler(16_000, 24_000)
        val ramp = ShortArray(1_600) { (it * 10).toShort() }
        val out = ramp.toList().chunked(160).flatMap { r.feed(it.toShortArray()).toList() }
        assertTrue("got ${out.size}", out.size in 2_396..2_400)
        for (i in 1 until out.size) assertTrue("jump at $i", out[i] - out[i - 1] in 0..10)
    }

    @Test
    fun streamedChatTextIsReadFromServerSentEvents() {
        assertEquals("Hel", OpenAiChat.delta("""data: {"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertNull(OpenAiChat.delta("data: [DONE]"))
        assertNull(OpenAiChat.delta(""": keep-alive"""))
        assertNull(OpenAiChat.delta("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun sentencesAreSpokenAsSoonAsTheyAreComplete() {
        val b = SentenceBuffer(minChars = 5)
        assertEquals(emptyList<String>(), b.add("It is sunny"))
        assertEquals(listOf("It is sunny today."), b.add(" today. Tomor"))
        assertEquals(emptyList<String>(), b.add("row, rain"))
        assertEquals("Tomorrow, rain", b.flush())
    }
}
