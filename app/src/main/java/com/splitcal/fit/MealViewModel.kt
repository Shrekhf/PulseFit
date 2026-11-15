package com.splitcal.fit

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

data class MealAnalysis(
    val mealTitle: String,
    val confidence: Int,
    val ingredients: List<IngredientItem>,
    val usedGemini: Boolean = false,
    val fallbackReason: String? = null,
    val activeModel: String? = null
)

data class MealPreset(val title: String, val ingredients: List<IngredientItem>)

class MealViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = SplitCalDatabase.get(application).dao()
    private val healthSyncBridge = HealthSyncBridge(application)
    private val healthConnect = HealthConnectManager(application)
    private val preferences = application.splitCalPrefs
    private var selectedDate: String = LocalDate.now().toString()
    private var lastLaunchTimestamp: java.time.Instant? = null
    private var syncingNutrition = false
    private val _state = MutableStateFlow(MealScannerUiState())
    val state: StateFlow<MealScannerUiState> = _state.asStateFlow()

    fun setDate(date: String) {
        if (runCatching { LocalDate.parse(date) }.isSuccess) selectedDate = date
    }

    fun updateTitle(value: String) = _state.update { it.copy(mealTitle = value) }
    fun updateDescription(value: String) = _state.update { it.copy(descriptionInput = value) }
    fun selectSlot(value: String) = _state.update { it.copy(selectedSlot = value) }
    fun setAnalyzing(value: Boolean) = _state.update { it.copy(isAnalyzing = value, statusMessage = if (value) null else it.statusMessage) }
    fun fail(message: String) = _state.update { it.copy(isAnalyzing = false, statusMessage = message) }

    fun updateIngredient(id: String, transform: (IngredientItem) -> IngredientItem) = _state.update { current ->
        current.copy(ingredients = current.ingredients.map { if (it.id == id) transform(it) else it })
    }

    fun updateIngredient(index: Int, ingredient: IngredientItem) = _state.update { current ->
        current.copy(ingredients = current.ingredients.mapIndexed { i, item -> if (i == index) ingredient else item })
    }

    fun addCustomIngredient(name: String, portion: String, calories: Int, protein: Int, carbs: Int, fat: Int) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        _state.update { current ->
            current.copy(ingredients = current.ingredients + IngredientItem(
                name = cleanName,
                portion = portion.trim().ifBlank { "1 serving" },
                baseCalories = calories.coerceAtLeast(0),
                baseProtein = protein.coerceAtLeast(0),
                baseCarbs = carbs.coerceAtLeast(0),
                baseFat = fat.coerceAtLeast(0)
            ))
        }
    }

    fun loadPreset(preset: MealPreset) = _state.update { current ->
        MealScannerUiState(
            mealTitle = preset.title,
            descriptionInput = preset.title,
            selectedSlot = current.selectedSlot,
            confidenceBadge = "Preset · nutrition estimate",
            ingredients = preset.ingredients.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
        )
    }

    fun loadMealForEdit(id: String) = viewModelScope.launch {
        val meal = dao.mealOnce(id) ?: return@launch
        val rows = dao.ingredientsOnce(id).map(MealIngredientEntity::toUiItem)
        selectedDate = meal.date
        _state.update { current ->
            MealScannerUiState(
                mealTitle = meal.name,
                descriptionInput = meal.name,
                selectedSlot = meal.slot,
                confidenceBadge = "Saved meal",
                ingredients = rows.ifEmpty { estimateImportedIngredients(meal) },
                syncStatus = current.syncStatus,
                editingMealId = meal.id,
                editingSlot = meal.slot,
                editingExternalRecordId = meal.externalRecordId,
                editingHealthConnectRecordId = meal.healthConnectRecordId
            )
        }
    }

    fun markSamsungHealthLaunch() { lastLaunchTimestamp = java.time.Instant.now() }

    fun syncMealsFromSamsungHealth() = viewModelScope.launch {
        if (syncingNutrition || selectedDate != LocalDate.now().toString()) return@launch
        syncingNutrition = true
        try {
            val readPermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.NutritionRecord::class)
            if (readPermission !in healthConnect.grantedPermissions()) {
                _state.update { it.copy(syncStatus = "Allow Health Connect nutrition access to import Samsung Health meals") }
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
                    val meal = MealEntity(
                        date = LocalDate.now().toString(),
                        slot = mealSlot(record.mealType),
                        name = name,
                        kcal = record.energy?.inKilocalories?.roundToInt() ?: 0,
                        protein = record.protein?.inGrams?.roundToInt() ?: 0,
                        carbs = record.totalCarbohydrate?.inGrams?.roundToInt() ?: 0,
                        fat = record.totalFat?.inGrams?.roundToInt() ?: 0,
                        externalRecordId = externalId,
                        externalDataOrigin = record.metadata.dataOrigin.packageName
                    )
                    dao.insertMeal(meal)
                    dao.insertIngredients(estimateImportedIngredients(meal).map { it.toEntity(meal.id) })
                    imported++
                }
            }
            lastLaunchTimestamp = null
            _state.update { current -> current.copy(syncStatus = if (imported > 0) "Imported $imported meal${if (imported == 1) "" else "s"} from Samsung Health · ingredient rows are estimates" else "Samsung Health sync complete · no new meals found") }
        } catch (error: Exception) {
            _state.update { it.copy(syncStatus = "Samsung Health meal sync unavailable · ${error.message ?: "check Health Connect access"}") }
        } finally {
            syncingNutrition = false
        }
    }

    fun deleteMeal(id: String) = viewModelScope.launch { dao.deleteMeal(id) }

    fun setAnalysis(analysis: MealAnalysis) = _state.update { current ->
        current.copy(
            mealTitle = analysis.mealTitle,
            confidenceBadge = if (analysis.usedGemini) "${analysis.confidence}% AI Match" else "Offline estimate",
            ingredients = analysis.ingredients,
            isAnalyzing = false,
            statusMessage = if (analysis.usedGemini) "Analyzed with ${analysis.activeModel ?: "Gemini"}" else "Offline estimate · ${analysis.fallbackReason ?: "Gemini unavailable"}",
            activeModifiers = emptySet(),
            usedGemini = analysis.usedGemini,
            editingMealId = null,
            editingExternalRecordId = null,
            editingHealthConnectRecordId = null
        )
    }

    suspend fun analyzeMeal(context: Context, prompt: String, bitmap: Bitmap?): MealAnalysis {
        val saved = preferences.data.first()
        val apiKey = saved[stringPreferencesKey("gemini_key")].orEmpty().trim()
        val selectedModel = saved[stringPreferencesKey("gemini_model")] ?: GeminiApiClient.DEFAULT_MODEL
        val analysis = AiMealAnalyzer().analyze(context, apiKey, prompt, bitmap, selectedModel = selectedModel)
        setAnalysis(analysis)
        return analysis
    }

    fun applyModification(label: String) = _state.update { current ->
        val wasActive = label in current.activeModifiers
        val modifiers = if (wasActive) current.activeModifiers - label else current.activeModifiers + label
        var rows = current.ingredients
        val addOnId = "modifier:${label.lowercase().replace(' ', '_')}"
        when (label) {
            "Remove Pita" -> {
                if (wasActive) rows = rows.filterNot { it.id == addOnId }.map { if ("pita" in it.name.lowercase()) it.copy(included = true) else it }
                else if (rows.any { "pita" in it.name.lowercase() }) rows = rows.map { if ("pita" in it.name.lowercase()) it.copy(included = false) else it }
                else rows = rows + IngredientItem(id = addOnId, name = "Pita (removed)", portion = "1 pita", baseCalories = 240, baseProtein = 8, baseCarbs = 48, baseFat = 2, included = false)
            }
            "Extra Chicken", "Extra Hummus Scoop" -> {
                if (wasActive) rows = rows.filterNot { it.id == addOnId }
                else rows = rows + if (label == "Extra Chicken") IngredientItem(id = addOnId, name = "Extra grilled chicken", portion = "1 extra scoop", baseCalories = 190, baseProtein = 36, baseCarbs = 0, baseFat = 4)
                else IngredientItem(id = addOnId, name = "Extra hummus scoop", portion = "1 extra scoop", baseCalories = 70, baseProtein = 3, baseCarbs = 5, baseFat = 4)
            }
            "Sub Greens for Rice" -> {
                if (wasActive) rows = rows.filterNot { it.id == addOnId }.map { if ("rice" in it.name.lowercase()) it.copy(included = true) else it }
                else {
                    rows = rows.map { if ("rice" in it.name.lowercase()) it.copy(included = false) else it }
                    rows = rows + IngredientItem(id = addOnId, name = "Green Blast greens", portion = "1 greens base", baseCalories = 45, baseProtein = 3, baseCarbs = 8, baseFat = 0)
                }
            }
            "No Heavy Sauce" -> {
                val isSauce: (IngredientItem) -> Boolean = { "sauce" in it.name.lowercase() || "tzatziki" in it.name.lowercase() || "dressing" in it.name.lowercase() }
                if (wasActive) rows = rows.filterNot { it.id == addOnId }.map { if (isSauce(it)) it.copy(included = true) else it }
                else if (rows.any(isSauce)) rows = rows.map { if (isSauce(it)) it.copy(included = false) else it }
                else rows = rows + IngredientItem(id = addOnId, name = "Heavy sauce (removed)", portion = "1 serving", baseCalories = 120, baseProtein = 3, baseCarbs = 8, baseFat = 9, included = false)
            }
        }
        current.copy(ingredients = rows, activeModifiers = modifiers)
    }

    fun logMeal(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        val total = IngredientTotals(s.totalCalories, s.totalProtein, s.totalCarbs, s.totalFat)
        var meal = MealEntity(
            id = s.editingMealId ?: java.util.UUID.randomUUID().toString(),
            date = selectedDate,
            slot = s.selectedSlot,
            name = s.mealTitle,
            kcal = total.kcal,
            protein = total.protein,
            carbs = total.carbs,
            fat = total.fat,
            externalRecordId = s.editingExternalRecordId,
            healthConnectRecordId = s.editingHealthConnectRecordId
        )
        if (s.editingMealId != null) {
            dao.updateMeal(meal)
            dao.deleteIngredientsForMeal(meal.id)
        } else {
            dao.insertMeal(meal)
        }
        dao.insertIngredients(s.ingredients.map { it.toEntity(meal.id) })

        val synced: Boolean
        val status: String
        if (s.editingExternalRecordId != null) {
            val writtenId = healthSyncBridge.writeEditedImportedMeal(s.mealTitle, s.selectedSlot, total.kcal, total.protein, total.carbs, total.fat, s.editingHealthConnectRecordId)
            if (writtenId != null) {
                meal = meal.copy(healthConnectRecordId = writtenId)
                dao.updateMeal(meal)
            }
            synced = writtenId != null
            status = if (synced) "SplitCal update synced · original Samsung Health record remains unchanged" else "Updated in SplitCal · Health Connect write unavailable"
        } else {
            synced = s.editingMealId == null && selectedDate == LocalDate.now().toString() && runCatching {
                healthSyncBridge.writeMealToSamsungHealth(s.mealTitle, s.selectedSlot, total.kcal, total.protein, total.carbs, total.fat)
            }.getOrDefault(false)
            status = if (synced) "Synced to Room + Samsung Health" else "Saved to Room · Health Connect sync unavailable or not permitted"
        }
        _state.update { it.copy(syncStatus = status, editingMealId = null, editingExternalRecordId = null, editingHealthConnectRecordId = null) }
        onDone(status)
    }

    fun logMeal(slot: String, onDone: (String) -> Unit) {
        _state.update { it.copy(selectedSlot = slot) }
        logMeal(onDone)
    }

    private fun mealSlot(type: Int): String = when (type) { 1 -> "Breakfast"; 2 -> "Lunch"; 3 -> "Dinner"; else -> "Snack" }

    private fun estimateImportedIngredients(meal: MealEntity): List<IngredientItem> {
        val guess = parseMealOffline(meal.name)
        fun scale(selector: (IngredientItem) -> Int, total: Int): List<Int> {
            val sum = guess.sumOf(selector)
            if (sum <= 0) return guess.mapIndexed { i, _ -> if (i == 0) total else 0 }
            val scaled = guess.map { (selector(it).toFloat() * total / sum).roundToInt() }.toMutableList()
            scaled[scaled.lastIndex] += total - scaled.sum()
            return scaled
        }
        val kcal = scale(IngredientItem::baseCalories, meal.kcal)
        val protein = scale(IngredientItem::baseProtein, meal.protein)
        val carbs = scale(IngredientItem::baseCarbs, meal.carbs)
        val fat = scale(IngredientItem::baseFat, meal.fat)
        return guess.mapIndexed { index, item ->
            val name = when {
                "rice / grains" == item.name.lowercase() && "pita" in meal.name.lowercase() -> "Pita / wrap"
                "rice / grains" == item.name.lowercase() && ("rice" in meal.name.lowercase() || "greens" in meal.name.lowercase()) -> "Rice and grains"
                else -> item.name
            }
            item.copy(name = name, baseCalories = kcal[index], baseProtein = protein[index], baseCarbs = carbs[index], baseFat = fat[index])
        }
    }
}

object MealPresets {
    val all = listOf(
        MealPreset("Just Salad · Cilantro Lime Chicken Bowl", listOf(ingredient("Romaine & supergreens", "1 base", 45, 3, 8, 0), ingredient("Double oven-roasted chicken", "double serving", 360, 66, 0, 8), ingredient("Black beans & corn", "1 scoop", 130, 7, 24, 1), ingredient("Avocado", "1 serving", 110, 1, 6, 10), ingredient("Grillo's pickles", "1 serving", 15, 0, 3, 0), ingredient("Heavy cucumber tzatziki & sriracha", "heavy serving", 140, 3, 8, 10), ingredient("Spicy Harissa Pita", "1 pita", 160, 5, 30, 3, included = false))),
        MealPreset("Just Salad · Chicken Caesar Bowl", listOf(ingredient("Oven-roasted chicken", "1 serving", 280, 52, 0, 6), ingredient("Romaine", "1 base", 25, 2, 5, 0), ingredient("Parmesan", "1 serving", 80, 7, 1, 5), ingredient("Cucumber tzatziki", "heavy serving", 80, 3, 6, 5), ingredient("Wrap tortilla", "1 tortilla", 310, 8, 52, 8, included = false), ingredient("Coke Zero", "12 oz", 0, 0, 0, 0))),
        MealPreset("Juicy Platters · Mixed Platter", listOf(ingredient("Grilled chicken & gyro", "1 platter serving", 420, 55, 4, 20), ingredient("Half rice / half Green Blast", "half portions", 160, 5, 32, 2), ingredient("Extra chicken scoop", "1 scoop", 190, 36, 0, 4), ingredient("Cabbage & carrot slaw", "1 serving", 45, 1, 10, 0), ingredient("Tomatoes & pickled onions", "1 serving", 25, 1, 5, 0), ingredient("Classic hummus scoop", "1 scoop", 190, 6, 14, 12), ingredient("Warm pita", "1 pita", 240, 8, 48, 2, included = false))),
        MealPreset("Bubbakoo's · Chicken Bowl", listOf(ingredient("Grilled chicken", "1 serving", 280, 52, 0, 6), ingredient("Brown rice", "1 scoop", 215, 5, 45, 2), ingredient("Black beans", "1 scoop", 110, 7, 20, 1), ingredient("Fajita veggies", "1 serving", 45, 2, 9, 0), ingredient("Chipotle crema", "1 serving", 120, 1, 3, 12), ingredient("Tortilla chips", "1 serving", 280, 4, 36, 14, included = false))),
        MealPreset("Muscle Maker · Godfather Bowl", listOf(ingredient("Grilled chicken breast", "1 serving", 280, 52, 0, 6), ingredient("Steamed cauliflower rice", "1 base", 50, 4, 9, 1), ingredient("Turkey bacon", "1 serving", 70, 8, 1, 4), ingredient("Mushrooms & roasted red peppers", "1 serving", 40, 2, 8, 0), ingredient("Part-skim mozzarella", "1 serving", 80, 7, 1, 5), ingredient("Baked sweet potato fries", "side", 210, 3, 39, 5))),
        MealPreset("Trattoria · Chicken Parm (No Pasta)", listOf(ingredient("Breaded chicken cutlet with marinara & mozzarella", "1 cutlet", 655, 52, 35, 30), ingredient("Penne pasta", "1 serving", 500, 17, 95, 5, included = false)))
    )

    private fun ingredient(name: String, portion: String, kcal: Int, protein: Int, carbs: Int, fat: Int, included: Boolean = true) =
        IngredientItem(name = name, portion = portion, baseCalories = kcal, baseProtein = protein, baseCarbs = carbs, baseFat = fat, included = included)
}

fun parseMealOffline(text: String): List<IngredientItem> {
    val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
    val items = mutableListOf<IngredientItem>()
    fun add(name: String, portion: String, calories: Int, protein: Int, carbs: Int, fat: Int) {
        items += IngredientItem(name = name, portion = portion, baseCalories = calories, baseProtein = protein, baseCarbs = carbs, baseFat = fat)
    }
    when {
        "apple juice" in normalized -> add("Apple juice", Regex("\\b(\\d+)\\s*(?:oz|ounce|ounces)\\b").find(normalized)?.value ?: "8 oz", 120, 0, 29, 0)
        "orange juice" in normalized -> add("Orange juice", "8 oz", 112, 2, 26, 0)
        "protein shake" in normalized || "protein smoothie" in normalized -> add("Protein shake", "1 bottle", 160, 30, 8, 3)
    }
    if ("chicken" in normalized) add("Grilled chicken", "1 serving", 280, 52, 0, 6)
    if ("beef" in normalized || "steak" in normalized || "gyro" in normalized) add("Beef / gyro", "1 serving", 320, 38, 0, 18)
    if ("salmon" in normalized) add("Salmon", "1 fillet", 300, 34, 0, 18)
    if ("tofu" in normalized) add("Tofu", "1 serving", 180, 20, 8, 10)
    if ("greens" in normalized || "salad" in normalized || "lettuce" in normalized || "romaine" in normalized) add("Greens base", "1 base", 45, 3, 8, 0)
    if ("rice" in normalized || "pasta" in normalized || "bread" in normalized || "pita" in normalized || "tortilla" in normalized) {
        val isRemoved = listOf("no pita", "no bread", "no rice", "no pasta", "without pita", "without bread").any(normalized::contains)
        items += IngredientItem(name = "Rice / grains", portion = "1 serving", baseCalories = 210, baseProtein = 6, baseCarbs = 42, baseFat = 2, included = !isRemoved)
    }
    if ("beans" in normalized || "corn" in normalized) add("Beans & corn", "1 scoop", 130, 7, 24, 1)
    if ("avocado" in normalized) add("Avocado", "1 serving", 110, 1, 6, 10)
    if ("pickle" in normalized) add("Pickles", "1 serving", 15, 0, 3, 0)
    if ("sauce" in normalized || "dressing" in normalized || "hummus" in normalized || "tzatziki" in normalized || "sriracha" in normalized) add("Sauce / dressing", "1 serving", 120, 3, 8, 9)
    if ("fries" in normalized) add("Fries", "1 side", 210, 3, 39, 5)
    if ("egg" in normalized) add("Egg", "1 large", 70, 6, 0, 5)
    return items.distinctBy { it.name.lowercase() }.ifEmpty { listOf(IngredientItem(name = "Mixed meal estimate", portion = "1 meal", baseCalories = 450, baseProtein = 25, baseCarbs = 45, baseFat = 18)) }
}
