package com.splitcal.fit

import android.app.Activity
import android.content.Context
import android.os.Build
import java.time.LocalDate
import kotlin.math.roundToInt

/** Live adapter for the Samsung Health Data SDK. */
class SamsungHealthDataSdkAdapter(private val context: Context) : SamsungHealthDataSdkDataSource {
    private val appContext = context.applicationContext
    private val store by lazy { com.samsung.android.sdk.health.data.HealthDataService.getStore(appContext) }
    private val permissions by lazy { setOf(
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.STEPS, com.samsung.android.sdk.health.data.permission.AccessType.READ),
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.STEPS_GOAL, com.samsung.android.sdk.health.data.permission.AccessType.READ),
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.ACTIVE_CALORIES_BURNED_GOAL, com.samsung.android.sdk.health.data.permission.AccessType.READ),
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.ENERGY_SCORE, com.samsung.android.sdk.health.data.permission.AccessType.READ),
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.SLEEP, com.samsung.android.sdk.health.data.permission.AccessType.READ),
        com.samsung.android.sdk.health.data.permission.Permission.of(com.samsung.android.sdk.health.data.request.DataTypes.HEART_RATE, com.samsung.android.sdk.health.data.permission.AccessType.READ)
    ) }

    override val isAvailable: Boolean get() = isSupportedSamsungDevice(appContext)

    override suspend fun requestPermissions(activity: Activity): Boolean = runCatching {
        if (!isAvailable) return false
        store.requestPermissions(permissions, activity).isNotEmpty()
    }.getOrDefault(false)

    override suspend fun readStepsAndGoals(date: LocalDate): SamsungSdkDailyData? = runCatching {
        if (!isAvailable) return null
        val granted = store.getGrantedPermissions(permissions)
        fun hasReadPermission(dataType: com.samsung.android.sdk.health.data.request.DataType) = com.samsung.android.sdk.health.data.permission.Permission.of(dataType, com.samsung.android.sdk.health.data.permission.AccessType.READ) in granted
        if (!hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.STEPS)) return null
        val today = date
        val start = today.atStartOfDay()
        val end = today.plusDays(1).atStartOfDay()
        val stepsRequest = com.samsung.android.sdk.health.data.request.DataType.StepsType.TOTAL.requestBuilder
            .setLocalTimeFilter(com.samsung.android.sdk.health.data.request.LocalTimeFilter.of(start, end)).build()
        val steps = store.aggregateData(stepsRequest).dataList.sumOf { it.value ?: 0L }.toInt()

        val dayFilter = com.samsung.android.sdk.health.data.request.LocalDateFilter.of(today, today.plusDays(1))
        val stepGoal = if (hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.STEPS_GOAL)) {
            val req = com.samsung.android.sdk.health.data.request.DataType.StepsGoalType.LAST.requestBuilder.setLocalDateFilter(dayFilter).build()
            store.aggregateData(req).dataList.lastOrNull()?.value
        } else null
        val activeGoal = if (hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.ACTIVE_CALORIES_BURNED_GOAL)) {
            val req = com.samsung.android.sdk.health.data.request.DataType.ActiveCaloriesBurnedGoalType.LAST.requestBuilder.setLocalDateFilter(dayFilter).build()
            store.aggregateData(req).dataList.lastOrNull()?.value
        } else null

        val readStart = today.minusDays(1).atStartOfDay()
        val readEnd = today.plusDays(1).atStartOfDay()
        val readFilter = com.samsung.android.sdk.health.data.request.LocalTimeFilter.of(readStart, readEnd)
        val energy = if (hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.ENERGY_SCORE)) {
            val req = com.samsung.android.sdk.health.data.request.DataTypes.ENERGY_SCORE.readDataRequestBuilder
                .setLocalDateFilter(com.samsung.android.sdk.health.data.request.LocalDateFilter.of(today.minusDays(1), today.plusDays(1))).build()
            store.readData(req).dataList.lastOrNull()?.getValue(com.samsung.android.sdk.health.data.request.DataType.EnergyScoreType.ENERGY_SCORE)?.roundToInt()
        } else null
        val sleep = if (hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.SLEEP)) {
            val req = com.samsung.android.sdk.health.data.request.DataTypes.SLEEP.readDataRequestBuilder.setLocalTimeFilter(readFilter).build()
            store.readData(req).dataList.maxByOrNull { it.endTime?.toEpochMilli() ?: Long.MIN_VALUE }?.getValue(com.samsung.android.sdk.health.data.request.DataType.SleepType.DURATION)?.toMinutes()?.toInt()
        } else null
        val heart = if (hasReadPermission(com.samsung.android.sdk.health.data.request.DataTypes.HEART_RATE)) {
            val req = com.samsung.android.sdk.health.data.request.DataTypes.HEART_RATE.readDataRequestBuilder.setLocalTimeFilter(readFilter).build()
            store.readData(req).dataList.mapNotNull { it.getValue(com.samsung.android.sdk.health.data.request.DataType.HeartRateType.HEART_RATE)?.toDouble() }
                .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        } else null
        SamsungSdkDailyData(steps, stepGoal, activeGoal, energy, sleep, heart)
    }.getOrNull()

    private fun isSupportedSamsungDevice(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("emulator", true)) return false
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SamsungHealthRepository.SAMSUNG_HEALTH_PACKAGE, 0).versionName
        }.getOrNull() ?: return false
        val nums = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
        return nums.take(3).let { (it.getOrNull(0) ?: 0) > 6 || ((it.getOrNull(0) ?: 0) == 6 && ((it.getOrNull(1) ?: 0) > 30 || ((it.getOrNull(1) ?: 0) == 30 && (it.getOrNull(2) ?: 0) >= 2))) }
    }
}

