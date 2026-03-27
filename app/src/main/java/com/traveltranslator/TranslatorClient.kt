package com.traveltranslator

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class TranslatorClient(
    private val baseUrl: String = "https://translate.argosopentech.com"
) {

    fun detectLanguage(text: String): Result<String> {
        return runCatching {
            val body = JSONObject().put("q", text).toString()
            val response = postJson("$baseUrl/detect", body)
            val array = JSONArray(response)
            val best = array.optJSONObject(0)
            val detected = best?.optString("language").orEmpty()
            check(detected.isNotBlank()) { "Language detection returned empty language." }
            detected
        }
    }

    fun translate(text: String, source: String, target: String): Result<String> {
        return runCatching {
            val body = JSONObject()
                .put("q", text)
                .put("source", source)
                .put("target", target)
                .put("format", "text")
                .toString()

            val response = postJson("$baseUrl/translate", body)
            val translated = JSONObject(response).optString("translatedText")
            check(translated.isNotBlank()) { "Translation service returned empty text." }
            translated
        }
    }

    private fun postJson(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        return BufferedReader(connection.inputStream.reader()).use { it.readText() }
    }
}
