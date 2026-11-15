package com.splitcal.fit

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Multimodal Gemini estimate with an offline deterministic NLP fallback. */
class AiMealAnalyzer {
    suspend fun analyze(context: Context, apiKey: String, prompt: String, bitmap: Bitmap? = null, imageUri: Uri? = null): MealAnalysis {
        if (apiKey.isBlank()) return fallback(prompt, "No Gemini API key is saved. Save it in Settings and try again.")
        return try {
            analyzeWithGemini(context, apiKey.trim(), prompt, bitmap, imageUri).copy(usedGemini = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fallback(prompt, describeFailure(failure))
        }
    }

    private suspend fun analyzeWithGemini(context: Context, apiKey: String, prompt: String, bitmap: Bitmap?, imageUri: Uri?): MealAnalysis {
      return withContext(Dispatchers.IO) {
        val model = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            generationConfig = GenerationConfig.Builder().apply { responseMimeType = "application/json" }.build()
        )
        val photo = bitmap ?: imageUri?.let { uri -> context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }
        val request = content {
            photo?.let { image(it) }
            text("Analyze the meal photo and user's meal description/customizations. Estimate edible portions and nutrition. Return only JSON: {\"mealTitle\": string, \"confidence\": integer 0-100, \"ingredients\":[{\"name\":string,\"portion\":string,\"calories\":integer,\"protein\":integer,\"carbs\":integer,\"fat\":integer,\"included\":boolean}]}. User prompt: ${prompt.ifBlank { "Identify the pictured meal." }}")
        }
        val response = model.generateContent(request).text ?: error("Empty Gemini response")
        parseResponse(response)
      }
    }

    private fun parseResponse(raw: String): MealAnalysis {
        val jsonText = raw.substringAfter('{', "{").let { "{" + it.substringBeforeLast('}', "}") + "}" }
        val root = JSONObject(jsonText)
        val rows = root.getJSONArray("ingredients")
        val ingredients = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            IngredientDraft(row.optString("name", "Ingredient"), row.optInt("calories"), row.optInt("protein"), row.optInt("carbs"), row.optInt("fat"), row.optBoolean("included", true), portion = row.optString("portion", "1 serving"))
        }
        require(ingredients.isNotEmpty()) { "No ingredients returned" }
        return MealAnalysis(root.optString("mealTitle", "Meal estimate"), root.optInt("confidence", 65).coerceIn(0, 100), ingredients, usedGemini = true)
    }

    private fun fallback(prompt: String, reason: String) = MealAnalysis("Offline meal estimate", 45, parseMealOffline(prompt), fallbackReason = reason)

    private fun describeFailure(failure: Exception): String {
        val message = generateSequence(failure as Throwable?) { it.cause }
            .flatMap { sequenceOf(it.javaClass.simpleName, it.message.orEmpty()) }
            .joinToString(" ").lowercase()
        return when {
            "unknownhost" in message || "unable to resolve" in message || "network" in message || "timeout" in message -> "No connection to Gemini. Check internet access and try again."
            "api_key_invalid" in message || "api key not valid" in message || "invalid api key" in message || "unauthenticated" in message -> "Gemini rejected this API key. Check the key in Settings."
            "resource_exhausted" in message || "quota" in message || "429" in message -> "Gemini quota is unavailable right now. Check the key's API quota and try later."
            "permission_denied" in message || "403" in message -> "Google AI access was denied. Check that this key can use the Gemini API."
            "404" in message || "not found" in message -> "Gemini 2.5 Flash was not found for this request. Check model access and try again."
            else -> "Gemini request failed (${failure.javaClass.simpleName}). Check the key, internet connection, and Google AI API access."
        }
    }
}
