package com.splitcal.fit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Parses Gemini's structured meal response and always supplies a usable offline estimate on failure. */
class AiMealAnalyzer {
    suspend fun analyze(
        context: Context,
        apiKey: String,
        prompt: String,
        bitmap: Bitmap? = null,
        imageUri: Uri? = null,
        selectedModel: String = GeminiApiClient.DEFAULT_MODEL
    ): MealAnalysis {
        if (apiKey.trim().isEmpty()) {
            return offline(prompt, "No Gemini API key is saved. Enter it in Settings and tap Save settings & targets.")
        }
        return try {
            val instructions = """
                Analyze this meal. Use the photo when supplied, and use the user's text to identify foods, portion sizes, and requested substitutions or exclusions.
                Return only a JSON object with this exact shape:
                {"mealTitle":"string","confidence":0,"ingredients":[{"name":"string","portion":"string","calories":0,"protein":0,"carbs":0,"fat":0,"included":true}]}
                Include one row per ingredient. Use non-negative integer nutrition values per row. Mark omitted or explicitly removed ingredients included=false.
                User description and customization: ${prompt.trim().ifBlank { "Identify and estimate the pictured meal." }}
            """.trimIndent()
            val result = GeminiApiClient(context).generateContent(apiKey.trim(), selectedModel, instructions, bitmap, imageUri)
            parseResponse(result.text).copy(usedGemini = true, activeModel = result.modelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            offline(prompt, describeFailure(failure))
        }
    }

    internal fun parseResponse(raw: String): MealAnalysis {
        val json = extractJson(raw)
        val title = json.optString("mealTitle").trim().ifBlank { "Meal estimate" }
        val confidence = number(json.opt("confidence"), 65).coerceIn(0, 100)
        val rows = json.optJSONArray("ingredients") ?: throw JSONException("Gemini response is missing the ingredients array.")
        val ingredients = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val name = row.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    IngredientItem(
                        name = name,
                        portion = row.optString("portion").trim().ifBlank { "1 serving" },
                        baseCalories = number(row.opt("calories"), 0).coerceAtLeast(0),
                        baseProtein = number(row.opt("protein"), 0).coerceAtLeast(0),
                        baseCarbs = number(row.opt("carbs"), 0).coerceAtLeast(0),
                        baseFat = number(row.opt("fat"), 0).coerceAtLeast(0),
                        included = row.optBoolean("included", true)
                    )
                )
            }
        }
        if (ingredients.isEmpty()) throw JSONException("Gemini response has no usable ingredient rows.")
        return MealAnalysis(title, confidence, ingredients)
    }

    private fun extractJson(raw: String): JSONObject {
        val clean = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) throw JSONException("Gemini did not return a JSON object.")
        return JSONObject(clean.substring(start, end + 1))
    }

    private fun number(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }?.toInt() ?: fallback
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt() ?: fallback
        else -> fallback
    }

    private fun offline(prompt: String, reason: String) = MealAnalysis(
        mealTitle = "Offline meal estimate",
        confidence = 45,
        ingredients = parseMealOffline(prompt),
        fallbackReason = reason
    )

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
            "api_key_invalid" in message || "api key not valid" in message || "invalid api key" in message || "unauthenticated" in message -> "Gemini rejected this API key. Check Gemini API access in Google AI Studio."
            "resource_exhausted" in message || "quota" in message || "429" in message -> "Gemini quota or rate limit is unavailable right now. Check quota and try later."
            failure is JSONException -> "Gemini responded, but its meal data was not valid JSON. Try again or edit the offline estimate."
            else -> "Gemini request failed (${failure.javaClass.simpleName}): ${failure.message.orEmpty().take(180)}"
        }
    }
}
