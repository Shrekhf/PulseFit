package com.pulsefit.app

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

private val Application.pulseFitPrefs by preferencesDataStore("pulsefit_settings")

@Entity(tableName = "daily_logs")
data class DailyLogEntity(@PrimaryKey val date: String, val steps: Int = 0)

@Entity(tableName = "meals")
data class MealEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val date: String, val slot: String, val name: String, val kcal: Int, val protein: Int, val carbs: Int, val fat: Int)

@Entity(tableName = "ingredients", foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)], indices = [Index("mealId")])
data class IngredientItem(@PrimaryKey val id: String = UUID.randomUUID().toString(), val mealId: String, val name: String, val baseKcal: Int, val baseProtein: Int, val baseCarbs: Int, val baseFat: Int, val multiplier: Float = 1f, val included: Boolean = true)

@Entity(tableName = "workout_splits")
data class WorkoutSplitEntity(@PrimaryKey val id: String, val name: String, val description: String)

@Entity(tableName = "planned_exercises", foreignKeys = [ForeignKey(entity = WorkoutSplitEntity::class, parentColumns = ["id"], childColumns = ["splitId"], onDelete = ForeignKey.CASCADE)], indices = [Index("splitId")])
data class PlannedExerciseEntity(@PrimaryKey val id: String, val splitId: String, val day: Int, val name: String, val sets: Int, val reps: String, val restSeconds: Int, val burnCalories: Int)

@Entity(tableName = "exercise_sets", indices = [Index("date")])
data class ExerciseSetEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val date: String, val exerciseName: String, val weight: Float, val reps: Int, val rpe: Float, val type: String, val completed: Boolean = true, val burnCalories: Int = 0)

data class IngredientTotals(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int)

@Dao interface PulseFitDao {
    @Query("SELECT * FROM daily_logs WHERE date = :date") fun dailyLog(date: String): Flow<DailyLogEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDailyLog(log: DailyLogEntity)
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY rowid DESC") fun meals(date: String): Flow<List<MealEntity>>
    @Insert suspend fun insertMeal(meal: MealEntity)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Insert suspend fun insertIngredients(items: List<IngredientItem>)
    @Query("SELECT * FROM ingredients WHERE mealId = :mealId") fun ingredients(mealId: String): Flow<List<IngredientItem>>
    @Query("SELECT * FROM workout_splits ORDER BY rowid") fun splits(): Flow<List<WorkoutSplitEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSplits(items: List<WorkoutSplitEntity>)
    @Query("SELECT * FROM planned_exercises WHERE splitId = :splitId AND day = :day ORDER BY rowid") fun exercises(splitId: String, day: Int): Flow<List<PlannedExerciseEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPlannedExercises(items: List<PlannedExerciseEntity>)
    @Query("SELECT * FROM exercise_sets WHERE date = :date") fun exerciseSets(date: String): Flow<List<ExerciseSetEntity>>
    @Insert suspend fun insertSet(set: ExerciseSetEntity)
    @Query("UPDATE exercise_sets SET completed = :complete WHERE id = :id") suspend fun setComplete(id: String, complete: Boolean)
}

@Database(entities = [DailyLogEntity::class, MealEntity::class, IngredientItem::class, WorkoutSplitEntity::class, PlannedExerciseEntity::class, ExerciseSetEntity::class], version = 1, exportSchema = false)
abstract class PulseFitDatabase : RoomDatabase() {
    abstract fun dao(): PulseFitDao
    companion object {
        @Volatile private var instance: PulseFitDatabase? = null
        fun get(app: Application): PulseFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(app, PulseFitDatabase::class.java, "pulsefit.db").build().also { instance = it }
        }
    }
}

data class Settings(val calorieTarget: Int = 2150, val proteinTarget: Int = 185, val carbTarget: Int = 200, val fatTarget: Int = 65, val stepGoal: Int = 15000, val calibrationMode: String = "personal_500", val geminiKey: String = "")

data class PulseFitUiState(
    val date: String = LocalDate.now().toString(), val steps: Int = 12450, val settings: Settings = Settings(), val meals: List<MealEntity> = emptyList(),
    val sets: List<ExerciseSetEntity> = emptyList(), val splits: List<WorkoutSplitEntity> = emptyList(), val selectedSplitId: String = "bro_split",
    val selectedDay: Int = LocalDate.now().dayOfWeek.value - 1, val exercises: List<PlannedExerciseEntity> = emptyList(), val healthStatus: String = "Manual step entry"
) {
    val intake get() = meals.sumOf { it.kcal }
    val protein get() = meals.sumOf { it.protein }
    val carbs get() = meals.sumOf { it.carbs }
    val fat get() = meals.sumOf { it.fat }
    val stepBurn get() = steps * when (settings.calibrationMode) { "tracker_high" -> 700; "conservative" -> 420; else -> 500 } / 15000
    val workoutBurn get() = sets.filter { it.completed }.sumOf { it.burnCalories }
    val remaining get() = settings.calorieTarget - intake + stepBurn + workoutBurn
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = PulseFitDatabase.get(application).dao()
    private val app = application
    private val date = LocalDate.now().toString()
    private val splitId = MutableStateFlow("bro_split")
    private val selectedDay = MutableStateFlow(LocalDate.now().dayOfWeek.value - 1)
    private val healthStatus = MutableStateFlow("Manual step entry · Health Connect available")
    private val settingsFlow = app.pulseFitPrefs.data.map { p -> Settings(
        calorieTarget = p[intPreferencesKey("calories")] ?: 2150,
        proteinTarget = p[intPreferencesKey("protein")] ?: 185,
        carbTarget = p[intPreferencesKey("carbs")] ?: 200,
        fatTarget = p[intPreferencesKey("fat")] ?: 65,
        stepGoal = p[intPreferencesKey("step_goal")] ?: 15000,
        calibrationMode = p[stringPreferencesKey("calibration")] ?: "personal_500",
        geminiKey = p[stringPreferencesKey("gemini_key")] ?: ""
    ) }
    val state: StateFlow<PulseFitUiState> = combine(
        dao.dailyLog(date), dao.meals(date), dao.exerciseSets(date), dao.splits(), settingsFlow, splitId, selectedDay, healthStatus
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val log = values[0] as DailyLogEntity?
        val meals = values[1] as List<MealEntity>
        val sets = values[2] as List<ExerciseSetEntity>
        val splits = values[3] as List<WorkoutSplitEntity>
        val settings = values[4] as Settings
        val sid = values[5] as String
        val day = values[6] as Int
        val status = values[7] as String
        PulseFitUiState(date, log?.steps ?: 12450, settings, meals, sets, splits, sid, day, emptyList(), status)
    }.flatMapLatest { base ->
        dao.exercises(base.selectedSplitId, base.selectedDay).map { base.copy(exercises = it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PulseFitUiState(date = date))

    init { viewModelScope.launch { dao.putDailyLog(DailyLogEntity(date, dao.dailyLog(date).first()?.steps ?: 12450)); seedSplits() } }

    private suspend fun seedSplits() {
        val splits = listOf(
            WorkoutSplitEntity("bro_split", "5-Day Bro Split", "Chest · Back · Shoulders · Legs · Arms"),
            WorkoutSplitEntity("ppl_upper", "Push / Pull / Legs + Upper", "Push · Pull · Legs · Upper · Optional lower"),
            WorkoutSplitEntity("upper_lower", "Upper / Lower Hypertrophy", "Four-day upper / lower rotation"),
            WorkoutSplitEntity("arnold", "Arnold Split", "Chest+Back · Shoulders+Arms · Legs"),
            WorkoutSplitEntity("athletic", "Full Body Athletic", "Three-day strength & conditioning")
        )
        dao.insertSplits(splits)
        val definitions = mapOf(
            "bro_split" to listOf("Chest" to listOf("Incline Barbell Press", "Flat Dumbbell Press", "Weighted Chest Dips", "Cable Chest Fly"), "Back" to listOf("Lat Pulldown", "Chest-Supported Row", "Single-Arm Dumbbell Row"), "Shoulders" to listOf("Seated Shoulder Press", "Cable Lateral Raise", "Face Pull"), "Legs" to listOf("Hack Squat", "Romanian Deadlift", "Leg Extension"), "Arms" to listOf("EZ-Bar Preacher Curl", "Close-Grip Bench Press", "Overhead Rope Extension"), "Active Recovery · 15,000 Steps" to emptyList(), "Full Rest & Meal Prep" to emptyList()),
            "ppl_upper" to listOf("Push" to listOf("Bench Press", "Overhead Press", "Cable Fly"), "Pull" to listOf("Lat Pulldown", "Barbell Row", "Face Pull"), "Legs" to listOf("Squat", "Romanian Deadlift", "Calf Raise"), "Upper" to listOf("Incline Press", "Cable Row", "Lateral Raise"), "Lower" to listOf("Leg Press", "Leg Curl")),
            "upper_lower" to listOf("Upper A" to listOf("Bench Press", "Barbell Row", "Lateral Raise"), "Lower A" to listOf("Squat", "Romanian Deadlift", "Calf Raise"), "Upper B" to listOf("Incline Press", "Lat Pulldown", "Curl"), "Lower B" to listOf("Leg Press", "Leg Curl", "Hip Thrust")),
            "arnold" to listOf("Chest + Back" to listOf("Incline Press", "Dumbbell Fly", "Pull-Up", "T-Bar Row"), "Shoulders + Arms" to listOf("Shoulder Press", "Lateral Raise", "EZ Curl", "Skull Crusher"), "Legs" to listOf("Squat", "Leg Curl", "Calf Raise"), "Chest + Back" to listOf("Bench Press", "Cable Fly", "Pulldown", "Cable Row"), "Shoulders + Arms" to listOf("Rear Delt Fly", "Preacher Curl", "Triceps Pushdown")),
            "athletic" to listOf("Full Body A" to listOf("Trap Bar Deadlift", "Push Press", "Pull-Up", "Sled Push"), "Full Body B" to listOf("Front Squat", "Bench Press", "Row", "Farmer Carry"), "Full Body C" to listOf("Romanian Deadlift", "Landmine Press", "Split Squat", "Bike Intervals"))
        )
        definitions.forEach { (id, days) -> dao.insertPlannedExercises(days.flatMapIndexed { day, (focus, names) -> names.mapIndexed { index, name -> PlannedExerciseEntity("$id-$day-$index", id, day, name, 3 + (index % 2), "8–12", 90, if (names.isEmpty()) 0 else 35) } }) }
    }

    fun selectSplit(id: String) { splitId.value = id }
    fun selectDay(day: Int) { selectedDay.value = day }
    fun setHealthStatus(status: String) { healthStatus.value = status }
    fun updateSteps(value: Int) = viewModelScope.launch { dao.putDailyLog(DailyLogEntity(date, value.coerceAtLeast(0))) }
    fun saveSettings(settings: Settings) = viewModelScope.launch { app.pulseFitPrefs.edit { p ->
        p[intPreferencesKey("calories")] = settings.calorieTarget; p[intPreferencesKey("protein")] = settings.proteinTarget
        p[intPreferencesKey("carbs")] = settings.carbTarget; p[intPreferencesKey("fat")] = settings.fatTarget
        p[intPreferencesKey("step_goal")] = settings.stepGoal; p[stringPreferencesKey("calibration")] = settings.calibrationMode
        p[stringPreferencesKey("gemini_key")] = settings.geminiKey
    } }
    fun logMeal(slot: String, name: String, totals: IngredientTotals, ingredients: List<IngredientItem>) = viewModelScope.launch {
        val meal = MealEntity(date = date, slot = slot, name = name, kcal = totals.kcal, protein = totals.protein, carbs = totals.carbs, fat = totals.fat)
        dao.insertMeal(meal); dao.insertIngredients(ingredients.map { it.copy(mealId = meal.id) })
    }
    fun deleteMeal(id: String) = viewModelScope.launch { dao.deleteMeal(id) }
    fun logSet(exercise: PlannedExerciseEntity, weight: Float, reps: Int, rpe: Float, type: String) = viewModelScope.launch {
        val burn = if (type == "Working" || type == "Failure") 35 else 0
        dao.insertSet(ExerciseSetEntity(date = date, exerciseName = exercise.name, weight = weight, reps = reps, rpe = rpe, type = type, burnCalories = burn))
    }
    fun addStepSet(id: String, done: Boolean) = viewModelScope.launch { dao.setComplete(id, done) }
}
