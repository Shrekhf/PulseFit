package com.splitcal.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealModelsTest {
    @Test
    fun defaultBowlTotalsExcludePitaAndUseRoundedMacros() {
        val state = MealScannerUiState()

        assertEquals(800, state.totalCalories)
        assertEquals(80, state.totalProtein)
        assertEquals(49, state.totalCarbs)
        assertEquals(29, state.totalFat)
        assertEquals(160, state.totalSavedCalories)
        assertFalse(state.ingredients.last().included)
    }

    @Test
    fun multiplierAndCheckboxChangesProduceCorrectEffectiveTotals() {
        val original = MealScannerUiState()
        val chicken = original.ingredients.first { it.name.contains("chicken", ignoreCase = true) }
        val scaled = chicken.copy(multiplier = 1.5f)
        val state = original.copy(ingredients = original.ingredients.map { if (it.id == chicken.id) scaled else it })

        assertEquals(980, state.totalCalories)
        assertEquals(113, state.totalProtein)
        assertEquals(33, state.totalFat)
        assertEquals(800, original.totalCalories)

        val pita = state.ingredients.last()
        val withPita = state.copy(ingredients = state.ingredients.map { if (it.id == pita.id) it.copy(included = true) else it })
        assertEquals(1140, withPita.totalCalories)
        assertEquals(36, withPita.totalFat)
    }

    @Test
    fun offlineTextParserRecognizesJuiceAndServingQuantity() {
        val items = parseMealOffline("apple juice 8oz")

        assertEquals(1, items.size)
        assertEquals("Apple juice", items.single().name)
        assertEquals(120, items.single().effectiveCalories)
        assertEquals(29, items.single().effectiveCarbs)
    }

    @Test
    fun checkboxAndMultiplierChangesReturnNewIngredientValues() {
        val ingredient = IngredientItem(name = "Rice", portion = "1 cup", baseCalories = 200, baseProtein = 4, baseCarbs = 45, baseFat = 1)
        val changed = ingredient.copy(included = false, multiplier = 1.5f)

        assertTrue(ingredient.included)
        assertEquals(200, ingredient.effectiveCalories)
        assertEquals(0, changed.effectiveCalories)
        assertEquals(300, changed.savedCalories)
    }
}
