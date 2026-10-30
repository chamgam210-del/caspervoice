package com.casper.voice.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.casper.voice.KeySource
import com.casper.voice.Responder
import com.casper.voice.SpeechSettings
import com.casper.voice.TokenSource
import com.casper.voice.TranscriptionSettings
import com.casper.voice.VoiceAgent
import com.casper.voice.VoiceEvent
import com.casper.voice.audio.AudioRoute
import com.casper.voice.chat.ChatSettings
import com.casper.voice.chat.OpenAiChat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * A talk-back demo of the voice library, and the way to try it by hand:
 * pick the ears, the voice and who answers, press Start and talk. Every
 * event the library reports is listed with its time, and the numbers it
 * keeps are shown underneath.
 *
 * Keys typed here are kept in this app's private preferences. A real app
 * should fetch short-lived keys from its own server instead.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private var agent: VoiceAgent? = null
    private var watch: Job? = null

    private lateinit var hearing: Spinner
    private lateinit var hearingModel: EditText
    private lateinit var hearingUrl: EditText
    private lateinit var speaking: Spinner
    private lateinit var voice: EditText
    private lateinit var reply: Spinner
    private lateinit var route: Spinner
    private lateinit var language: EditText
    private val keyFields = LinkedHashMap<String, EditText>()
    private lateinit var start: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
        restore()
    }

    override fun onDestroy() {
        agent?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggle() else note("microphone permission refused")
    }

    private fun toggle() {
        val running = agent
        if (running != null) {
            scope.launch { stop(running) }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        save()
        scope.launch { begin() }
    }

    private suspend fun begin() {
        val keys = keyFields.mapValues { it.value.text.toString().trim() }.filterValues { it.isNotEmpty() }
        val keySource = KeySource { provider -> keys[keyNameFor(provider)] }
        val lang = language.text.toString().trim().ifEmpty { null }
        val ears = TranscriptionSettings(
            provider = hearing.selectedItem as String,
            baseUrl = hearingUrl.text.toString().trim().ifEmpty { TranscriptionSettings.OPENAI_URL },
            model = hearingModel.text.toString().trim().ifEmpty { TranscriptionSettings.OPENAI_MODEL },
            language = lang
        )
        val mouth = SpeechSettings(
            provider = speaking.selectedItem as String,
            voice = voice.text.toString().trim().ifEmpty { SpeechSettings.OPENAI_VOICE }
        )
        val answer: Responder? = when (reply.selectedItem as String) {
            REPLY_ECHO -> Responder.of { heard -> "You said: $heard" }
            REPLY_CHAT -> OpenAiChat({ ChatSettings() }, TokenSource { keys["openai"] })
            else -> null
        }
        val a = try {
            VoiceAgent.create(this) {
                this.keys = keySource
                this.hearing = ears
                this.speaking = mouth
                responder = answer
                route = AudioRoute.valueOf(this@MainActivity.route.selectedItem as String)
                log = { line -> runOnUiThread { note(line) } }
            }
        } catch (e: Exception) {
            note("could not set up: ${e.message}")
            return
        }
        agent = a
        start.text = "Starting…"
        start.isEnabled = false
        watch = scope.launch { a.events.collect { show(it) } }
        if (!a.start()) {
            note("could not open the microphone")
            stop(a)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        start.text = "Stop"
        start.isEnabled = true
        note("listening: hearing ${ears.provider}, speaking ${mouth.provider}, reply ${reply.selectedItem}")
        if (answer == null) note("no reply set: it only transcribes")
        scope.launch {
            while (agent === a) {
                val s = a.stats
                status.text = "heard ${s.heard} · barge-ins ${s.bargeIns} · false ${s.falseInterruptions} · " +
                    "echoes dropped ${s.echoesDropped}\nfirst sound ${ms(s.firstSoundMs)} · text after speech ${ms(s.transcribeMs)}"
                delay(500)
            }
        }
    }

    private suspend fun stop(a: VoiceAgent) {
        a.stop()
        a.close()
        watch?.cancel()
        agent = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        start.text = "Start"
        start.isEnabled = true
        note("stopped")
    }

    private fun show(e: VoiceEvent) {
        val line = when (e) {
            is VoiceEvent.Partial -> return
            is VoiceEvent.Heard -> "YOU: ${e.text}"
            is VoiceEvent.Interrupted -> "cut off; heard: ${e.heard}"
            is VoiceEvent.Resumed -> "false barge-in; resuming from: ${e.from}"
            is VoiceEvent.Failed -> "FAILED: ${e.reason}"
            else -> e.toString()
        }
        note(line)
    }

    private fun ms(v: Long) = if (v < 0) "–" else "${v} ms"

    private fun note(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.append("$time  $text\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- settings kept between runs ----

    private val prefs get() = getSharedPreferences("sample", MODE_PRIVATE)

    private fun save() {
        prefs.edit().apply {
            putInt("hearing", hearing.selectedItemPosition)
            putInt("speaking", speaking.selectedItemPosition)
            putInt("reply", reply.selectedItemPosition)
            putInt("route", route.selectedItemPosition)
            putString("hearingModel", hearingModel.text.toString())
            putString("hearingUrl", hearingUrl.text.toString())
            putString("voice", voice.text.toString())
            putString("language", language.text.toString())
            keyFields.forEach { (name, field) -> putString("key.$name", field.text.toString()) }
        }.apply()
    }

    private fun restore() {
        val p = prefs
        hearing.setSelection(p.getInt("hearing", 0))
        speaking.setSelection(p.getInt("speaking", 0))
        reply.setSelection(p.getInt("reply", 0))
        route.setSelection(p.getInt("route", 0))
        hearingModel.setText(p.getString("hearingModel", ""))
        hearingUrl.setText(p.getString("hearingUrl", ""))
        voice.setText(p.getString("voice", ""))
        language.setText(p.getString("language", "en"))
        val bundled = bundledKeys()
        keyFields.forEach { (name, field) ->
            field.setText(p.getString("key.$name", null)?.takeIf { it.isNotEmpty() } ?: bundled[name].orEmpty())
        }
    }

    /** Debug builds may carry an env.properties; release builds never do. */
    private fun bundledKeys(): Map<String, String> {
        val props = Properties()
        runCatching { assets.open("env.properties").use { props.load(it) } }
        return mapOf(
            "openai" to "OPENAI_API_KEY",
            "elevenlabs" to "ELEVENLABS_API_KEY",
            "deepgram" to "DEEPGRAM_API_KEY",
            "groq" to "GROQ_API_KEY"
        ).mapValues { props.getProperty(it.value)?.trim().orEmpty() }
    }

    private fun keyNameFor(provider: String) = when (provider) {
        "openai-compatible" -> "openai"
        else -> provider
    }

    // ---- layout, in code so the sample has no resources to read ----

    private fun layout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 18, 0, 4)
        }.also { column.addView(it) }
        fun spinner(vararg items: String) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items.toList())
        }.also { column.addView(it) }
        fun field(hint: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            textSize = 14f
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { column.addView(it) }

        label("Hearing")
        hearing = spinner("openai", "openai-compatible", "groq", "deepgram")
        hearingModel = field("model (blank for the provider's default)")
        hearingUrl = field("base URL (for openai-compatible, e.g. http://192.168.1.5:8000/v1)")
        language = field("language, e.g. en (blank to detect)")
        label("Speaking")
        speaking = spinner("system", "openai", "elevenlabs", "openai-compatible")
        voice = field("voice (blank for the provider's default)")
        label("Reply")
        reply = spinner(REPLY_ECHO, REPLY_CHAT, REPLY_NONE)
        label("Audio route")
        route = spinner(*AudioRoute.entries.map { it.name }.toTypedArray())
        label("Keys (kept on this phone only)")
        for (name in listOf("openai", "elevenlabs", "deepgram", "groq")) keyFields[name] = field("$name key", secret = true)

        start = Button(this).apply {
            text = "Start"
            setOnClickListener { toggle() }
        }
        status = TextView(this).apply { setPadding(0, 12, 0, 12) }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }

        val settings = ScrollView(this).apply { addView(column) }
        logScroll = ScrollView(this).apply { addView(log) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settings, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(start)
            addView(status)
            addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setPadding(16, 0, 16, 0)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(16, bars.top, 16, bars.bottom)
            insets
        }
        hearing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                hearingUrl.visibility = if (hearing.selectedItem == "openai-compatible") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return root
    }

    private companion object {
        const val REPLY_ECHO = "say back what it heard"
        const val REPLY_CHAT = "OpenAI chat model"
        const val REPLY_NONE = "none (transcribe only)"
    }
}
