package com.splitcal.fit

import android.content.Context
import android.app.Activity
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.permission.HealthPermission
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class SamsungHealthSnapshot(
    val steps: Int = 0,
    val activeCalories: Int? = null,
    val totalCalories: Int? = null,
    val averageHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
    val exerciseSessions: Int = 0,
    val fromSamsungHealth: Boolean = false,
    val stepGoal: Int? = null,
    val activeCaloriesGoal: Int? = null,
    val energyScore: Int? = null
)

object StepBurnCalculator {
    const val PERSONAL_MODE = "personal_500"
    const val WEARABLE_MODE = "tracker_high"
    const val CONSERVATIVE_MODE = "conservative"
    fun calibrated(steps: Int, mode: String, wearableActiveCalories: Int? = null): Int = when (mode) {
        WEARABLE_MODE -> wearableActiveCalories?.takeIf { it > 0 } ?: (steps * (700f / 15000f)).roundToInt()
        CONSERVATIVE_MODE -> (steps * (420f / 15000f)).roundToInt()
        else -> (steps * (500f / 15000f)).roundToInt()
    }
    fun personal(steps: Int) = (steps * (500f / 15000f)).roundToInt()
    fun rawReference(steps: Int) = (steps * (700f / 15000f)).roundToInt()
}

/** Samsung Health Data SDK is shipped by Samsung as a separate AAR. */
interface SamsungHealthDataSdkDataSource {
    val isAvailable: Boolean
    suspend fun requestPermissions(activity: Activity): Boolean
    suspend fun readStepsAndGoals(date: LocalDate = LocalDate.now()): SamsungSdkDailyData?
}
data class SamsungSdkDailyData(val steps: Int, val stepGoal: Int?, val activeCaloriesGoal: Int?, val energyScore: Int?, val sleepMinutes: Int?, val heartRate: Int?)

class SamsungHealthRepository(
    private val context: Context,
    private val manager: HealthConnectManager = HealthConnectManager(context),
    private val directSdk: SamsungHealthDataSdkDataSource = createSamsungHealthDataSdkDataSource(context)
) {
    private val preferences = UserPreferencesRepository(context)
    val samsungSdkDataSource get() = directSdk

    suspend fun includeAllOrigins(): Boolean = !preferences.preferSamsungHealthOrigin.first()
    suspend fun setIncludeAllOrigins(include: Boolean) {
        (context.applicationContext as android.app.Application).splitCalPrefs.edit { it[PREFER_SAMSUNG_HEALTH_ORIGIN] = !include }
    }

    suspend fun readToday(): SamsungHealthSnapshot? = readDay(LocalDate.now())

    suspend fun readDay(day: LocalDate): SamsungHealthSnapshot? = runCatching {
        val client = manager.clientOrNull() ?: return null
        val granted = client.permissionController.getGrantedPermissions()
        if (androidx.health.connect.client.permission.HealthPermission.getReadPermission(StepsRecord::class) !in granted) return null
        val zone = ZoneId.systemDefault(); val start = day.atStartOfDay(zone).toInstant(); val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val samsungOnly = preferences.preferSamsungHealthOrigin.first()
        fun <T : androidx.health.connect.client.records.Record> request(type: kotlin.reflect.KClass<T>, permission: kotlin.reflect.KClass<*>) = ReadRecordsRequest(
            recordType = type,
            timeRangeFilter = TimeRangeFilter.between(start, end),
            dataOriginFilter = if (samsungOnly) setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE)) else emptySet(),
            pageSize = 1000
        )
        val steps = client.readRecords(request(StepsRecord::class, StepsRecord::class)).records.sumOf { it.count }.toInt()
        val activePermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val totalPermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        val heartPermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateRecord::class)
        val sleepPermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(SleepSessionRecord::class)
        val exercisePermission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val active = if (activePermission in granted) client.readRecords(request(ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class)).records.sumOf { it.energy.inKilocalories }.roundToInt() else null
        val total = if (totalPermission in granted) client.readRecords(request(TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class)).records.sumOf { it.energy.inKilocalories }.roundToInt() else null
        val heart = if (heartPermission in granted) client.readRecords(request(HeartRateRecord::class, HeartRateRecord::class)).records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() else null
        val sleep = if (sleepPermission in granted) client.readRecords(request(SleepSessionRecord::class, SleepSessionRecord::class)).records.maxByOrNull { it.endTime }?.let { ((it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L).toInt() } else null
        val exerciseCount = if (exercisePermission in granted) client.readRecords(request(ExerciseSessionRecord::class, ExerciseSessionRecord::class)).records.size else 0
        SamsungHealthSnapshot(steps, active, total, heart, sleep, exerciseCount, fromSamsungHealth = samsungOnly)
    }.getOrNull()

    suspend fun readRecovery(): RecoverySnapshot? {
        val sdkRecovery = if (directSdk.isAvailable) runCatching { directSdk.readStepsAndGoals() }.getOrNull() else null
        if (sdkRecovery != null) {
            val tip = when {
                (sdkRecovery.sleepMinutes ?: 480) < 390 -> "Sleep was under 6.5 hours: consider reducing target RPE by 0.5 today."
                sdkRecovery.steps > 18_000 -> "Step load is high: consider reducing target RPE by 0.5 or using machine and cable alternatives to heavy spinal-loading lifts."
                else -> "Recovery context synced. Follow planned RPE and adjust based on warm-up sets."
            }
            return RecoverySnapshot(sdkRecovery.sleepMinutes, sdkRecovery.heartRate, tip)
        }
        return readRecoveryFromHealthConnect()
    }

    private suspend fun readRecoveryFromHealthConnect(): RecoverySnapshot? = runCatching {
        val client = manager.clientOrNull() ?: return null
        val granted = client.permissionController.getGrantedPermissions()
        val useSamsung = preferences.preferSamsungHealthOrigin.first()
        val origin = if (useSamsung) setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE)) else emptySet()
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val sleepStart = now.minus(java.time.Duration.ofHours(36))
        val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
        val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        val sleep = if (sleepPermission in granted) client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(sleepStart, now), dataOriginFilter = origin, pageSize = 1000)).records.maxByOrNull { it.endTime } else null
        val sleepMinutes = sleep?.let { ((it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L).toInt() }
        val heart = if (heartPermission in granted) {
            val hrRangeStart = sleep?.startTime ?: now.minus(java.time.Duration.ofHours(12))
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(hrRangeStart, now), dataOriginFilter = origin, pageSize = 1000)).records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        } else null
        val yesterdayStart = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val yesterdaySteps = if (stepsPermission in granted) client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(yesterdayStart, todayStart), dataOriginFilter = origin, pageSize = 1000)).records.sumOf { it.count }.toInt() else 0
        if (sleepMinutes == null && heart == null && yesterdaySteps == 0) return null
        val tip = when {
            (sleepMinutes ?: 480) < 390 -> "Sleep was under 6.5 hours: consider reducing target RPE by 0.5 today."
            yesterdaySteps > 18_000 -> "Yesterday's step load was high: consider reducing target RPE by 0.5 or using machine and cable alternatives to heavy spinal-loading lifts."
            else -> "Recovery context synced. Follow planned RPE and adjust based on warm-up sets."
        }
        RecoverySnapshot(sleepMinutes, heart, tip)
    }.getOrNull()

    companion object { const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth" }
}



/** Used when the separately licensed Samsung AAR has not been installed locally. */
class SamsungHealthDataSdkFallback : SamsungHealthDataSdkDataSource {
    override val isAvailable: Boolean = false
    override suspend fun requestPermissions(activity: android.app.Activity): Boolean = false
    override suspend fun readStepsAndGoals(date: LocalDate): SamsungSdkDailyData? = null
}

fun createSamsungHealthDataSdkDataSource(context: Context): SamsungHealthDataSdkDataSource = runCatching {
    val adapterClass = Class.forName("com.splitcal.fit.SamsungHealthDataSdkAdapter")
    adapterClass.getConstructor(Context::class.java).newInstance(context) as SamsungHealthDataSdkDataSource
}.getOrElse { SamsungHealthDataSdkFallback() }
