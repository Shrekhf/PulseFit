package com.pulsefit.app

import android.app.Application
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
    private val _state = MutableStateFlow(StepsUiState(sdkAvailable = repo.samsungSdkDataSource.isAvailable))
    val state: StateFlow<StepsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(includeAllOrigins = repo.includeAllOrigins())
            val mode = prefs.data.first()[stringPreferencesKey("calibration")] ?: StepBurnCalculator.PERSONAL_MODE
            _state.value = _state.value.copy(calibrationMode = mode)
        }
    }
    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(isLoading = true)
        val data = repo.readToday()
        val recovery = repo.readRecovery()
        if (data != null) {
            if (data.steps > 0) dao.putDailyLog(DailyLogEntity(LocalDate.now().toString(), data.steps))
            prefs.edit { p -> data.activeCalories?.let { p[intPreferencesKey("wearable_active_calories")] = it } ?: p.remove(intPreferencesKey("wearable_active_calories")) }
            _state.value = _state.value.copy(snapshot = data, recovery = recovery, status = if (data.fromSamsungHealth) "Synced · Samsung Health origin" else "Synced · all Health Connect origins", isLoading = false)
        } else _state.value = _state.value.copy(recovery = recovery, isLoading = false, status = "No permitted Health Connect step data · manual and sensor sources remain available")
    }
    fun setIncludeAllOrigins(value: Boolean) = viewModelScope.launch {
        repo.setIncludeAllOrigins(value); _state.value = _state.value.copy(includeAllOrigins = value); refresh()
    }
    fun setCalibrationMode(value: String) = viewModelScope.launch {
        prefs.edit { it[stringPreferencesKey("calibration")] = value }
        _state.value = _state.value.copy(calibrationMode = value)
    }
    fun setSteps(value: Int) = viewModelScope.launch { dao.putDailyLog(DailyLogEntity(LocalDate.now().toString(), value.coerceAtLeast(0))); _state.value = _state.value.copy(status = "Step count saved locally") }
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
