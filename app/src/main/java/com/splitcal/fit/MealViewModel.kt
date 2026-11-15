package com.splitcal.fit

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

data class IngredientDraft(
    val name: String, val kcal: Int, val p: Int, val c: Int, val f: Int,
    val included: Boolean = true, val multiplier: Float = 1f, val portion: String = "1 serving"
)
data class MealAnalysis(val mealTitle: String, val confidence: Int, val ingredients: List<IngredientDraft>, val usedGemini: Boolean = false, val fallbackReason: String? = null)
data class MealScannerState(val title: String = "My meal", val confidence: Int = 0, val ingredients: List<IngredientDraft> = emptyList(), val analyzing: Boolean = false, val error: String? = null, val savings: Int = 0, val syncStatus: String? = null, val editingMealId: String? = null, val editingSlot: String? = null, val analysisStatus: String? = null)

class MealViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = SplitCalDatabase.get(application).dao()
    private val healthSyncBridge = HealthSyncBridge(application)
    private val preferences = application.splitCalPrefs
    private var selectedDate: String = LocalDate.now().toString()
    private val _state = MutableStateFlow(MealScannerState(ingredients = parseMealOffline("chicken bowl with greens, rice, beans and sauce")))
    val state: StateFlow<MealScannerState> = _state.asStateFlow()
    fun setDate(date: String) { if (runCatching { LocalDate.parse(date) }.isSuccess) selectedDate = date }
    fun updateTitle(value: String) = mutate { copy(title = value) }

    fun updateIngredient(index: Int, ingredient: IngredientDraft) = mutate { copy(ingredients = ingredients.toMutableList().also { it[index] = ingredient }) }
    fun loadPreset(preset: MealPreset) { _state.value = MealScannerState(preset.title, 100, preset.ingredients) }
    fun loadMealForEdit(id: String) = viewModelScope.launch {
        val meal = dao.mealOnce(id) ?: return@launch
        val rows = dao.ingredientsOnce(id).map { IngredientDraft(it.name, it.baseKcal, it.baseProtein, it.baseCarbs, it.baseFat, it.included, it.multiplier) }
        selectedDate = meal.date
        _state.value = MealScannerState(meal.name, 100, rows, editingMealId = meal.id, editingSlot = meal.slot)
    }
    fun deleteMeal(id: String) = viewModelScope.launch { dao.deleteMeal(id) }
    fun setAnalyzing(value: Boolean) = mutate { copy(analyzing = value, error = null) }
    fun setAnalysis(analysis: MealAnalysis) { _state.value = MealScannerState(analysis.mealTitle, analysis.confidence, analysis.ingredients, analysisStatus = if (analysis.usedGemini) "Analyzed with Gemini 2.5 Flash" else "Offline estimate · ${analysis.fallbackReason ?: "Gemini unavailable"}") }
    suspend fun analyzeMeal(context: android.content.Context, prompt: String, bitmap: android.graphics.Bitmap?): MealAnalysis {
        val apiKey = preferences.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("gemini_key")].orEmpty().trim()
        val analysis = AiMealAnalyzer().analyze(context, apiKey, prompt, bitmap)
        setAnalysis(analysis)
        return analysis
    }
    fun fail(message: String) = mutate { copy(analyzing = false, error = message) }
    fun applyModification(label: String) = mutate {
        val list = ingredients.toMutableList()
        when (label) {
            "Remove Pita" -> { val i = list.indexOfFirst { "pita" in it.name.lowercase() }; if (i >= 0) list[i] = list[i].copy(included = false) }
            "Extra Chicken" -> list += IngredientDraft("Extra grilled chicken", 190, 36, 0, 4)
            "Sub Greens for Rice" -> { val i = list.indexOfFirst { "rice" in it.name.lowercase() }; if (i >= 0) list[i] = list[i].copy(included = false); list += IngredientDraft("Green Blast greens", 45, 3, 8, 0) }
            "Extra Hummus Scoop" -> list += IngredientDraft("Extra hummus scoop", 70, 3, 5, 4)
            "No Heavy Sauce" -> { val i = list.indexOfFirst { "sauce" in it.name.lowercase() || "tzatziki" in it.name.lowercase() }; if (i >= 0) list[i] = list[i].copy(included = false) }
        }
        copy(ingredients = list, savings = when(label) { "Remove Pita" -> 240; "Sub Greens for Rice" -> 185; "No Heavy Sauce" -> 120; else -> 0 })
    }
    fun logMeal(slot: String, onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        val rows = s.ingredients.filter { it.included }
        val total = IngredientTotals(rows.sumOf { (it.kcal * it.multiplier).toInt() }, rows.sumOf { (it.p * it.multiplier).toInt() }, rows.sumOf { (it.c * it.multiplier).toInt() }, rows.sumOf { (it.f * it.multiplier).toInt() })
        val meal = MealEntity(id = s.editingMealId ?: java.util.UUID.randomUUID().toString(), date = selectedDate, slot = slot, name = s.title, kcal = total.kcal, protein = total.protein, carbs = total.carbs, fat = total.fat)
        if (s.editingMealId != null) { dao.updateMeal(meal); dao.deleteIngredientsForMeal(meal.id) } else dao.insertMeal(meal)
        dao.insertIngredients(s.ingredients.map { IngredientItem(mealId = meal.id, name = it.name, baseKcal = it.kcal, baseProtein = it.p, baseCarbs = it.c, baseFat = it.f, multiplier = it.multiplier, included = it.included) })
        val synced = s.editingMealId == null && selectedDate == LocalDate.now().toString() && runCatching { healthSyncBridge.writeMealToSamsungHealth(s.title, slot, total.kcal, total.protein, total.carbs, total.fat) }.getOrDefault(false)
        val status = if (synced) "Synced to Room + Samsung Health" else "Saved to Room · Health Connect sync unavailable or not permitted"
        _state.value = _state.value.copy(syncStatus = status, editingMealId = null)
        onDone(status)
    }
    private inline fun mutate(block: MealScannerState.() -> MealScannerState) { _state.value = _state.value.block() }
}

data class MealPreset(val title: String, val ingredients: List<IngredientDraft>)
object MealPresets {
    val all = listOf(
        MealPreset("Just Salad · Cilantro Lime Chicken Bowl", listOf(IngredientDraft("Romaine & supergreens",45,3,8,0), IngredientDraft("Double oven-roasted chicken",360,66,0,8), IngredientDraft("Black beans & corn",130,7,24,1), IngredientDraft("Avocado",110,1,6,10), IngredientDraft("Grillo's pickles",15,0,3,0), IngredientDraft("Heavy cucumber tzatziki & sriracha",140,3,8,10), IngredientDraft("Spicy Harissa Pita",160,5,30,3,false))),
        MealPreset("Just Salad · Chicken Caesar Bowl", listOf(IngredientDraft("Oven-roasted chicken",280,52,0,6), IngredientDraft("Romaine",25,2,5,0), IngredientDraft("Parmesan",80,7,1,5), IngredientDraft("Cucumber tzatziki",80,3,6,5), IngredientDraft("Wrap tortilla",310,8,52,8,false), IngredientDraft("Coke Zero",0,0,0,0))),
        MealPreset("Juicy Platters · Mixed Platter", listOf(IngredientDraft("Grilled chicken & gyro",420,55,4,20), IngredientDraft("Half rice / half Green Blast",160,5,32,2), IngredientDraft("Extra chicken scoop",190,36,0,4), IngredientDraft("Cabbage & carrot slaw",45,1,10,0), IngredientDraft("Tomatoes & pickled onions",25,1,5,0), IngredientDraft("Classic hummus scoop",190,6,14,12), IngredientDraft("Warm pita",240,8,48,2,false))),
        MealPreset("Bubbakoo's · Chicken Bowl", listOf(IngredientDraft("Grilled chicken",280,52,0,6), IngredientDraft("Brown rice",215,5,45,2), IngredientDraft("Black beans",110,7,20,1), IngredientDraft("Fajita veggies",45,2,9,0), IngredientDraft("Chipotle crema",120,1,3,12), IngredientDraft("Tortilla chips",280,4,36,14,false))),
        MealPreset("Muscle Maker · Godfather Bowl", listOf(IngredientDraft("Grilled chicken breast",280,52,0,6), IngredientDraft("Steamed cauliflower rice",50,4,9,1), IngredientDraft("Turkey bacon",70,8,1,4), IngredientDraft("Mushrooms & roasted red peppers",40,2,8,0), IngredientDraft("Part-skim mozzarella",80,7,1,5), IngredientDraft("Baked sweet potato fries",210,3,39,5))),
        MealPreset("Trattoria · Chicken Parm (No Pasta)", listOf(IngredientDraft("Breaded chicken cutlet with marinara & mozzarella",655,52,35,30), IngredientDraft("Penne pasta",500,17,95,5,false)))
    )
}

fun parseMealOffline(text: String): List<IngredientDraft> {
    val t = text.lowercase(); val out = mutableListOf<IngredientDraft>()
    if ("chicken" in t) out += IngredientDraft("Grilled chicken",280,52,0,6)
    if ("beef" in t || "steak" in t || "gyro" in t) out += IngredientDraft("Beef / gyro",320,38,0,18)
    if ("salmon" in t) out += IngredientDraft("Salmon",300,34,0,18)
    if ("tofu" in t) out += IngredientDraft("Tofu",180,20,8,10)
    if ("green" in t || "salad" in t || "lettuce" in t || "romaine" in t) out += IngredientDraft("Greens base",45,3,8,0)
    if ("rice" in t || "pasta" in t || "bread" in t || "pita" in t) out += IngredientDraft("Rice / grain / bread",210,6,42,2, included = !("no pita" in t || "no bread" in t))
    if ("beans" in t || "corn" in t) out += IngredientDraft("Beans & corn",130,7,24,1)
    if ("avocado" in t) out += IngredientDraft("Avocado",110,1,6,10)
    if ("sauce" in t || "dressing" in t || "hummus" in t || "tzatziki" in t) out += IngredientDraft("Sauce / dressing",120,3,8,9)
    return out.ifEmpty { listOf(IngredientDraft("Mixed meal estimate",450,25,45,18)) }
}
