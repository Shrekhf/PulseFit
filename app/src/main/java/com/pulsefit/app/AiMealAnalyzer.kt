package com.pulsefit.app

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Multimodal Gemini estimate with an offline deterministic NLP fallback. */
class AiMealAnalyzer {
    suspend fun analyze(context: Context, apiKey: String, prompt: String, bitmap: Bitmap? = null, imageUri: Uri? = null): MealAnalysis {
        if (apiKey.isBlank()) return fallback(prompt)
        return runCatching { analyzeWithGemini(context, apiKey, prompt, bitmap, imageUri) }.getOrElse { fallback(prompt) }
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
        return MealAnalysis(root.optString("mealTitle", "Meal estimate"), root.optInt("confidence", 65).coerceIn(0, 100), ingredients)
    }

    private fun fallback(prompt: String) = MealAnalysis("Offline meal estimate", 45, parseMealOffline(prompt))
}
