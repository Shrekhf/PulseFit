package com.splitcal.fit

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Best-effort writer: Room remains the source of truth when Health Connect is unavailable. */
class HealthSyncBridge(private val context: Context) {
    private val preferences = UserPreferencesRepository(context)

    private suspend fun clientIfPermitted(writePermission: String): HealthConnectClient? = runCatching {
        if (preferences.autoWriteToSamsungHealth.first().not()) return null
        val client = HealthConnectClient.getOrCreate(context)
        if (writePermission !in client.permissionController.getGrantedPermissions()) return null
        client
    }.getOrNull()

    suspend fun writeMealToSamsungHealth(mealTitle: String, slot: String, totalCalories: Int, proteinGrams: Int, carbsGrams: Int, fatGrams: Int): Boolean = runCatching {
        val permission = HealthPermission.getWritePermission(NutritionRecord::class)
        val client = clientIfPermitted(permission) ?: return false
        val now = Instant.now()
        val zone = ZoneOffset.systemDefault().rules.getOffset(now)
        client.insertRecords(listOf(NutritionRecord(
            startTime = now, startZoneOffset = zone, endTime = now, endZoneOffset = zone,
            metadata = Metadata.manualEntry(), name = mealTitle,
            energy = Energy.kilocalories(totalCalories.toDouble()), protein = Mass.grams(proteinGrams.toDouble()),
            totalCarbohydrate = Mass.grams(carbsGrams.toDouble()), totalFat = Mass.grams(fatGrams.toDouble()),
            mealType = when (slot.lowercase()) { "breakfast" -> 1; "lunch" -> 2; "dinner" -> 3; else -> 4 }
        )))
        true
    }.getOrDefault(false)

    suspend fun writeWorkoutToSamsungHealth(title: String, durationMinutes: Int, activeCaloriesBurned: Int): Boolean = runCatching {
        val exercisePermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val client = clientIfPermitted(exercisePermission) ?: return false
        val end = Instant.now()
        val start = end.minus(Duration.ofMinutes(durationMinutes.coerceAtLeast(1).toLong()))
        val zone = ZoneOffset.systemDefault().rules.getOffset(end)
        val records = mutableListOf<androidx.health.connect.client.records.Record>(ExerciseSessionRecord(
            startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
            metadata = Metadata.manualEntry(), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, title = title
        ))
        if (activeCaloriesBurned > 0) {
            val caloriesPermission = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
            if (caloriesPermission in client.permissionController.getGrantedPermissions()) records += ActiveCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(activeCaloriesBurned.toDouble()), Metadata.manualEntry())
        }
        client.insertRecords(records)
        true
    }.getOrDefault(false)
}
