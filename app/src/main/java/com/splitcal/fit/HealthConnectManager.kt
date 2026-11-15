package com.splitcal.fit

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

class HealthConnectManager(private val context: Context) {
    val readPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )
    val writePermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    )
    val allPermissions get() = readPermissions + writePermissions

    fun clientOrNull(): HealthConnectClient? = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
    suspend fun grantedPermissions(): Set<String> = clientOrNull()?.permissionController?.getGrantedPermissions().orEmpty()

    companion object {
        fun permissionContract() = PermissionController.createRequestPermissionResultContract()
    }
}
