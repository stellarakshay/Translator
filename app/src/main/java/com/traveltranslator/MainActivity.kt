package com.traveltranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val translator = TranslatorClient()
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var spokenText: TextView
    private lateinit var targetSpinner: Spinner
    private lateinit var conversationButton: Button
    private lateinit var typeInput: EditText
    private lateinit var typeSendButton: Button

    private var conversationActive = false
    private var processingResult = false
    private var recognizerReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val currentAudit = mutableListOf<ConversationEntry>()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusText.text = if (granted) {
                getString(R.string.status_ready)
            } else {
                getString(R.string.status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        spokenText = findViewById(R.id.spokenText)
        targetSpinner = findViewById(R.id.targetSpinner)
        conversationButton = findViewById(R.id.btnConversation)
        typeInput = findViewById(R.id.typeInput)
        typeSendButton = findViewById(R.id.btnTypeSend)

        setupSpinner()
        setupConversationButton()
        setupTypedInput()

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
        cleanupOldAudits()

        ensureMicPermission()
        statusText.text = getString(R.string.status_ready)
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Greek", "Italian", "French")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetSpinner.adapter = adapter
    }

    private fun setupConversationButton() {
        conversationButton.setOnClickListener {
            if (conversationActive) {
                stopConversation()
            } else {
                startConversation()
            }
        }
    }

    private fun setupTypedInput() {
        typeSendButton.setOnClickListener {
            val input = typeInput.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) {
                statusText.text = getString(R.string.status_typed_empty)
                return@setOnClickListener
            }

            if (processingResult) return@setOnClickListener

            processingResult = true
            heardText.text = input
            typeInput.text?.clear()
            handleSpeech(input, fromTypedInput = true)
        }
    }

    private fun startConversation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = getString(R.string.status_speech_not_available)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensureMicPermission()
            return
        }

        currentAudit.clear()
        conversationActive = true
        conversationButton.text = getString(R.string.stop_conversation)
        startListeningLoop()
    }

    private fun stopConversation() {
        conversationActive = false
        processingResult = false
        conversationButton.text = getString(R.string.start_conversation)
        if (recognizerReady) speechRecognizer.cancel()
        statusText.text = getString(R.string.status_conversation_stopped)

        if (currentAudit.isNotEmpty()) {
            val session = ConversationSession(
                createdAtMs = System.currentTimeMillis(),
                targetLanguageCode = selectedLanguageCode(),
                entries = currentAudit.toList()
            )
            saveSession(session)
            renderAudit(session)
        }
    }

    private fun startListeningLoop() {
        if (!conversationActive || processingResult || !recognizerReady) return

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

        statusText.text = getString(R.string.status_listening)
        speechRecognizer.startListening(intent)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerReady = true
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!conversationActive) return
                processingResult = false
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    recognizerReady = false
                    speechRecognizer.cancel()
                    speechRecognizer.destroy()
                    initSpeechRecognizer()
                }
                statusText.text = getString(R.string.status_listening_retry)
                mainHandler.postDelayed({ startListeningLoop() }, 500)
            }

            override fun onResults(results: Bundle?) {
                if (!conversationActive) return

                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                if (spoken.isNullOrBlank()) {
                    processingResult = false
                    statusText.text = getString(R.string.status_listening_retry)
                    mainHandler.postDelayed({ startListeningLoop() }, 300)
                    return
                }

                processingResult = true
                heardText.text = spoken
                handleSpeech(spoken, fromTypedInput = false)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun handleSpeech(text: String, fromTypedInput: Boolean) {
        statusText.text = getString(R.string.status_processing)
        val targetLanguage = selectedLanguageCode()

        thread {
            val detected = translator.detectLanguage(text).getOrElse { fallbackDetectedLanguage(text) }

            val source = if (detected == targetLanguage) targetLanguage else "en"
            val target = if (source == "en") targetLanguage else "en"

            val translation = translator.translate(text, source, target)

            runOnUiThread {
                translation.onSuccess { translated ->
                    val conversational = makeConversational(translated, target)
                    spokenText.text = conversational

                    currentAudit += ConversationEntry(
                        sourceLanguage = source,
                        targetLanguage = target,
                        sourceText = text,
                        translatedText = conversational
                    )

                    if (fromTypedInput) {
                        processingResult = false
                        statusText.text = getString(R.string.status_manual_ready)
                    }

                    speak(conversational, if (target == "en") Locale.US else selectedLocale())
                }.onFailure {
                    processingResult = false
                    statusText.text = getString(R.string.status_translation_failed)
                    if (conversationActive) mainHandler.postDelayed({ startListeningLoop() }, 400)
                }
            }
        }
    }

    private fun makeConversational(text: String, language: String): String {
        return when (language) {
            "en" -> text
                .replace("I am", "I'm")
                .replace("do not", "don't")
                .replace("cannot", "can't")
                .replace("would not", "wouldn't")
            else -> text
        }
    }

    private fun fallbackDetectedLanguage(text: String): String {
        val hasGreekChars = text.any { it.code in 0x0370..0x03FF }
        if (hasGreekChars) return "el"

        val lower = text.lowercase(Locale.US)
        val frenchHints = listOf("bonjour", "merci", "s'il", "vous", "avec", "pour", "est")
        val italianHints = listOf("ciao", "grazie", "per", "con", "buongiorno", "prego", "come")

        if (frenchHints.any { lower.contains(it) }) return "fr"
        if (italianHints.any { lower.contains(it) }) return "it"
        return "en"
    }

    private fun renderAudit(session: ConversationSession) {
        val englishLog = StringBuilder()
        val localLog = StringBuilder()

        session.entries.forEachIndexed { i, entry ->
            val line = "${i + 1}. "
            val english = if (entry.sourceLanguage == "en") entry.sourceText else entry.translatedText
            val local = if (entry.sourceLanguage == session.targetLanguageCode) entry.sourceText else entry.translatedText
            englishLog.append(line).append(english).append("\n")
            localLog.append(line).append(local).append("\n")
        }

        val auditBody = StringBuilder()
            .append("English side:\n")
            .append(englishLog)
            .append("\n")
            .append(localLanguageName(session.targetLanguageCode))
            .append(" side:\n")
            .append(localLog)

        val summaryEn = buildSummary(session.entries)

        thread {
            val summaryLocal = translator.translate(summaryEn, "en", session.targetLanguageCode)
                .getOrDefault("Summary unavailable in ${localLanguageName(session.targetLanguageCode)}.")

            runOnUiThread {
                val message = auditBody
                    .append("\nAI-style summary (English):\n")
                    .append(summaryEn)
                    .append("\n\nAI-style summary (")
                    .append(localLanguageName(session.targetLanguageCode))
                    .append("):\n")
                    .append(summaryLocal)
                    .toString()

                val textView = TextView(this).apply {
                    text = message
                    setPadding(30, 20, 30, 20)
                    movementMethod = ScrollingMovementMethod()
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.audit_title))
                    .setView(textView)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun buildSummary(entries: List<ConversationEntry>): String {
        if (entries.isEmpty()) return "No summary available."

        val englishTurns = entries.map { if (it.sourceLanguage == "en") it.sourceText else it.translatedText }
        val keyIntent = when {
            englishTurns.any { it.contains("where", true) || it.contains("how do I get", true) || it.contains("direction", true) } ->
                "You were asking for directions."
            englishTurns.any { it.contains("train", true) || it.contains("bus", true) || it.contains("station", true) } ->
                "You discussed transportation details."
            englishTurns.any { it.contains("hotel", true) || it.contains("check in", true) } ->
                "You discussed accommodation details."
            else -> "You had a travel-related conversation."
        }

        val highlights = englishTurns.takeLast(4).joinToString(" ")
        return "$keyIntent Key details: $highlights"
    }

    private fun saveSession(session: ConversationSession) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existing = JSONArray(prefs.getString(KEY_AUDITS, "[]"))

        val entryArray = JSONArray().apply {
            session.entries.forEach {
                put(
                    JSONObject()
                        .put("sourceLanguage", it.sourceLanguage)
                        .put("targetLanguage", it.targetLanguage)
                        .put("sourceText", it.sourceText)
                        .put("translatedText", it.translatedText)
                )
            }
        }

        existing.put(
            JSONObject()
                .put("createdAtMs", session.createdAtMs)
                .put("targetLanguageCode", session.targetLanguageCode)
                .put("entries", entryArray)
        )

        prefs.edit().putString(KEY_AUDITS, existing.toString()).apply()
    }

    private fun cleanupOldAudits() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existing = JSONArray(prefs.getString(KEY_AUDITS, "[]"))
        val filtered = JSONArray()

        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (item.optLong("createdAtMs") >= cutoff) filtered.put(item)
        }

        prefs.edit().putString(KEY_AUDITS, filtered.toString()).apply()
    }

    private fun localLanguageName(code: String): String {
        return when (code) {
            "el" -> "Greek"
            "it" -> "Italian"
            else -> "French"
        }
    }

    private fun speak(text: String, locale: Locale) {
        tts.language = locale
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "conversation-utterance")
    }

    private fun selectedLanguageCode(): String {
        return when (targetSpinner.selectedItemPosition) {
            0 -> "el"
            1 -> "it"
            else -> "fr"
        }
    }

    private fun selectedLocale(): Locale {
        return when (targetSpinner.selectedItemPosition) {
            0 -> Locale("el", "GR")
            1 -> Locale.ITALIAN
            else -> Locale.FRENCH
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            statusText.text = getString(R.string.status_tts_failed)
            return
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { statusText.text = getString(R.string.status_processing) }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    processingResult = false
                    if (conversationActive) {
                        startListeningLoop()
                    } else {
                        statusText.text = getString(R.string.status_ready)
                    }
                }
            }

            @Deprecated("Deprecated in Android framework, still required by abstract class")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    processingResult = false
                    statusText.text = getString(R.string.status_tts_failed)
                    if (conversationActive) startListeningLoop()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationActive = false
        if (recognizerReady) {
            speechRecognizer.destroy()
            recognizerReady = false
        }
        tts.stop()
        tts.shutdown()
    }

    data class ConversationEntry(
        val sourceLanguage: String,
        val targetLanguage: String,
        val sourceText: String,
        val translatedText: String
    )

    data class ConversationSession(
        val createdAtMs: Long,
        val targetLanguageCode: String,
        val entries: List<ConversationEntry>
    )

    companion object {
        private const val PREFS_NAME = "conversation_audits"
        private const val KEY_AUDITS = "audits"
        private const val RETENTION_MS = 2L * 24L * 60L * 60L * 1000L
    }
}
