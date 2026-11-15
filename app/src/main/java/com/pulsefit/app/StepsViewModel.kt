package com.pulsefit.app

import android.app.Application
import android.app.Activity
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

data class StepsUiState(
    val snapshot: SamsungHealthSnapshot? = null,
    val includeAllOrigins: Boolean = false,
    val isLoading: Boolean = false,
    val status: String = "Connect Health Connect to sync Samsung Health data",
    val calibrationMode: String = StepBurnCalculator.PERSONAL_MODE,
    val sdkAvailable: Boolean = false,
    val recovery: RecoverySnapshot? = null
)

class StepsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SamsungHealthRepository(application)
    private val dao = PulseFitDatabase.get(application).dao()
    private val prefs = application.pulseFitPrefs
    private var selectedDate = LocalDate.now().toString()
    private val _state = MutableStateFlow(StepsUiState(sdkAvailable = repo.samsungSdkDataSource.isAvailable))
    val state: StateFlow<StepsUiState> = _state.asStateFlow()

    fun setDate(date: String) = viewModelScope.launch {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@launch
        selectedDate = date
        if (date == LocalDate.now().toString()) refresh() else {
            val sdkData = if (repo.samsungSdkDataSource.isAvailable) runCatching { repo.samsungSdkDataSource.readStepsAndGoals(parsed) }.getOrNull() else null
            val healthData = if (sdkData == null) repo.readDay(parsed) else null
            val synced = sdkData?.let { SamsungHealthSnapshot(it.steps, fromSamsungHealth = true, averageHeartRate = it.heartRate, sleepMinutes = it.sleepMinutes, stepGoal = it.stepGoal, activeCaloriesGoal = it.activeCaloriesGoal, energyScore = it.energyScore) } ?: healthData
            val localLog = dao.dailyLog(date).first()
            val steps = synced?.steps ?: localLog?.steps ?: 0
            if (synced != null) dao.putDailyLog(DailyLogEntity(date, steps))
            val previous = _state.value.snapshot
            _state.value = _state.value.copy(snapshot = SamsungHealthSnapshot(steps = steps, fromSamsungHealth = synced?.fromSamsungHealth ?: (previous?.fromSamsungHealth == true)), status = if (synced != null) "Historical Health Connect data saved locally · $date" else "Historical local record · $date")
        }
    }

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(includeAllOrigins = repo.includeAllOrigins())
            val mode = prefs.data.first()[stringPreferencesKey("calibration")] ?: StepBurnCalculator.PERSONAL_MODE
            _state.value = _state.value.copy(calibrationMode = mode)
        }
    }
    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(isLoading = true)
        val sdkData = if (repo.samsungSdkDataSource.isAvailable) runCatching { repo.samsungSdkDataSource.readStepsAndGoals() }.getOrNull() else null
        val data = if (sdkData == null) repo.readToday() else null
        val recovery = repo.readRecovery()
        val selected = sdkData?.let { SamsungHealthSnapshot(it.steps, fromSamsungHealth = true, averageHeartRate = it.heartRate, sleepMinutes = it.sleepMinutes, stepGoal = it.stepGoal, activeCaloriesGoal = it.activeCaloriesGoal, energyScore = it.energyScore) } ?: data
        if (selected != null) {
            if (selected.steps > 0 && selectedDate == LocalDate.now().toString()) dao.putDailyLog(DailyLogEntity(selectedDate, selected.steps))
            prefs.edit { p ->
                selected.activeCalories?.let { p[intPreferencesKey("wearable_active_calories")] = it }
                if (sdkData == null && selected.activeCalories == null) p.remove(intPreferencesKey("wearable_active_calories"))
                selected.stepGoal?.let { p[intPreferencesKey("step_goal")] = it }
            }
            val syncSource = when { sdkData != null -> "Synced directly from Samsung Health Data SDK"; data?.fromSamsungHealth == true -> "Synced · Samsung Health Health Connect origin"; else -> "Synced · all Health Connect origins" }
            _state.value = _state.value.copy(snapshot = selected, recovery = recovery, status = syncSource, isLoading = false, sdkAvailable = repo.samsungSdkDataSource.isAvailable)
        } else _state.value = _state.value.copy(recovery = recovery, isLoading = false, status = "No permitted Health Connect step data · manual and sensor sources remain available")
    }
    fun requestSamsungPermissions(activity: Activity) = viewModelScope.launch {
        val accepted = runCatching { repo.samsungSdkDataSource.requestPermissions(activity) }.getOrDefault(false)
        _state.value = _state.value.copy(status = if (accepted) "Samsung Health permissions granted" else "Samsung Health SDK permission unavailable or declined")
        refresh()
    }
    fun setIncludeAllOrigins(value: Boolean) = viewModelScope.launch {
        repo.setIncludeAllOrigins(value); _state.value = _state.value.copy(includeAllOrigins = value); refresh()
    }
    fun setCalibrationMode(value: String) = viewModelScope.launch {
        prefs.edit { it[stringPreferencesKey("calibration")] = value }
        _state.value = _state.value.copy(calibrationMode = value)
    }
    fun setSteps(value: Int) = viewModelScope.launch { dao.putDailyLog(DailyLogEntity(selectedDate, value.coerceAtLeast(0))); _state.value = _state.value.copy(snapshot = _state.value.snapshot?.copy(steps = value.coerceAtLeast(0)), status = "Step count saved locally") }
    fun addSteps(delta: Int, current: Int) = setSteps((current + delta).coerceAtLeast(0))
    fun setBenchmark() = setSteps(15_000)
    fun importExport(uri: Uri) = viewModelScope.launch {
        val parsed = runCatching {
            val raw = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Could not read export")
            parseStepExport(raw)
        }.getOrElse { _state.value = _state.value.copy(status = "Import failed · ${it.message ?: "unsupported export"}"); return@launch }
        setSteps(parsed)
        _state.value = _state.value.copy(status = "Imported $parsed steps from Samsung Health export")
    }
}

private fun parseStepExport(raw: String): Int {
    runCatching {
        val root = JSONObject(raw)
        listOf("steps", "step_count", "count", "total_steps").forEach { key -> if (root.has(key)) return root.optInt(key).coerceAtLeast(0) }
        root.keys().forEach { key -> val value = root.opt(key); if (key.contains("step", true) && value is Number) return value.toInt().coerceAtLeast(0) }
    }
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) error("Empty export")
    val header = lines.first().split(',', ';', '\t').map { it.trim().trim('"').lowercase() }
    val col = header.indexOfFirst { it.contains("step") || it == "count" }
    if (col >= 0) {
        val values = lines.drop(1).mapNotNull { line -> line.split(',', ';', '\t').getOrNull(col)?.trim()?.trim('"')?.replace(",", "")?.toIntOrNull() }
        if (values.isNotEmpty()) return values.sum().coerceAtLeast(0)
    }
    val numbers = lines.drop(1).mapNotNull { line -> line.split(',', ';', '\t').asSequence().map { it.trim().trim('"') }.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() }
    return numbers.sum().takeIf { it > 0 } ?: error("No step count column found")
}
