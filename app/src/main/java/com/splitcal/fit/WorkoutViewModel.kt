package com.splitcal.fit

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.google.ai.client.generativeai.GenerativeModel

data class ExerciseSeed(val name: String, val sets: Int = 4, val reps: String = "8–12", val rest: Int = 90, val equipment: String = "Full Commercial Gym", val jointNote: String = "Use a smooth, pain-free range of motion.")
data class DaySeed(val focus: String, val notes: String, val burn: Int, val exercises: List<ExerciseSeed>)
data class SplitSeed(val id: String, val name: String, val description: String, val days: List<DaySeed>)

object SplitGenerator {
    private fun ex(name: String, sets: Int = 4, reps: String = "8–12", rest: Int = 90, equipment: String = "Full Commercial Gym", note: String = "Control the eccentric and stop if the joint feels painful.") = ExerciseSeed(name, sets, reps, rest, equipment, note)
    private fun day(focus: String, vararg exercises: ExerciseSeed, notes: String = "Leave 1–3 reps in reserve on working sets.", burn: Int = 40) = DaySeed(focus, notes, burn, exercises.toList())
    private fun recovery(name: String) = DaySeed(name, "Easy walking, mobility, and recovery. Target 15,000 steps (about 500 calibrated active kcal).", 0, emptyList())

    val templates = listOf(
        SplitSeed("bro_split", "5-Day Bro Split", "Chest · Back · Shoulders · Knee-friendly legs · Arms", listOf(
            day("Chest", ex("Incline Barbell/Smith Press (30°)", rest = 120), ex("Flat Dumbbell Press"), ex("Weighted Chest Dips", note = "Use assisted dips or a neutral-grip press if shoulders feel pinched."), ex("Low-to-High Cable Chest Fly", 3, "12–15", 60), ex("Pec Deck Machine Fly (2s peak hold)", 3, "12–15", 60), burn = 215),
            day("Back", ex("Wide/Neutral-Grip Lat Pulldown"), ex("Chest-Supported T-Bar Row"), ex("Single-Arm Dumbbell Row", 3), ex("Straight-Arm Cable Lat Prayer", 3, "12–15", 60), ex("Dumbbell Shrugs (2s pause)", 3, "10–15", 60), burn = 225),
            day("Shoulders", ex("Seated Dumbbell Shoulder Press", rest = 120), ex("Cuff Cable Lateral Raises", 3, "12–20", 60), ex("Dumbbell Lateral Raise + Partial Drop", 3, "12–20", 60), ex("Reverse Pec Deck Rear Delt Fly", 3, "12–20", 60), ex("Rope Face Pulls", 3, "12–20", 60), burn = 200),
            day("Legs · Knee-Friendly", ex("Seated Leg Curl (warmup + working)", 4, "10–15", 75), ex("Hack Squat / Pendulum Squat", rest = 120, note = "Use a pain-free depth; choose pendulum or leg press if needed."), ex("Dumbbell Romanian Deadlift (RDL)", 3, "8–12", 90), ex("Leg Extension (2s peak hold)", 3, "12–15", 75), ex("Standing Calf Raise", 4, "10–15", 60), burn = 255),
            day("Arms", ex("EZ-Bar Preacher Curl", 3, "8–12", 75), ex("Close-Grip Bench / Weighted Dip", 3, "8–12", 90), ex("Incline Dumbbell Stretch Curl", 3, "10–15", 60), ex("Overhead Rope Tricep Extension", 3, "10–15", 60), ex("Hammer Rope Curl + Dual Pressdown Superset", 3, "12–15", 60), burn = 205),
            recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps")
        )),
        SplitSeed("ppl_upper", "Push / Pull / Legs + Upper", "Four training days plus optional lower body", listOf(
            day("Push", ex("Barbell Bench Press"), ex("Seated Dumbbell Shoulder Press"), ex("Incline Dumbbell Press"), ex("Cable Lateral Raise", 3), ex("Rope Triceps Pressdown", 3)),
            day("Pull", ex("Neutral-Grip Lat Pulldown"), ex("Chest-Supported Row"), ex("Cable Rear Delt Fly", 3), ex("Incline Dumbbell Curl", 3)),
            day("Legs", ex("Hack Squat", rest = 120), ex("Romanian Deadlift"), ex("Seated Leg Curl", 3), ex("Standing Calf Raise", 4)),
            day("Upper", ex("Incline Barbell Press"), ex("One-Arm Cable Row"), ex("Machine Chest Press"), ex("Lateral Raise", 3), ex("Cable Curl", 3)),
            day("Optional Lower", ex("Leg Press", rest = 120), ex("Hip Thrust"), ex("Leg Curl", 3), ex("Calf Raise", 3)), recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps")
        )),
        SplitSeed("upper_lower", "Upper / Lower Hypertrophy", "Four-day upper/lower rotation", listOf(
            day("Upper A", ex("Barbell Bench Press"), ex("Chest-Supported Row"), ex("Overhead Press"), ex("Lat Pulldown"), ex("Cable Lateral Raise", 3)),
            day("Lower A", ex("Hack Squat", rest = 120), ex("Romanian Deadlift"), ex("Seated Leg Curl", 3), ex("Calf Raise", 4)),
            day("Upper B", ex("Incline Dumbbell Press"), ex("One-Arm Dumbbell Row"), ex("Machine Shoulder Press"), ex("Cable Fly", 3), ex("Arms Superset", 3)),
            day("Lower B", ex("Leg Press", rest = 120), ex("Hip Thrust"), ex("Leg Extension", 3), ex("Lying Leg Curl", 3), ex("Calf Raise", 4)), recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps")
        )),
        SplitSeed("arnold", "Arnold Split", "Chest+Back · Shoulders+Arms · Legs · repeat", listOf(
            day("Chest + Back", ex("Incline Dumbbell Press"), ex("Flat Bench Press"), ex("Wide-Grip Pulldown"), ex("T-Bar Row"), ex("Cable Fly", 3)),
            day("Shoulders + Arms", ex("Seated Shoulder Press"), ex("Lateral Raise", 3), ex("EZ-Bar Curl", 3), ex("Skull Crusher", 3)),
            day("Legs", ex("Hack Squat", rest = 120), ex("Romanian Deadlift"), ex("Leg Curl", 3), ex("Calf Raise", 4)),
            day("Chest + Back", ex("Machine Chest Press"), ex("Cable Crossover", 3), ex("Neutral Pulldown"), ex("Chest-Supported Row")),
            day("Shoulders + Arms", ex("Machine Shoulder Press"), ex("Rear Delt Fly", 3), ex("Preacher Curl", 3), ex("Rope Pressdown", 3)), recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps")
        )),
        SplitSeed("athletic", "Full Body Athletic", "Three-day strength and conditioning", listOf(
            day("Full Body A", ex("Trap Bar Deadlift", 4, "5–8", 150), ex("Dumbbell Bench Press"), ex("Pull-Up / Assisted Pull-Up"), ex("Sled Push", 4, "20–30m")),
            recovery("Mobility & 15,000 Steps"), day("Full Body B", ex("Front Squat", rest = 150), ex("Landmine Press"), ex("Dumbbell Romanian Deadlift"), ex("Farmer Carry", 4, "30–40m")),
            recovery("Mobility & 15,000 Steps"), day("Full Body C", ex("Goblet Squat"), ex("Incline Dumbbell Press"), ex("Cable Row"), ex("Bike Intervals", 5, "45s / 75s")), recovery("Active Recovery & 15,000 Steps"), recovery("Active Recovery & 15,000 Steps")
        ))
    )
}

data class ExerciseSession(val exercise: ExerciseEntity, val sets: List<ExerciseSetEntity>)
data class RecoverySnapshot(val sleepMinutes: Int? = null, val restingHeartRate: Int? = null, val autoRegulationTip: String? = null)
private data class WorkoutInputs(val splitId: String, val selectedDay: Int, val date: String, val splits: List<WorkoutSplitEntity>, val sets: List<ExerciseSetEntity>)
data class WorkoutUiState(
    val splits: List<WorkoutSplitEntity> = emptyList(), val selectedSplitId: String = "bro_split", val selectedDay: Int = LocalDate.now().dayOfWeek.value - 1,
    val days: List<WorkoutDayEntity> = emptyList(), val day: WorkoutDayEntity? = null, val exercises: List<ExerciseSession> = emptyList(),
    val remainingSeconds: Int = 90, val timerTargetElapsedMs: Long = 0L, val timerMuted: Boolean = false, val equipment: String = "Full Commercial Gym", val totalVolume: Long = 0L, val healthSyncStatus: String? = null, val coachNote: String? = null, val coachLoading: Boolean = false
)

class WorkoutViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = SplitCalDatabase.get(app).dao()
    private val prefs = app.splitCalPrefs
    private val healthSyncBridge = HealthSyncBridge(app)
    private val samsungHealthRepository = SamsungHealthRepository(app)
    private val selectedDate = MutableStateFlow(LocalDate.now().toString())
    private val splitId = prefs.data.map { it[stringPreferencesKey("active_split")] ?: "bro_split" }.stateIn(viewModelScope, SharingStarted.Eagerly, "bro_split")
    private val selectedDay = MutableStateFlow(LocalDate.now().dayOfWeek.value - 1)
    private val equipment = MutableStateFlow("Full Commercial Gym")
    private val timerTarget = MutableStateFlow(0L)
    private val timerMuted = prefs.data.map { it[booleanPreferencesKey("workout_timer_muted")] ?: false }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val now = MutableStateFlow(SystemClock.elapsedRealtime())
    private var pausedSeconds = 90
    private var activeWorkoutStartMs: Long? = null
    private var lastAutoSyncKey: String? = null
    private val _healthSyncStatus = MutableStateFlow<String?>(null)
    private val _coachNote = MutableStateFlow<String?>(null)
    private val _coachLoading = MutableStateFlow(false)
    private val _recoverySnapshot = MutableStateFlow<RecoverySnapshot?>(null)
    val recoverySnapshot: StateFlow<RecoverySnapshot?> = _recoverySnapshot
    fun refreshRecovery() = viewModelScope.launch { _recoverySnapshot.value = samsungHealthRepository.readRecovery() }

    private val workoutContent: Flow<WorkoutUiState> = combine(splitId, selectedDay, selectedDate, dao.splits(), dao.allExerciseSets()) { id, dayIndex, date, splits, allSets -> WorkoutInputs(id, dayIndex, date, splits, allSets.filter { it.date == date }) }
        .flatMapLatest { input ->
            dao.workoutDays(input.splitId).flatMapLatest { days ->
                val activeDay = days.firstOrNull { it.dayIndex == input.selectedDay }
                if (activeDay == null) flowOf(WorkoutUiState(splits = input.splits, selectedSplitId = input.splitId, selectedDay = input.selectedDay, days = days))
                else dao.exerciseEntities(activeDay.id).map { exercises ->
                    WorkoutUiState(splits = input.splits, selectedSplitId = input.splitId, selectedDay = input.selectedDay, days = days, day = activeDay,
                        exercises = exercises.map { exercise -> ExerciseSession(exercise, input.sets.filter { it.exerciseId == exercise.id }.sortedBy { it.setNumber }) },
                        totalVolume = input.sets.filter { it.isCompleted }.sumOf { (it.weightLbs * it.reps).toLong() })
                }
            }
        }
    val state: StateFlow<WorkoutUiState> = combine(workoutContent, timerTarget, timerMuted, equipment, now) { content, target, muted, gear, time ->
        content.copy(timerTargetElapsedMs = target, remainingSeconds = if (target > time) ((target - time + 999) / 1000).toInt() else if (target == 0L) pausedSeconds else 90, timerMuted = muted, equipment = gear)
    }.combine(_healthSyncStatus) { content, syncStatus -> content.copy(healthSyncStatus = syncStatus) }
        .combine(_coachNote) { content, note -> content.copy(coachNote = note) }
        .combine(_coachLoading) { content, loading -> content.copy(coachLoading = loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    init {
        refreshRecovery()
        viewModelScope.launch { seed(); val p = prefs.data.first(); timerTarget.value = p[longPreferencesKey("workout_timer_target")] ?: 0L }
        viewModelScope.launch {
            while (true) {
                now.value = SystemClock.elapsedRealtime()
                val target = timerTarget.value
                if (target > now.value) pausedSeconds = ((target - now.value + 999) / 1000).toInt()
                if (target > 0 && now.value >= target) finishTimer()
                delay(200)
            }
        }
        viewModelScope.launch { prefs.data.collectLatest { data -> val value = data[longPreferencesKey("workout_timer_target")] ?: 0L; if (value != timerTarget.value) timerTarget.value = value } }
        viewModelScope.launch { state.map { it.exercises }.distinctUntilChanged().collectLatest { rows -> ensureSets(rows) } }
        viewModelScope.launch {
            state.map { snapshot -> snapshot.day?.id to snapshot.exercises.flatMap { it.sets }.filter { it.setType == SetType.WORKING }.let { sets -> sets.isNotEmpty() && sets.all { it.isCompleted } } }
                .distinctUntilChanged().collectLatest { (dayId, allDone) ->
                    if (dayId != null && allDone && lastAutoSyncKey != dayId) finishAndSyncWorkout(dayId)
                }
        }
    }

    private suspend fun seed() {
        dao.insertSplits(SplitGenerator.templates.map { WorkoutSplitEntity(it.id, it.name, it.description) })
        SplitGenerator.templates.forEach { split ->
            val days = split.days.mapIndexed { index, day -> WorkoutDayEntity("${split.id}-day-$index", split.id, index, day.focus, day.notes, day.burn) }
            dao.insertWorkoutDays(days)
            dao.insertExerciseEntities(split.days.flatMapIndexed { dayIndex, day -> day.exercises.mapIndexed { order, ex ->
                val dayId = "${split.id}-day-$dayIndex"
                ExerciseEntity("${dayId}-exercise-$order", dayId, ex.name, order, ex.sets, ex.reps, ex.rest, ex.equipment, ex.jointNote)
            } })
        }
    }

    private suspend fun ensureSets(rows: List<ExerciseSession>) {
        rows.forEach { session ->
            val existing = dao.setsForExerciseOnce(selectedDate.value, session.exercise.id)
            if (existing.isEmpty() && selectedDate.value == LocalDate.now().toString()) repeat(session.exercise.plannedSets) { n ->
                dao.insertWorkoutSet(ExerciseSetEntity(date = selectedDate.value, exerciseName = session.exercise.name, exerciseId = session.exercise.id, setNumber = n + 1, setType = if (n == 0 && "warmup" in session.exercise.name.lowercase()) SetType.WARMUP else SetType.WORKING))
            }
        }
    }

    fun chooseSplit(id: String) { viewModelScope.launch { prefs.edit { it[stringPreferencesKey("active_split")] = id }; selectedDay.value = 0 } }
    fun setDate(value: String) { runCatching { LocalDate.parse(value) }.onSuccess { selectedDate.value = value; selectedDay.value = it.dayOfWeek.value - 1; _coachNote.value = null } }
    fun chooseDay(day: Int) { selectedDay.value = day.coerceIn(0, 6) }
    fun chooseEquipment(value: String) { equipment.value = value }
    fun requestAiCoach() = viewModelScope.launch {
        _coachLoading.value = true
        val current = state.value
        val recovery = _recoverySnapshot.value
        val key = prefs.data.first()[stringPreferencesKey("gemini_key")].orEmpty()
        val offline = when {
            (recovery?.sleepMinutes ?: 480) < 390 -> "Sleep was under 6.5 hours. Keep 2–3 reps in reserve, lower today's RPE target by 0.5, and avoid grinding reps."
            else -> "Follow today's plan with controlled reps. Keep 1–3 reps in reserve and stop any movement that causes joint pain."
        }
        _coachNote.value = if (key.isBlank()) offline else runCatching {
            GenerativeModel(modelName = "gemini-2.5-flash", apiKey = key).generateContent(
                "Give concise, safety-minded coaching for ${current.day?.focus ?: "today's training"}. Planned exercises: ${current.exercises.joinToString { it.exercise.name }}. Session volume so far: ${current.totalVolume} lb. Sleep: ${recovery?.sleepMinutes ?: "unknown"} minutes; average/resting heart rate: ${recovery?.restingHeartRate ?: "unknown"} bpm. Return a short actionable note; avoid medical claims."
            ).text?.takeIf { it.isNotBlank() } ?: offline
        }.getOrDefault(offline)
        _coachLoading.value = false
    }
    fun updateSet(set: ExerciseSetEntity) = viewModelScope.launch { dao.updateSet(set.copy(weight = set.weightLbs, type = set.setType.name.lowercase().replaceFirstChar(Char::uppercase), legacyCompleted = set.isCompleted)) }
    fun cycleSetType(set: ExerciseSetEntity) {
        val next = when (set.setType) { SetType.WARMUP -> SetType.WORKING; SetType.WORKING -> SetType.DROP; SetType.DROP -> SetType.FAILURE; SetType.FAILURE -> SetType.WORKING }
        updateSet(set.copy(setType = next))
    }
    fun completeSet(set: ExerciseSetEntity, completed: Boolean, restSeconds: Int) {
        if (completed && activeWorkoutStartMs == null) activeWorkoutStartMs = System.currentTimeMillis()
        updateSet(set.copy(isCompleted = completed, burnCalories = if (completed) 12 else 0))
        if (completed) startTimer(restSeconds)
    }
    fun finishAndSyncWorkout(dayId: String? = state.value.day?.id) = viewModelScope.launch {
        if (selectedDate.value != LocalDate.now().toString()) return@launch
        val current = state.value
        val day = current.day ?: return@launch
        if (dayId == null || lastAutoSyncKey == dayId) return@launch
        lastAutoSyncKey = dayId
        val endMs = System.currentTimeMillis()
        val duration = activeWorkoutStartMs?.let { ((endMs - it).coerceAtLeast(60_000L) / 60_000L).toInt() } ?: (current.exercises.sumOf { it.exercise.plannedSets } * 3).coerceAtLeast(1)
        val calories = current.exercises.flatMap { it.sets }.filter { it.isCompleted }.sumOf { it.burnCalories }.takeIf { it > 0 } ?: day.estimatedBurnCalories
        val synced = healthSyncBridge.writeWorkoutToSamsungHealth(day.focus, duration, calories)
        _healthSyncStatus.value = if (synced) "Synced to Room + Samsung Health" else "Workout saved · Health Connect sync unavailable or not permitted"
    }
    fun swapExercise(current: ExerciseEntity, replacement: String) = viewModelScope.launch {
        dao.updateExerciseEntity(current.copy(name = replacement))
    }
    fun startTimer(seconds: Int) { pausedSeconds = seconds.coerceAtLeast(1); val target = SystemClock.elapsedRealtime() + pausedSeconds * 1000L; timerTarget.value = target; viewModelScope.launch { prefs.edit { it[longPreferencesKey("workout_timer_target")] = target } } }
    fun toggleTimer() { val current = timerTarget.value; if (current > 0) { pausedSeconds = ((current - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000L).toInt().coerceAtLeast(1); resetTimer() } else startTimer(pausedSeconds) }
    fun resetTimer() { pausedSeconds = 90; timerTarget.value = 0L; viewModelScope.launch { prefs.edit { it.remove(longPreferencesKey("workout_timer_target")) } } }
    fun toggleMute() = viewModelScope.launch { prefs.edit { it[booleanPreferencesKey("workout_timer_muted")] = !timerMuted.value } }

    private suspend fun finishTimer() {
        val target = timerTarget.value
        if (target == 0L || SystemClock.elapsedRealtime() < target) return
        pausedSeconds = 90
        timerTarget.value = 0L
        prefs.edit { it.remove(longPreferencesKey("workout_timer_target")) }
        if (!timerMuted.value) {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 300); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 400) } }
            runCatching {
                val vibrator = getApplication<Application>().getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator?.vibrate(250)
            }
        }
    }
}
