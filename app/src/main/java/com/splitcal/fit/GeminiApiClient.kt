package com.splitcal.fit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Small direct REST client shared by meal analysis and the workout coach. */
class GeminiApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = (appContext as android.app.Application).splitCalPrefs

    data class Generation(val text: String, val modelId: String)

    suspend fun generateContent(apiKey: String, selectedModel: String, prompt: String, bitmap: Bitmap? = null, imageUri: Uri? = null): Generation = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "No Gemini API key is saved. Enter it in Settings and tap Save settings & targets." }
        val selected = selectedModel.trim().ifBlank { DEFAULT_MODEL }
        val cached = preferences.data.first()[CACHED_MODEL_KEY].orEmpty()
        val candidates = (listOf(selected) + FALLBACK_MODELS + listOf(cached)).map(::normalizeModelId).filter { it.isNotBlank() }.distinct()
        for (model in candidates) {
            val response = postGenerate(key, model, prompt, bitmap, imageUri)
            if (response.first == 404) continue
            if (response.first !in 200..299) throw apiException(response.first, response.second)
            val text = parseGeneratedText(response.second)
            cacheModel(model)
            return@withContext Generation(text, model)
        }

        val discovered = listModels(key).firstOrNull()
            ?: error("Google returned no models that support generateContent for this API key.")
        val response = postGenerate(key, discovered, prompt, bitmap, imageUri)
        if (response.first !in 200..299) throw apiException(response.first, response.second)
        val text = parseGeneratedText(response.second)
        cacheModel(discovered)
        Generation(text, discovered)
    }

    /** Validates the key with models.list and selects the preferred model if it supports generation. */
    suspend fun testAndDiscoverModel(apiKey: String, preferredModel: String): String = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Enter an API key first." }
        val models = listModels(key)
        val selected = normalizeModelId(preferredModel)
        val candidates = (listOf(selected) + FALLBACK_MODELS + models).map(::normalizeModelId).distinct()
        var lastModelError: GeminiRestException? = null
        for (model in candidates) {
            if (models.none { it.equals(model, ignoreCase = true) }) continue
            val (status, body) = postGenerate(key, model, "Return this JSON exactly: {\"ok\":true}", bitmap = null, imageUri = null)
            if (status == 404) {
                lastModelError = apiException(status, body)
                continue
            }
            if (status !in 200..299) throw apiException(status, body)
            parseGeneratedText(body)
            cacheModel(model)
            return@withContext model
        }
        throw lastModelError ?: IllegalStateException("This API key has no models supporting generateContent.")
    }

    private suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        var pageToken: String? = null
        repeat(5) {
            val query = buildString {
                append("key=").append(encode(key))
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val (status, body) = request("GET", "$BASE_URL/models?$query")
            if (status !in 200..299) throw apiException(status, body)
            val root = JSONObject(body)
            val rows = root.optJSONArray("models") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val model = rows.optJSONObject(index) ?: continue
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
                    val name = model.optString("name").removePrefix("models/").trim()
                    if (name.isNotEmpty()) result += name
                }
            }
            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
            if (pageToken == null) return@withContext result.distinct()
        }
        result.distinct()
    }

    private suspend fun postGenerate(key: String, model: String, prompt: String, bitmap: Bitmap?, imageUri: Uri?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val image = bitmap ?: imageUri?.let { uri ->
            appContext.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: error("Could not open selected meal photo.")
        }
        val parts = JSONArray()
        if (image != null) {
            val encoded = encodeJpeg(image)
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", encoded)))
        }
        parts.put(JSONObject().put("text", prompt.trim().ifBlank { "Estimate this meal's ingredients and nutrition." }))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val url = "$BASE_URL/models/${encode(normalizeModelId(model))}:generateContent?key=${encode(key)}"
        request("POST", url, body.toString())
    }

    private suspend fun request(method: String, address: String, body: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeneratedText(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: error("Gemini returned no candidates: ${root.optJSONObject("promptFeedback")?.optString("blockReason") ?: "response blocked or empty"}.")
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: error("Gemini response did not contain text content.")
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("\n")
        return text.ifBlank { error("Gemini returned an empty response.") }
    }

    private fun apiException(status: Int, body: String): GeminiRestException {
        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        val message = when (status) {
            400, 401, 403 -> "Gemini rejected the API key or request${detail.takeIf(String::isNotBlank)?.let { ": $it" } ?: "."}"
            404 -> "Gemini model not found."
            429 -> "Gemini quota or rate limit exceeded${detail.takeIf(String::isNotBlank)?.let { ": $it" } ?: "."}"
            else -> "Gemini API returned HTTP $status${detail.takeIf(String::isNotBlank)?.let { ": $it" } ?: "."}"
        }
        return GeminiRestException(status, message)
    }

    private fun encodeJpeg(source: Bitmap): String {
        val maxEdge = maxOf(source.width, source.height)
        val scaled = if (maxEdge > 1024) {
            val ratio = 1024f / maxEdge
            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1), true)
        } else source
        return ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            if (scaled !== source) scaled.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    }

    private suspend fun cacheModel(model: String) {
        preferences.edit { it[CACHED_MODEL_KEY] = model }
    }

    private fun normalizeModelId(model: String): String = model.trim().removePrefix("models/").removePrefix("/")
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        val MODEL_OPTIONS = listOf(DEFAULT_MODEL, "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-4-hydrogen", "gemini-2.5-flash-lite")
        val FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-4-hydrogen", "gemini-2.5-flash-lite")
        val CACHED_MODEL_KEY = stringPreferencesKey("gemini_cached_model")
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

class GeminiRestException(val statusCode: Int, message: String) : Exception(message)
