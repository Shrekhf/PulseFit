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
import kotlin.math.roundToInt

data class IngredientDraft(
    val name: String, val kcal: Int, val p: Int, val c: Int, val f: Int,
    val included: Boolean = true, val multiplier: Float = 1f, val portion: String = "1 serving"
)
data class MealAnalysis(val mealTitle: String, val confidence: Int, val ingredients: List<IngredientDraft>, val usedGemini: Boolean = false, val fallbackReason: String? = null, val activeModel: String? = null)
data class MealScannerState(val title: String = "My meal", val confidence: Int = 0, val ingredients: List<IngredientDraft> = emptyList(), val analyzing: Boolean = false, val error: String? = null, val savings: Int = 0, val syncStatus: String? = null, val editingMealId: String? = null, val editingSlot: String? = null, val analysisStatus: String? = null, val editingExternalRecordId: String? = null, val editingHealthConnectRecordId: String? = null)

class MealViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = SplitCalDatabase.get(application).dao()
    private val healthSyncBridge = HealthSyncBridge(application)
    private val healthConnect = HealthConnectManager(application)
    private val preferences = application.splitCalPrefs
    private var selectedDate: String = LocalDate.now().toString()
    private var lastLaunchTimestamp: java.time.Instant? = null
    private var syncingNutrition = false
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
        _state.value = MealScannerState(meal.name, 100, rows.ifEmpty { estimateImportedIngredients(meal) }, editingMealId = meal.id, editingSlot = meal.slot, editingExternalRecordId = meal.externalRecordId, editingHealthConnectRecordId = meal.healthConnectRecordId)
    }
    fun markSamsungHealthLaunch() { lastLaunchTimestamp = java.time.Instant.now() }
    fun syncMealsFromSamsungHealth() = viewModelScope.launch {
        if (syncingNutrition || selectedDate != LocalDate.now().toString()) return@launch
        syncingNutrition = true
        try {
            val readPermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.NutritionRecord::class)
            if (readPermission !in healthConnect.grantedPermissions()) {
                _state.value = _state.value.copy(syncStatus = "Allow Health Connect nutrition access to import Samsung Health meals")
                return@launch
            }
            val preferSamsung = preferences.data.first()[PREFER_SAMSUNG_HEALTH_ORIGIN] ?: true
            val records = healthConnect.readSamsungHealthNutritionForToday(preferSamsung)
            val launchedAt = lastLaunchTimestamp
            val candidates = records.filter { record ->
                record.metadata.dataOrigin.packageName != "com.splitcal.fit" &&
                    (launchedAt == null || !record.startTime.isBefore(launchedAt))
            }
            var imported = 0
            candidates.forEach { record ->
                val externalId = record.metadata.id
                if (dao.mealByExternalRecordId(externalId) == null) {
                    val name = record.name?.takeIf { it.isNotBlank() } ?: "Samsung Health Scanned Meal"
                    val meal = MealEntity(date = LocalDate.now().toString(), slot = mealSlot(record.mealType), name = name,
                        kcal = record.energy?.inKilocalories?.roundToInt() ?: 0,
                        protein = record.protein?.inGrams?.roundToInt() ?: 0,
                        carbs = record.totalCarbohydrate?.inGrams?.roundToInt() ?: 0,
                        fat = record.totalFat?.inGrams?.roundToInt() ?: 0, externalRecordId = externalId, externalDataOrigin = record.metadata.dataOrigin.packageName)
                    dao.insertMeal(meal)
                    dao.insertIngredients(estimateImportedIngredients(meal).map { IngredientItem(mealId = meal.id, name = it.name, baseKcal = it.kcal, baseProtein = it.p, baseCarbs = it.c, baseFat = it.f) })
                    imported++
                }
            }
            lastLaunchTimestamp = null
            _state.value = _state.value.copy(syncStatus = if (imported > 0) "Imported $imported meal${if (imported == 1) "" else "s"} from Samsung Health · ingredient rows are estimates" else "Samsung Health sync complete · no new meals found")
        } catch (error: Exception) {
            _state.value = _state.value.copy(syncStatus = "Samsung Health meal sync unavailable · ${error.message ?: "check Health Connect access"}")
        } finally { syncingNutrition = false }
    }
    fun deleteMeal(id: String) = viewModelScope.launch { dao.deleteMeal(id) }
    fun setAnalyzing(value: Boolean) = mutate { copy(analyzing = value, error = null) }
    fun setAnalysis(analysis: MealAnalysis) { _state.value = MealScannerState(analysis.mealTitle, analysis.confidence, analysis.ingredients, analysisStatus = if (analysis.usedGemini) "Analyzed with ${analysis.activeModel ?: "Gemini"}" else "Offline estimate · ${analysis.fallbackReason ?: "Gemini unavailable"}") }
    suspend fun analyzeMeal(context: android.content.Context, prompt: String, bitmap: android.graphics.Bitmap?): MealAnalysis {
        val saved = preferences.data.first()
        val apiKey = saved[androidx.datastore.preferences.core.stringPreferencesKey("gemini_key")].orEmpty().trim()
        val selectedModel = saved[androidx.datastore.preferences.core.stringPreferencesKey("gemini_model")] ?: GeminiApiClient.DEFAULT_MODEL
        val analysis = AiMealAnalyzer().analyze(context, apiKey, prompt, bitmap, selectedModel = selectedModel)
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
        var meal = MealEntity(id = s.editingMealId ?: java.util.UUID.randomUUID().toString(), date = selectedDate, slot = slot, name = s.title, kcal = total.kcal, protein = total.protein, carbs = total.carbs, fat = total.fat, externalRecordId = s.editingExternalRecordId, healthConnectRecordId = s.editingHealthConnectRecordId)
        if (s.editingMealId != null) { dao.updateMeal(meal); dao.deleteIngredientsForMeal(meal.id) } else dao.insertMeal(meal)
        dao.insertIngredients(s.ingredients.map { IngredientItem(mealId = meal.id, name = it.name, baseKcal = it.kcal, baseProtein = it.p, baseCarbs = it.c, baseFat = it.f, multiplier = it.multiplier, included = it.included) })
        var synced = false
        var status: String
        if (s.editingExternalRecordId != null) {
            val writtenId = healthSyncBridge.writeEditedImportedMeal(s.title, slot, total.kcal, total.protein, total.carbs, total.fat, s.editingHealthConnectRecordId)
            if (writtenId != null) { meal = meal.copy(healthConnectRecordId = writtenId); dao.updateMeal(meal); synced = true }
            status = if (synced) "SplitCal update synced · original Samsung Health record remains unchanged" else "Updated in SplitCal · Health Connect write unavailable"
        } else {
            synced = s.editingMealId == null && selectedDate == LocalDate.now().toString() && runCatching { healthSyncBridge.writeMealToSamsungHealth(s.title, slot, total.kcal, total.protein, total.carbs, total.fat) }.getOrDefault(false)
            status = if (synced) "Synced to Room + Samsung Health" else "Saved to Room · Health Connect sync unavailable or not permitted"
        }
        _state.value = _state.value.copy(syncStatus = status, editingMealId = null, editingExternalRecordId = null, editingHealthConnectRecordId = null)
        onDone(status)
    }
    private fun mealSlot(type: Int): String = when (type) { 1 -> "Breakfast"; 2 -> "Lunch"; 3 -> "Dinner"; else -> "Snack" }
    private fun estimateImportedIngredients(meal: MealEntity): List<IngredientDraft> {
        val guess = parseMealOffline(meal.name)
        fun scaled(selector: (IngredientDraft) -> Int, total: Int): List<Int> {
            val sum = guess.sumOf(selector)
            if (sum <= 0) return guess.mapIndexed { i, _ -> if (i == 0) total else 0 }
            val scaled = guess.map { (selector(it).toFloat() * total / sum).roundToInt() }.toMutableList()
            scaled[scaled.lastIndex] += total - scaled.sum()
            return scaled
        }
        val kcal = scaled({ it.kcal }, meal.kcal); val protein = scaled({ it.p }, meal.protein); val carbs = scaled({ it.c }, meal.carbs); val fat = scaled({ it.f }, meal.fat)
        return guess.mapIndexed { i, item ->
            val adjustedName = when {
                item.name == "Rice / grain / bread" && "pita" in meal.name.lowercase() -> "Pita / wrap"
                item.name == "Rice / grain / bread" && ("rice" in meal.name.lowercase() || "greens" in meal.name.lowercase()) -> "Rice and grains"
                else -> item.name
            }
            item.copy(name = adjustedName, kcal = kcal[i], p = protein[i], c = carbs[i], f = fat[i])
        }
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
