package com.splitcal.fit

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import java.util.UUID

@Immutable
data class IngredientItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val portion: String,
    val baseCalories: Int,
    val baseProtein: Int,
    val baseCarbs: Int,
    val baseFat: Int,
    val multiplier: Float = 1.0f,
    val included: Boolean = true
) {
    val effectiveCalories: Int get() = if (included) (baseCalories * multiplier).roundToInt() else 0
    val effectiveProtein: Int get() = if (included) (baseProtein * multiplier).roundToInt() else 0
    val effectiveCarbs: Int get() = if (included) (baseCarbs * multiplier).roundToInt() else 0
    val effectiveFat: Int get() = if (included) (baseFat * multiplier).roundToInt() else 0
    val savedCalories: Int get() = if (!included) (baseCalories * multiplier).roundToInt() else 0

    fun toEntity(mealId: String) = MealIngredientEntity(
        id = id,
        mealId = mealId,
        name = name,
        baseKcal = baseCalories,
        baseProtein = baseProtein,
        baseCarbs = baseCarbs,
        baseFat = baseFat,
        multiplier = multiplier,
        included = included
    )
}

@Immutable
data class MealScannerUiState(
    val mealTitle: String = "Just Salad Cilantro Lime Chicken Bowl (Custom)",
    val descriptionInput: String = "Just Salad Cilantro Lime Chicken Bowl: romaine & supergreens, double oven-roasted chicken, black beans, corn, avocado, Grillo's pickles, extra cucumber tzatziki, heavy sriracha, no pita, no cheese",
    val selectedSlot: String = "Dinner",
    val confidenceBadge: String = "98% AI Match",
    val statusMessage: String? = null,
    val isAnalyzing: Boolean = false,
    val activeModifiers: Set<String> = emptySet(),
    val ingredients: List<IngredientItem> = defaultJustSaladIngredients(),
    val syncStatus: String? = null,
    val editingMealId: String? = null,
    val editingSlot: String? = null,
    val editingExternalRecordId: String? = null,
    val editingHealthConnectRecordId: String? = null,
    val usedGemini: Boolean = false
) {
    val totalCalories: Int get() = ingredients.sumOf(IngredientItem::effectiveCalories)
    val totalProtein: Int get() = ingredients.sumOf(IngredientItem::effectiveProtein)
    val totalCarbs: Int get() = ingredients.sumOf(IngredientItem::effectiveCarbs)
    val totalFat: Int get() = ingredients.sumOf(IngredientItem::effectiveFat)
    val totalSavedCalories: Int get() = ingredients.sumOf(IngredientItem::savedCalories)
}

fun defaultJustSaladIngredients() = listOf(
    IngredientItem(name = "Romaine & supergreens", portion = "1 base", baseCalories = 45, baseProtein = 3, baseCarbs = 8, baseFat = 0),
    IngredientItem(name = "Double oven-roasted chicken", portion = "double serving", baseCalories = 360, baseProtein = 66, baseCarbs = 0, baseFat = 8),
    IngredientItem(name = "Black beans & corn", portion = "1 scoop", baseCalories = 130, baseProtein = 7, baseCarbs = 24, baseFat = 1),
    IngredientItem(name = "Avocado", portion = "1 serving", baseCalories = 110, baseProtein = 1, baseCarbs = 6, baseFat = 10),
    IngredientItem(name = "Grillo's pickles", portion = "1 serving", baseCalories = 15, baseProtein = 0, baseCarbs = 3, baseFat = 0),
    IngredientItem(name = "Heavy cucumber tzatziki & sriracha", portion = "heavy serving", baseCalories = 140, baseProtein = 3, baseCarbs = 8, baseFat = 10),
    IngredientItem(name = "Spicy Harissa Pita", portion = "1 pita", baseCalories = 160, baseProtein = 5, baseCarbs = 30, baseFat = 3, included = false)
)

fun MealIngredientEntity.toUiItem() = IngredientItem(
    id = id,
    name = name,
    portion = "1 serving",
    baseCalories = baseKcal,
    baseProtein = baseProtein,
    baseCarbs = baseCarbs,
    baseFat = baseFat,
    multiplier = multiplier,
    included = included
)

