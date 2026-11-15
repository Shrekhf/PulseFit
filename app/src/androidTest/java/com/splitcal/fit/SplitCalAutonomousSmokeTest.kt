package com.splitcal.fit

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplitCalAutonomousSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun clearDailyTestLogs() {
        runBlocking {
            val dao = SplitCalDatabase.get(composeRule.activity.application as android.app.Application).dao()
            dao.clearIngredients()
            dao.clearMeals()
            dao.clearExerciseSets()
            dao.clearDailyLogs()
        }
    }

    @Test fun allFourTabsExposeTheirPrimaryControls() {
        composeRule.onNodeWithTag("tab_dashboard").assertExists()
        composeRule.onNodeWithTag("dashboard_net_budget").assertExists()
        composeRule.onNodeWithTag("tab_workout").performClick()
        composeRule.onNodeWithTag("workout_day_focus").assertExists()
        composeRule.onNodeWithTag("tab_meals").performClick()
        composeRule.onNodeWithTag("scan_samsung_health").assertExists()
        composeRule.onNodeWithTag("tab_steps").performClick()
        composeRule.onNodeWithTag("steps_list").performScrollToNode(hasTestTag("set_15000_benchmark"))
        composeRule.onNodeWithTag("set_15000_benchmark").assertExists()
    }

    @Test fun broSplitDaysLogASetAndUpdateVolumeEpleyAndTimer() {
        composeRule.onNodeWithTag("tab_workout").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("workout_day_0").fetchSemanticsNodes().isNotEmpty() }
        listOf("Chest", "Back", "Shoulders", "Legs", "Arms").forEachIndexed { index, focus ->
            composeRule.onNodeWithTag("workout_day_selector").performScrollToNode(hasTestTag("workout_day_$index"))
            composeRule.onNodeWithText(focus.take(8)).assertExists()
        }
        composeRule.onNodeWithTag("workout_day_selector").performScrollToNode(hasTestTag("workout_day_0"))
        composeRule.onNodeWithTag("workout_day_0").performClick()
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithTag("workout_day_focus").assertTextContains("Chest"); true }.getOrDefault(false) }
        composeRule.onNodeWithTag("workout_list").performScrollToNode(hasTestTag("exercise_expand_bro_split-day-0-exercise-0"))
        composeRule.onNodeWithTag("exercise_expand_bro_split-day-0-exercise-0").performClick()
        composeRule.onNodeWithTag("workout_list").performScrollToNode(hasTestTag("set_weight"))
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("set_weight").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithTag("set_weight")[0].performTextClearance()
        composeRule.onAllNodesWithTag("set_weight")[0].performTextInput("135")
        composeRule.onAllNodesWithTag("set_reps")[0].performTextClearance()
        composeRule.onAllNodesWithTag("set_reps")[0].performTextInput("8")
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithText("171").assertExists(); true }.getOrDefault(false) }
        composeRule.onAllNodesWithTag("set_complete")[0].performClick()
        composeRule.onAllNodesWithTag("set_complete")[0].assertIsOn()
        composeRule.onNodeWithTag("workout_list").performScrollToNode(hasTestTag("session_volume"))
        composeRule.onNodeWithTag("session_volume").assertTextContains("1,080 lb", substring = true)
        composeRule.onNodeWithText("171").assertExists()
        composeRule.onNodeWithTag("rest_timer_display").assertTextContains(":", substring = true)
    }

    @Test fun takeoutPresetModifiersLogAndUpdateDashboardBudget() {
        composeRule.onNodeWithTag("tab_meals").performClick()
        composeRule.onNodeWithTag("meal_scanner").performScrollToNode(hasTestTag("preset_just_salad"))
        composeRule.onNodeWithTag("preset_just_salad").performClick()
        waitForSnackbarToDismiss("Loaded Just Salad")
        composeRule.onNodeWithTag("meal_modifiers").performScrollToNode(hasTestTag("modifier_remove_pita"))
        composeRule.onNodeWithTag("modifier_remove_pita").performClick()
        waitForSnackbarToDismiss("Applied Remove Pita")
        composeRule.onNodeWithTag("meal_modifiers").performScrollToNode(hasTestTag("modifier_extra_chicken"))
        composeRule.onNodeWithTag("modifier_extra_chicken").performClick()
        waitForSnackbarToDismiss("Applied Extra Chicken")
        composeRule.onNodeWithTag("meal_total_kcal").assertTextContains("990 kcal", substring = true)
        repeat(5) {
            if (runCatching { composeRule.onNodeWithTag("log_meal").assertIsDisplayed(); true }.getOrDefault(false)) return@repeat
            composeRule.onNodeWithTag("meal_scanner").performTouchInput { swipeUp() }
        }
        composeRule.onNodeWithTag("log_meal").assertIsDisplayed()
        composeRule.onNodeWithTag("log_meal").performClick()
        val dao = SplitCalDatabase.get(composeRule.activity.application as android.app.Application).dao()
        composeRule.waitUntil(10_000) { runBlocking { dao.mealsOnce().any { it.name.contains("Cilantro Lime") } } }
        composeRule.onNodeWithTag("tab_dashboard").performClick()
        composeRule.onNodeWithTag("dashboard_net_budget").assertExists()
        composeRule.onNodeWithTag("dashboard_calorie_details").assertTextContains("990 eaten", substring = true)
    }

    @Test fun personalFifteenThousandStepBenchmarkIsFiveHundredCalories() {
        composeRule.onNodeWithTag("tab_steps").performClick()
        composeRule.onNodeWithTag("steps_list").performScrollToNode(hasTestTag("set_15000_benchmark"))
        composeRule.onNodeWithTag("set_15000_benchmark").performClick()
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithText("500 kcal").assertExists(); true }.getOrDefault(false) }
        composeRule.onNodeWithTag("steps_list").performScrollToNode(hasTestTag("steps_heading"))
        composeRule.onNodeWithText("15,000 / 15,000 steps").assertExists()
        composeRule.onNodeWithTag("tab_dashboard").performClick()
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithText("500 calibrated kcal").assertExists(); true }.getOrDefault(false) }
    }

    @Test fun geminiFallsBackOfflineAndSamsungBridgeHandlesMissingApp() {
        val expectedModels = listOf("gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-4-hydrogen", "gemini-2.5-flash-lite")
        assertEquals("gemini-flash-latest", GeminiApiClient.DEFAULT_MODEL)
        assertEquals(expectedModels, GeminiApiClient.MODEL_OPTIONS)
        runBlocking {
            val result = AiMealAnalyzer().analyze(composeRule.activity.applicationContext, "   ", "chicken bowl", selectedModel = GeminiApiClient.DEFAULT_MODEL)
            assertFalse(result.usedGemini)
            assertTrue(result.ingredients.isNotEmpty())
            assertTrue(result.fallbackReason.orEmpty().contains("No Gemini API key"))
        }
        val packageManager = composeRule.activity.packageManager
        if (packageManager.getLaunchIntentForPackage(SamsungHealthRepository.SAMSUNG_HEALTH_PACKAGE) == null) {
            composeRule.onNodeWithTag("tab_meals").performClick()
            composeRule.onNodeWithTag("scan_samsung_health").performClick()
            composeRule.onNodeWithText("Samsung Health isn't installed", substring = true).assertExists()
            composeRule.onNodeWithTag("tab_steps").performClick()
            composeRule.onNodeWithTag("sync_samsung_health").performClick()
            composeRule.onNodeWithText("Samsung Health isn't installed", substring = true).assertExists()
        }
    }

    private fun waitForSnackbarToDismiss(message: String) {
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText(message, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
}
