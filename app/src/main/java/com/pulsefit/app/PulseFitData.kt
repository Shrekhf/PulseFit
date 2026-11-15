package com.pulsefit.app

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay

val Application.pulseFitPrefs by preferencesDataStore("pulsefit_settings")

val PREFER_SAMSUNG_HEALTH_ORIGIN = booleanPreferencesKey("prefer_samsung_health_origin")
val AUTO_WRITE_TO_SAMSUNG_HEALTH = booleanPreferencesKey("auto_write_to_samsung_health")

class UserPreferencesRepository(private val context: Context) {
    private val store = (context.applicationContext as Application).pulseFitPrefs
    val preferSamsungHealthOrigin: Flow<Boolean> = store.data.map { it[PREFER_SAMSUNG_HEALTH_ORIGIN] ?: true }
    val autoWriteToSamsungHealth: Flow<Boolean> = store.data.map { it[AUTO_WRITE_TO_SAMSUNG_HEALTH] ?: true }
}

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

enum class SetType { WARMUP, WORKING, DROP, FAILURE }

class PulseFitConverters {
    @TypeConverter fun setTypeToString(value: SetType): String = value.name
    @TypeConverter fun stringToSetType(value: String): SetType = runCatching { SetType.valueOf(value) }.getOrDefault(SetType.WORKING)
}

@Entity(tableName = "workout_days", foreignKeys = [ForeignKey(entity = WorkoutSplitEntity::class, parentColumns = ["id"], childColumns = ["splitId"], onDelete = ForeignKey.CASCADE)], indices = [Index("splitId"), Index(value = ["splitId", "dayIndex"], unique = true)])
data class WorkoutDayEntity(@PrimaryKey val id: String, val splitId: String, val dayIndex: Int, val focus: String, val notes: String, val estimatedBurnCalories: Int = 0)

@Entity(tableName = "exercises", foreignKeys = [ForeignKey(entity = WorkoutDayEntity::class, parentColumns = ["id"], childColumns = ["workoutDayId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workoutDayId")])
data class ExerciseEntity(@PrimaryKey val id: String, val workoutDayId: String, val name: String, val sortOrder: Int, val plannedSets: Int, val repRange: String, val restSeconds: Int, val equipment: String, val jointNote: String)

@Entity(tableName = "exercise_sets", indices = [Index("date"), Index("exerciseName"), Index("exerciseId")])
data class ExerciseSetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,
    val exerciseName: String,
    val exerciseId: String = "",
    val setNumber: Int = 1,
    val setType: SetType = SetType.WORKING,
    val weightLbs: Float = 0f,
    val reps: Int = 0,
    val rpe: Float = 8f,
    val isCompleted: Boolean = false,
    val burnCalories: Int = 0,
    // Kept for migration compatibility with the original prototype schema.
    val weight: Float = 0f,
    val type: String = "Working",
    @ColumnInfo(name = "completed") val legacyCompleted: Boolean = false
)

data class IngredientTotals(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int)

@Dao interface PulseFitDao {
    @Query("SELECT * FROM daily_logs") fun allDailyLogs(): Flow<List<DailyLogEntity>>
    @Query("SELECT * FROM meals") fun allMeals(): Flow<List<MealEntity>>
    @Query("SELECT * FROM exercise_sets") fun allExerciseSets(): Flow<List<ExerciseSetEntity>>
    @Query("SELECT * FROM daily_logs") suspend fun dailyLogsOnce(): List<DailyLogEntity>
    @Query("SELECT * FROM meals") suspend fun mealsOnce(): List<MealEntity>
    @Query("SELECT * FROM ingredients") suspend fun ingredientsOnce(): List<IngredientItem>
    @Query("SELECT * FROM workout_splits") suspend fun splitsOnce(): List<WorkoutSplitEntity>
    @Query("SELECT * FROM planned_exercises") suspend fun plannedExercisesOnce(): List<PlannedExerciseEntity>
    @Query("SELECT * FROM workout_days") suspend fun workoutDaysOnce(): List<WorkoutDayEntity>
    @Query("SELECT * FROM exercises") suspend fun exerciseEntitiesOnce(): List<ExerciseEntity>
    @Query("SELECT * FROM exercise_sets") suspend fun exerciseSetsOnce(): List<ExerciseSetEntity>
    @Query("DELETE FROM ingredients") suspend fun clearIngredients()
    @Query("DELETE FROM meals") suspend fun clearMeals()
    @Query("DELETE FROM exercise_sets") suspend fun clearExerciseSets()
    @Query("DELETE FROM planned_exercises") suspend fun clearPlannedExercises()
    @Query("DELETE FROM exercises") suspend fun clearExerciseEntities()
    @Query("DELETE FROM workout_days") suspend fun clearWorkoutDays()
    @Query("DELETE FROM workout_splits") suspend fun clearSplits()
    @Query("DELETE FROM daily_logs") suspend fun clearDailyLogs()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreDailyLogs(rows: List<DailyLogEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreMeals(rows: List<MealEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreIngredients(rows: List<IngredientItem>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSplits(rows: List<WorkoutSplitEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restorePlannedExercises(rows: List<PlannedExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWorkoutDays(rows: List<WorkoutDayEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreExerciseEntities(rows: List<ExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreExerciseSets(rows: List<ExerciseSetEntity>)
    @Query("SELECT * FROM daily_logs WHERE date = :date") fun dailyLog(date: String): Flow<DailyLogEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDailyLog(log: DailyLogEntity)
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY rowid DESC") fun meals(date: String): Flow<List<MealEntity>>
    @Insert suspend fun insertMeal(meal: MealEntity)
    @Query("SELECT * FROM meals WHERE id = :id LIMIT 1") suspend fun mealOnce(id: String): MealEntity?
    @Update suspend fun updateMeal(meal: MealEntity)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Query("DELETE FROM ingredients WHERE mealId = :mealId") suspend fun deleteIngredientsForMeal(mealId: String)
    @Insert suspend fun insertIngredients(items: List<IngredientItem>)
    @Query("SELECT * FROM ingredients WHERE mealId = :mealId") fun ingredients(mealId: String): Flow<List<IngredientItem>>
    @Query("SELECT * FROM ingredients WHERE mealId = :mealId") suspend fun ingredientsOnce(mealId: String): List<IngredientItem>
    @Query("SELECT * FROM workout_splits ORDER BY rowid") fun splits(): Flow<List<WorkoutSplitEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSplits(items: List<WorkoutSplitEntity>)
    @Query("SELECT * FROM planned_exercises WHERE splitId = :splitId AND day = :day ORDER BY rowid") fun exercises(splitId: String, day: Int): Flow<List<PlannedExerciseEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPlannedExercises(items: List<PlannedExerciseEntity>)
    @Query("SELECT * FROM exercise_sets WHERE date = :date ORDER BY rowid") fun exerciseSets(date: String): Flow<List<ExerciseSetEntity>>
    @Insert suspend fun insertSet(set: ExerciseSetEntity)
    @Update suspend fun updateSet(set: ExerciseSetEntity)
    @Query("UPDATE exercise_sets SET completed = :complete, isCompleted = :complete WHERE id = :id") suspend fun setComplete(id: String, complete: Boolean)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkoutDays(items: List<WorkoutDayEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExerciseEntities(items: List<ExerciseEntity>)
    @Update suspend fun updateExerciseEntity(item: ExerciseEntity)
    @Query("SELECT * FROM workout_days WHERE splitId = :splitId ORDER BY dayIndex") fun workoutDays(splitId: String): Flow<List<WorkoutDayEntity>>
    @Query("SELECT * FROM exercises WHERE workoutDayId = :dayId ORDER BY sortOrder") fun exerciseEntities(dayId: String): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercise_sets WHERE date = :date AND exerciseId = :exerciseId ORDER BY setNumber") fun setsForExercise(date: String, exerciseId: String): Flow<List<ExerciseSetEntity>>
    @Query("SELECT * FROM exercise_sets WHERE date = :date AND exerciseId = :exerciseId ORDER BY setNumber") suspend fun setsForExerciseOnce(date: String, exerciseId: String): List<ExerciseSetEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkoutSet(set: ExerciseSetEntity)
    @Query("SELECT * FROM workout_splits ORDER BY rowid") suspend fun workoutSplitsOnce(): List<WorkoutSplitEntity>
    @Query("SELECT * FROM exercise_sets WHERE date = :date AND isCompleted = 1") fun completedWorkoutSets(date: String): Flow<List<ExerciseSetEntity>>
}

@Database(entities = [DailyLogEntity::class, MealEntity::class, IngredientItem::class, WorkoutSplitEntity::class, PlannedExerciseEntity::class, WorkoutDayEntity::class, ExerciseEntity::class, ExerciseSetEntity::class], version = 3, exportSchema = false)
@TypeConverters(PulseFitConverters::class)
abstract class PulseFitDatabase : RoomDatabase() {
    abstract fun dao(): PulseFitDao
    companion object {
        @Volatile private var instance: PulseFitDatabase? = null
        fun get(app: Application): PulseFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(app, PulseFitDatabase::class.java, "pulsefit.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS workout_days (id TEXT NOT NULL PRIMARY KEY, splitId TEXT NOT NULL, dayIndex INTEGER NOT NULL, focus TEXT NOT NULL, notes TEXT NOT NULL, estimatedBurnCalories INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(splitId) REFERENCES workout_splits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_days_splitId ON workout_days(splitId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workout_days_splitId_dayIndex ON workout_days(splitId, dayIndex)")
                db.execSQL("CREATE TABLE IF NOT EXISTS exercises (id TEXT NOT NULL PRIMARY KEY, workoutDayId TEXT NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL, plannedSets INTEGER NOT NULL, repRange TEXT NOT NULL, restSeconds INTEGER NOT NULL, equipment TEXT NOT NULL, jointNote TEXT NOT NULL, FOREIGN KEY(workoutDayId) REFERENCES workout_days(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercises_workoutDayId ON exercises(workoutDayId)")
                db.execSQL("ALTER TABLE exercise_sets ADD COLUMN setNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE exercise_sets ADD COLUMN setType TEXT NOT NULL DEFAULT 'WORKING'")
                db.execSQL("ALTER TABLE exercise_sets ADD COLUMN weightLbs REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercise_sets ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE exercise_sets SET weightLbs = weight, isCompleted = completed, setType = CASE lower(type) WHEN 'warmup' THEN 'WARMUP' WHEN 'drop' THEN 'DROP' WHEN 'failure' THEN 'FAILURE' ELSE 'WORKING' END")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_sets_exerciseName ON exercise_sets(exerciseName)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercise_sets ADD COLUMN exerciseId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_sets_exerciseId ON exercise_sets(exerciseId)")
            }
        }
    }
}

data class Settings(val calorieTarget: Int = 2150, val proteinTarget: Int = 185, val carbTarget: Int = 200, val fatTarget: Int = 65, val stepGoal: Int = 15000, val calibrationMode: String = "personal_500", val geminiKey: String = "", val wearableActiveCalories: Int? = null, val preferSamsungOrigin: Boolean = true, val autoWriteSamsung: Boolean = true)

data class DailyChartPoint(val date: String, val intake: Int, val burn: Int)

data class PulseFitUiState(
    val date: String = LocalDate.now().toString(), val steps: Int = 12450, val settings: Settings = Settings(), val meals: List<MealEntity> = emptyList(),
    val sets: List<ExerciseSetEntity> = emptyList(), val splits: List<WorkoutSplitEntity> = emptyList(), val selectedSplitId: String = "bro_split",
    val selectedDay: Int = LocalDate.now().dayOfWeek.value - 1, val exercises: List<PlannedExerciseEntity> = emptyList(), val healthStatus: String = "Manual step entry",
    val stepStreak: Int = 0, val gymStreak: Int = 0, val week: List<DailyChartPoint> = emptyList()
) {
    val intake get() = meals.sumOf { it.kcal }
    val protein get() = meals.sumOf { it.protein }
    val carbs get() = meals.sumOf { it.carbs }
    val fat get() = meals.sumOf { it.fat }
    val stepBurn get() = StepBurnCalculator.calibrated(steps, settings.calibrationMode, settings.wearableActiveCalories)
    val workoutBurn get() = sets.filter { it.legacyCompleted }.sumOf { it.burnCalories }
    val remaining get() = settings.calorieTarget - intake + stepBurn + workoutBurn
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = PulseFitDatabase.get(application).dao()
    private val app = application
    private val date = MutableStateFlow(LocalDate.now())
    private val splitId = app.pulseFitPrefs.data.map { it[stringPreferencesKey("active_split")] ?: "bro_split" }.stateIn(viewModelScope, SharingStarted.Eagerly, "bro_split")
    private val selectedDay = MutableStateFlow(LocalDate.now().dayOfWeek.value - 1)
    private val healthStatus = MutableStateFlow("Manual step entry · Health Connect available")
    private val settingsFlow = app.pulseFitPrefs.data.map { p -> Settings(
        calorieTarget = p[intPreferencesKey("calories")] ?: 2150,
        proteinTarget = p[intPreferencesKey("protein")] ?: 185,
        carbTarget = p[intPreferencesKey("carbs")] ?: 200,
        fatTarget = p[intPreferencesKey("fat")] ?: 65,
        stepGoal = p[intPreferencesKey("step_goal")] ?: 15000,
        calibrationMode = p[stringPreferencesKey("calibration")] ?: "personal_500",
        geminiKey = p[stringPreferencesKey("gemini_key")] ?: "",
        wearableActiveCalories = p[intPreferencesKey("wearable_active_calories")],
        preferSamsungOrigin = p[PREFER_SAMSUNG_HEALTH_ORIGIN] ?: true,
        autoWriteSamsung = p[AUTO_WRITE_TO_SAMSUNG_HEALTH] ?: true
    ) }
    val state: StateFlow<PulseFitUiState> = date.flatMapLatest { selected -> combine(
        dao.dailyLog(selected.toString()), dao.meals(selected.toString()), dao.exerciseSets(selected.toString()), dao.splits(), settingsFlow, splitId, selectedDay, healthStatus,
        dao.allDailyLogs(), dao.allMeals(), dao.allExerciseSets()
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
        val logs = values[8] as List<DailyLogEntity>
        val allMeals = values[9] as List<MealEntity>
        val allSets = values[10] as List<ExerciseSetEntity>
        val selectedDate = selected.toString()
        val stepStreak = streak(selected, logs.filter { it.steps >= 15_000 }.map { it.date }.toSet())
        val gymStreak = streak(selected, allSets.filter { it.isCompleted && it.setType == SetType.WORKING }.map { it.date }.toSet())
        val weekStart = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        val days = (0..6).map { weekStart.plusDays(it.toLong()) }
        val week = days.map { d -> DailyChartPoint(d.toString(), allMeals.filter { it.date == d.toString() }.sumOf { it.kcal }, logs.firstOrNull { it.date == d.toString() }?.steps?.let { StepBurnCalculator.calibrated(it, settings.calibrationMode, if (d == LocalDate.now()) settings.wearableActiveCalories else null) }.orZero() + allSets.filter { it.date == d.toString() && it.isCompleted }.sumOf { it.burnCalories }) }
        PulseFitUiState(selectedDate, log?.steps ?: 0, settings, meals, sets, splits, sid, day, emptyList(), status, stepStreak, gymStreak, week)
    }.flatMapLatest { base ->
        dao.exercises(base.selectedSplitId, base.selectedDay).map { base.copy(exercises = it) }
    } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PulseFitUiState(date = LocalDate.now().toString()))

    init { viewModelScope.launch { seedSplits() } }

    fun selectDate(value: String) { runCatching { date.value = LocalDate.parse(value) } }
    private fun streak(anchor: LocalDate, completedDates: Set<String>): Int {
        var cursor = anchor
        if (cursor.toString() !in completedDates) cursor = cursor.minusDays(1)
        var count = 0
        while (cursor.toString() in completedDates) { count++; cursor = cursor.minusDays(1) }
        return count
    }
    private fun Int?.orZero() = this ?: 0

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

    fun selectSplit(id: String) { viewModelScope.launch { app.pulseFitPrefs.edit { it[stringPreferencesKey("active_split")] = id } } }
    fun selectDay(day: Int) { selectedDay.value = day }
    fun setHealthStatus(status: String) { healthStatus.value = status }
    fun updateSteps(value: Int) = viewModelScope.launch { dao.putDailyLog(DailyLogEntity(date.value.toString(), value.coerceAtLeast(0))) }
    fun setSamsungPreferences(prefer: Boolean, autoWrite: Boolean) = viewModelScope.launch { app.pulseFitPrefs.edit { it[PREFER_SAMSUNG_HEALTH_ORIGIN] = prefer; it[AUTO_WRITE_TO_SAMSUNG_HEALTH] = autoWrite } }
    fun saveSettings(settings: Settings) = viewModelScope.launch { app.pulseFitPrefs.edit { p ->
        p[intPreferencesKey("calories")] = settings.calorieTarget; p[intPreferencesKey("protein")] = settings.proteinTarget
        p[intPreferencesKey("carbs")] = settings.carbTarget; p[intPreferencesKey("fat")] = settings.fatTarget
        p[intPreferencesKey("step_goal")] = settings.stepGoal; p[stringPreferencesKey("calibration")] = settings.calibrationMode
        p[stringPreferencesKey("gemini_key")] = settings.geminiKey
        p[PREFER_SAMSUNG_HEALTH_ORIGIN] = settings.preferSamsungOrigin
        p[AUTO_WRITE_TO_SAMSUNG_HEALTH] = settings.autoWriteSamsung
    } }
    fun logMeal(slot: String, name: String, totals: IngredientTotals, ingredients: List<IngredientItem>) = viewModelScope.launch {
        val meal = MealEntity(date = date.value.toString(), slot = slot, name = name, kcal = totals.kcal, protein = totals.protein, carbs = totals.carbs, fat = totals.fat)
        dao.insertMeal(meal); dao.insertIngredients(ingredients.map { it.copy(mealId = meal.id) })
    }
    fun deleteMeal(id: String) = viewModelScope.launch { dao.deleteMeal(id) }
    fun logSet(exercise: PlannedExerciseEntity, weight: Float, reps: Int, rpe: Float, type: String) = viewModelScope.launch {
        val burn = if (type == "Working" || type == "Failure") 35 else 0
        val kind = runCatching { SetType.valueOf(type.uppercase()) }.getOrDefault(SetType.WORKING)
        dao.insertSet(ExerciseSetEntity(date = date.value.toString(), exerciseName = exercise.name, weight = weight, weightLbs = weight, reps = reps, rpe = rpe, type = type, setType = kind, burnCalories = burn, legacyCompleted = true, isCompleted = true))
    }
    fun addStepSet(id: String, done: Boolean) = viewModelScope.launch { dao.setComplete(id, done) }
}
