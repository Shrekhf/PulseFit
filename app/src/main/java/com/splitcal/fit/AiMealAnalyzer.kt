package com.splitcal.fit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Gemini REST analysis with a clear, deterministic offline fallback. */
class AiMealAnalyzer {
    suspend fun analyze(
        context: Context,
        apiKey: String,
        prompt: String,
        bitmap: Bitmap? = null,
        imageUri: Uri? = null,
        selectedModel: String = GeminiApiClient.DEFAULT_MODEL
    ): MealAnalysis {
        if (apiKey.isBlank()) return fallback(prompt, "No Gemini API key is saved. Enter it in Settings and tap Save settings & targets.")
        return try {
            val instructions = "Analyze the meal photo and user's meal description/customizations. Estimate edible portions and nutrition. Return JSON with mealTitle (string), confidence (integer 0-100), and ingredients (array of objects with name, portion, calories, protein, carbs, fat as numbers, and included as boolean). User prompt: ${prompt.ifBlank { "Identify the pictured meal." }}"
            val response = GeminiApiClient(context).generateContent(apiKey.trim(), selectedModel, instructions, bitmap, imageUri)
            parseResponse(response.text).copy(usedGemini = true, activeModel = response.modelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fallback(prompt, describeFailure(failure))
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
        require(ingredients.isNotEmpty()) { "Gemini response contained no ingredients." }
        return MealAnalysis(root.optString("mealTitle", "Meal estimate"), root.optInt("confidence", 65).coerceIn(0, 100), ingredients)
    }

    private fun fallback(prompt: String, reason: String) = MealAnalysis("Offline meal estimate", 45, parseMealOffline(prompt), fallbackReason = reason)

    private fun describeFailure(failure: Exception): String {
        if (failure is GeminiRestException) {
            val detail = failure.message.orEmpty().substringAfter(": ", failure.message.orEmpty()).take(220)
            return when (failure.statusCode) {
                400, 401, 403 -> "Gemini rejected the generation request (HTTP ${failure.statusCode}): $detail"
                404 -> "Gemini could not find a model that can analyze this request (HTTP 404). Test & Auto-Detect Model checks generation access now."
                429 -> "Gemini quota or rate limit reached (HTTP 429): $detail"
                in 500..599 -> "Gemini is temporarily unavailable (HTTP ${failure.statusCode}): $detail"
                else -> "Gemini request failed (HTTP ${failure.statusCode}): $detail"
            }
        }
        val message = generateSequence(failure as Throwable?) { it.cause }
            .flatMap { sequenceOf(it.javaClass.simpleName, it.message.orEmpty()) }
            .joinToString(" ").lowercase()
        return when {
            "unknownhost" in message || "unable to resolve" in message || "network" in message || "timeout" in message -> "No connection to Gemini. Check internet access and try again."
            "api_key_invalid" in message || "api key not valid" in message || "invalid api key" in message || "unauthenticated" in message || "http 400" in message || "http 401" in message || "http 403" in message -> "Gemini rejected this API key or request. Check the key and Gemini API access in Google AI Studio."
            "resource_exhausted" in message || "quota" in message || "429" in message -> "Gemini quota or rate limit is unavailable right now. Check quota and try later."
            else -> "Gemini request failed (${failure.javaClass.simpleName}). Check the API key, internet connection, and model access."
        }
    }
}
