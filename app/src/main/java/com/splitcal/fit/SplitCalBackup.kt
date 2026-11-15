package com.splitcal.fit

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SplitCalBackupPayload(
    val version: Int = 1,
    val dailyLogs: List<DailyLogEntity> = emptyList(),
    val meals: List<MealEntity> = emptyList(),
    val ingredients: List<IngredientItem> = emptyList(),
    val workoutSplits: List<WorkoutSplitEntity> = emptyList(),
    val plannedExercises: List<PlannedExerciseEntity> = emptyList(),
    val workoutDays: List<WorkoutDayEntity> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val exerciseSets: List<ExerciseSetEntity> = emptyList()
)

object SplitCalBackup {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val dao = SplitCalDatabase.get(context.applicationContext as android.app.Application).dao()
        val backup = SplitCalBackupPayload(
            dailyLogs = dao.dailyLogsOnce(), meals = dao.mealsOnce(), ingredients = dao.ingredientsOnce(),
            workoutSplits = dao.splitsOnce(), plannedExercises = dao.plannedExercisesOnce(), workoutDays = dao.workoutDaysOnce(),
            exercises = dao.exerciseEntitiesOnce(), exerciseSets = dao.exerciseSetsOnce()
        )
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(gson.toJson(backup)) }
            ?: error("Could not open the selected backup destination")
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not open the selected backup file")
        val backup = gson.fromJson(json, SplitCalBackupPayload::class.java) ?: error("Backup file is empty or invalid")
        require(backup.version == 1) { "This backup version is not supported" }
        val db = SplitCalDatabase.get(context.applicationContext as android.app.Application)
        db.withTransaction {
            val dao = db.dao()
            dao.clearIngredients(); dao.clearMeals(); dao.clearExerciseSets(); dao.clearPlannedExercises()
            dao.clearExerciseEntities(); dao.clearWorkoutDays(); dao.clearSplits(); dao.clearDailyLogs()
            if (backup.dailyLogs.isNotEmpty()) dao.restoreDailyLogs(backup.dailyLogs)
            if (backup.workoutSplits.isNotEmpty()) dao.restoreSplits(backup.workoutSplits)
            if (backup.meals.isNotEmpty()) dao.restoreMeals(backup.meals)
            if (backup.ingredients.isNotEmpty()) dao.restoreIngredients(backup.ingredients)
            if (backup.workoutDays.isNotEmpty()) dao.restoreWorkoutDays(backup.workoutDays)
            if (backup.plannedExercises.isNotEmpty()) dao.restorePlannedExercises(backup.plannedExercises)
            if (backup.exercises.isNotEmpty()) dao.restoreExerciseEntities(backup.exercises)
            if (backup.exerciseSets.isNotEmpty()) dao.restoreExerciseSets(backup.exerciseSets)
        }
    }
}
