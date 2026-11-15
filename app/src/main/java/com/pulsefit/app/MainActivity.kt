package com.pulsefit.app

import android.os.Bundle
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val Bg = Color(0xFF0B1120)
private val Card = Color(0xFF151E2E)
private val Raised = Color(0xFF1E293B)
private val Emerald = Color(0xFF10B981)
private val Cyan = Color(0xFF06B6D4)
private val Muted = Color(0xFF94A3B8)
private val White = Color(0xFFF8FAFC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PulseFitApp() } }
}

@Composable
private fun PulseFitApp(vm: DashboardViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }
    var showSetDialog by remember { mutableStateOf<PlannedExerciseEntity?>(null) }
    var restDuration by remember { mutableIntStateOf(90) }
    var timerEnd by remember { mutableLongStateOf(0L) }
    var remainingSeconds by remember { mutableIntStateOf(90) }
    var sensorEnabled by remember { mutableStateOf(false) }
    val sensorPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sensorEnabled = granted
        vm.setHealthStatus(if (granted) "Step sensor enabled" else "Activity recognition declined · manual entry available")
    }
    val healthClient = remember { runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() }
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { grants ->
        if (stepsPermission in grants) { vm.setHealthStatus("Connected · syncing steps"); scope.launch { syncSteps(healthClient, vm, snackbar) } }
        else vm.setHealthStatus("Permission declined · manual entry remains available")
    }
    LaunchedEffect(timerEnd) {
        while (timerEnd > 0L) {
            val left = ((timerEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000L).toInt()
            remainingSeconds = left
            if (left == 0) { timerEnd = 0L; snackbar.showSnackbar("Rest timer complete"); break }
            delay(250)
        }
    }
    DisposableEffect(lifecycle, healthClient) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && healthClient != null) scope.launch {
            val granted = runCatching { healthClient.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (stepsPermission in granted) syncSteps(healthClient, vm, snackbar)
        } }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycle, sensorEnabled) {
        val tracker = if (sensorEnabled) StepCounterTracker(context.applicationContext, vm) else null
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> tracker?.start(); Lifecycle.Event.ON_STOP -> tracker?.stop(); else -> Unit }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) tracker?.start()
        onDispose { lifecycle.lifecycle.removeObserver(observer); tracker?.stop() }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Emerald, secondary = Cyan, background = Bg, surface = Card, onBackground = White, onSurface = White)) {
        Scaffold(containerColor = Bg, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F172A)) {
                val entries = listOf(Icons.Default.Dashboard to "Dashboard", Icons.Default.FitnessCenter to "AI Gym Planner", Icons.Default.CameraAlt to "AI Meal Scanner", Icons.AutoMirrored.Filled.DirectionsWalk to "Health Steps")
                entries.forEachIndexed { index, (icon, label) -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 9.sp, maxLines = 1) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Bg, selectedTextColor = Emerald, indicatorColor = Emerald, unselectedIconColor = Muted, unselectedTextColor = Muted)) }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                TopBar(remainingSeconds, timerEnd > 0L, {
                    if (timerEnd > 0L) { timerEnd = 0; remainingSeconds = restDuration }
                    else timerEnd = SystemClock.elapsedRealtime() + remainingSeconds * 1000L
                }, { restDuration = 90; remainingSeconds = 90; timerEnd = 0 }, { showProfile = true })
                when (tab) {
                    0 -> Dashboard(state, onWorkout = { tab = 1 }, onMeals = { tab = 2 }, onSteps = { tab = 3 }, onProfile = { showProfile = true })
                    1 -> WorkoutScreen(state, vm, onLog = { showSetDialog = it }, onToast = { scope.launch { snackbar.showSnackbar(it) } })
                    2 -> MealScreen(state, vm, onToast = { scope.launch { snackbar.showSnackbar(it) } })
                    else -> StepsScreen(state, vm, onConnect = {
                        if (healthClient == null) scope.launch { snackbar.showSnackbar("Health Connect is not available on this device") }
                        else healthLauncher.launch(setOf(stepsPermission))
                    }, onSensor = {
                        if (android.os.Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) sensorEnabled = true
                        else sensorPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                    })
                }
            }
        }
        if (showProfile) ProfileSheet(state.settings, onDismiss = { showProfile = false }, onSave = { vm.saveSettings(it); showProfile = false; scope.launch { snackbar.showSnackbar("Targets saved") } })
        showSetDialog?.let { exercise -> SetLogger(exercise, onDismiss = { showSetDialog = null }, onSave = { w, r, rpe, type -> vm.logSet(exercise, w, r, rpe, type); showSetDialog = null; scope.launch { snackbar.showSnackbar("Set saved · Epley 1RM ${epley(w, r).toInt()} lb") } }) }
    }
}

private suspend fun syncSteps(client: HealthConnectClient?, vm: DashboardViewModel, snackbar: SnackbarHostState) {
    if (client == null) return
    runCatching {
        val zone = ZoneId.systemDefault(); val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
        vm.updateSteps((result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()); vm.setHealthStatus("Synced from Health Connect")
    }.onFailure { vm.setHealthStatus("Health Connect sync failed · manual entry available") }
}

private class StepCounterTracker(context: android.content.Context, private val vm: DashboardViewModel) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = context.getSharedPreferences("pulsefit_step_sensor", 0)
    fun start() { sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() { manager.unregisterListener(this) }
    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull()?.toInt() ?: return
        val today = LocalDate.now().toString()
        var baseline = prefs.getInt("baseline", -1)
        val savedDate = prefs.getString("date", "")
        if (baseline < 0 || savedDate != today || raw < baseline) {
            baseline = raw - vm.state.value.steps
            prefs.edit().putInt("baseline", baseline).putString("date", today).apply()
        }
        vm.updateSteps((raw - baseline).coerceAtLeast(0))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
private fun TopBar(seconds: Int, running: Boolean, toggle: () -> Unit, reset: () -> Unit, profile: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101827)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Emerald), Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Bg) }
            Column { Text("PulseFit AI", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text("COACH · VISION · HEALTH", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Cyan, fontWeight = FontWeight.Bold)
            TextButton(onClick = toggle, contentPadding = PaddingValues(horizontal = 5.dp)) { Text(if (running) "Pause" else "Start", color = Cyan) }
            IconButton(onClick = reset, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Reset rest timer", tint = Muted, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = profile, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Person, "Profile and targets", tint = Emerald) }
        }
    }
}

@Composable private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
}

@Composable private fun Dashboard(s: PulseFitUiState, onWorkout: () -> Unit, onMeals: () -> Unit, onSteps: () -> Unit, onProfile: () -> Unit) {
    ScrollPage {
        Text(LocalDate.parse(s.date).let { "${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}, ${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth}" }, color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("NET CALORIE BUDGET", "${s.remaining}", "kcal remaining", Emerald, Modifier.weight(1f), "${s.intake} eaten · ${s.stepBurn + s.workoutBurn} active", s.remaining.toFloat() / s.settings.calorieTarget.coerceAtLeast(1))
            StatCard("HEALTH STEPS", "%s".format(s.steps.toString().reversed().chunked(3).joinToString(",").reversed()), "of ${s.settings.stepGoal}", Cyan, Modifier.weight(1f), "${s.stepBurn} calibrated kcal", s.steps.toFloat() / s.settings.stepGoal.coerceAtLeast(1))
        }
        ActionHeading("Macro progress", "Edit targets", onProfile)
        SurfaceCard {
            MacroBar("Protein", s.protein, s.settings.proteinTarget, Emerald); MacroBar("Carbs", s.carbs, s.settings.carbTarget, Cyan); MacroBar("Fat", s.fat, s.settings.fatTarget, Color(0xFFFBBF24))
        }
        ActionHeading("Today's workout", "Open planner", onWorkout)
        SurfaceCard { Text(s.splits.firstOrNull { it.id == s.selectedSplitId }?.name ?: "5-Day Bro Split", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(s.exercises.firstOrNull()?.name ?: "Select today's training day", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("${s.workoutBurn} kcal from completed sets · tap to plan", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onWorkout)) }
        ActionHeading("Meal diary · ${s.meals.size}", "Scan / log", onMeals)
        SurfaceCard {
            if (s.meals.isEmpty()) Text("Nothing logged today. Add a meal to update calories and macros.", color = Muted, fontSize = 13.sp)
            s.meals.take(3).forEach { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(it.slot.uppercase(), color = Emerald, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(it.name, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }; Text("${it.kcal} kcal", color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp) } }
        }
        ActionHeading("Wearable discrepancy inspector", "Steps", onSteps)
        SurfaceCard { Text("Calibrated: ${s.stepBurn} kcal · 500 kcal / 15k steps", color = Emerald, fontWeight = FontWeight.SemiBold); Text("Raw wrist estimate: ${s.steps * 700 / 15000} kcal · 700 kcal / 15k steps", color = Color(0xFFFDA4AF)); Text("Difference: +${(s.steps * 200 / 15000)} kcal potentially overcounted", color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun StatCard(title: String, value: String, sub: String, color: Color, modifier: Modifier, detail: String, progress: Float) { SurfaceCard(modifier) { Text(title, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(value, color = White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold); Text(sub, color = Muted, fontSize = 10.sp); Progress(progress, color); Text(detail, color = Muted, fontSize = 9.sp) } }

@Composable private fun MacroBar(label: String, amount: Int, target: Int, color: Color) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = White, fontSize = 12.sp); Text("$amount / $target g", color = Muted, fontSize = 11.sp) }; Progress(if (target > 0) amount.toFloat() / target else 0f, color) } }
@Composable private fun ActionHeading(title: String, action: String, click: () -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(action, color = Emerald, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.clickable(onClick = click).padding(4.dp)) } }
@Composable private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) { Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content) }
@Composable private fun Progress(value: Float, color: Color) { Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Raised)) { Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color)) } }

@Composable private fun WorkoutScreen(s: PulseFitUiState, vm: DashboardViewModel, onLog: (PlannedExerciseEntity) -> Unit, onToast: (String) -> Unit) {
    ScrollPage {
        Column { Text("AI GYM PLANNER", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Build your training week", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        s.splits.forEach { split -> FilterChip(selected = split.id == s.selectedSplitId, onClick = { vm.selectSplit(split.id) }, label = { Text(split.name) }, leadingIcon = if (split.id == s.selectedSplitId) ({ Icon(Icons.Default.Check, null) }) else null) }
        SurfaceCard { Text(s.splits.firstOrNull { it.id == s.selectedSplitId }?.description ?: "Choose a training split", color = Muted, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").forEachIndexed { i, label -> FilterChip(selected = i == s.selectedDay, onClick = { vm.selectDay(i) }, label = { Text(label, fontSize = 9.sp) }) } }
        if (s.exercises.isEmpty()) SurfaceCard { Text("Recovery day · active recovery and a 15,000 step goal.", color = White); Text("Rest timer remains available in the top bar.", color = Muted, fontSize = 12.sp) }
        s.exercises.forEach { exercise ->
            val done = s.sets.any { it.exerciseName == exercise.name && it.completed }
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(exercise.name, color = White, fontWeight = FontWeight.Bold); Text("${exercise.sets} sets · ${exercise.reps} reps · ${exercise.restSeconds}s rest", color = Muted, fontSize = 10.sp); Text("Logged sets: ${s.sets.count { it.exerciseName == exercise.name }}", color = Cyan, fontSize = 10.sp) }
                    IconButton(onClick = { onToast("Swap suggestion: ${swapSuggestion(exercise.name)}") }) { Icon(Icons.Default.SwapHoriz, "AI exercise swap", tint = Cyan) }
                    Button(onClick = { onLog(exercise) }, colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Log set") }
                }
            }
        }
        s.sets.filter { it.completed }.takeLast(1).forEach { set -> SurfaceCard { Text("Latest set · ${set.exerciseName}", color = Emerald, fontWeight = FontWeight.Bold); Text("${set.weight} lb × ${set.reps} · RPE ${set.rpe} · ${set.type} · Epley 1RM ${epley(set.weight, set.reps).toInt()} lb", color = Muted, fontSize = 11.sp) } }
    }
}
private fun swapSuggestion(name: String) = when { "squat" in name.lowercase() -> "Leg press or goblet squat"; "press" in name.lowercase() -> "Neutral-grip dumbbell press"; "row" in name.lowercase() -> "Chest-supported machine row"; else -> "Cable or machine variation with a comfortable range" }
private fun epley(weight: Float, reps: Int) = weight * (1 + reps.coerceAtLeast(1) / 30f)

@Composable private fun SetLogger(exercise: PlannedExerciseEntity, onDismiss: () -> Unit, onSave: (Float, Int, Float, String) -> Unit) {
    var weight by remember { mutableStateOf("135") }; var reps by remember { mutableStateOf("10") }; var rpe by remember { mutableStateOf("8") }; var type by remember { mutableStateOf("Working") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Card, title = { Text("Log ${exercise.name}", color = White) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Weight (lb)", weight, { weight = it }); NumberField("Reps", reps, { reps = it }); NumberField("RPE", rpe, { rpe = it })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Warmup", "Working", "Drop", "Failure").forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it, fontSize = 9.sp) }) } }
            Text("Estimated Epley 1RM: ${epley(weight.toFloatOrNull() ?: 0f, reps.toIntOrNull() ?: 1).toInt()} lb", color = Cyan, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSave(weight.toFloatOrNull() ?: 0f, reps.toIntOrNull() ?: 1, rpe.toFloatOrNull() ?: 8f, type) }) { Text("Save set") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun NumberField(label: String, value: String, update: (String) -> Unit) { OutlinedTextField(value, update, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Emerald, unfocusedBorderColor = Muted, focusedLabelColor = Emerald, unfocusedLabelColor = Muted), modifier = Modifier.fillMaxWidth()) }

private data class IngredientDraft(val name: String, val kcal: Int, val p: Int, val c: Int, val f: Int, val included: Boolean = true, val multiplier: Float = 1f)
private fun parseIngredients(text: String): List<IngredientDraft> {
    val t = text.lowercase(); val result = mutableListOf<IngredientDraft>()
    if ("chicken" in t) result += IngredientDraft("Grilled chicken", 280, 52, 0, 6)
    if ("beef" in t || "steak" in t) result += IngredientDraft("Beef", 320, 38, 0, 18)
    if ("salmon" in t) result += IngredientDraft("Salmon", 300, 34, 0, 18)
    if ("tofu" in t) result += IngredientDraft("Tofu", 180, 20, 8, 10)
    if ("greens" in t || "salad" in t || "lettuce" in t) result += IngredientDraft("Greens base", 45, 3, 8, 0)
    if ("rice" in t || "pasta" in t || "bread" in t || "pita" in t) result += IngredientDraft("Grain / bread", 210, 6, 42, 2, included = !("no pita" in t || "no bread" in t))
    if ("beans" in t || "corn" in t) result += IngredientDraft("Beans & corn", 130, 7, 24, 1)
    if ("avocado" in t || "hummus" in t || "cheese" in t || "dressing" in t || "sauce" in t) result += IngredientDraft("Toppings & dressing", 160, 4, 10, 12)
    if (result.isEmpty()) result += IngredientDraft("Mixed meal estimate", 450, 25, 45, 18)
    return result
}
private val customizationChips = listOf("No Pita" to -240, "Extra Chicken" to 190, "Greens Base" to -185, "Half Rice / Half Greens" to -105, "Heavy Tzatziki / Sriracha" to 90, "Grillo's Pickles" to 15, "Extra Hummus" to 70)

@Composable private fun MealScreen(s: PulseFitUiState, vm: DashboardViewModel, onToast: (String) -> Unit) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("Grilled chicken bowl with greens, black beans, corn, avocado and light dressing") }
    var ingredients by remember { mutableStateOf(parseIngredients(description)) }
    var slot by remember { mutableStateOf("Dinner") }
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> photoUri = uri; if (uri != null) onToast("Photo attached. Add a description to estimate ingredients.") }
    var cameraOpen by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) cameraOpen = true else onToast("Camera permission is needed to take a meal photo") }
    val total = remember(ingredients) { IngredientTotals(ingredients.filter { it.included }.sumOf { (it.kcal * it.multiplier).toInt() }, ingredients.filter { it.included }.sumOf { (it.p * it.multiplier).toInt() }, ingredients.filter { it.included }.sumOf { (it.c * it.multiplier).toInt() }, ingredients.filter { it.included }.sumOf { (it.f * it.multiplier).toInt() }) }
    ScrollPage {
        Column { Text("AI MEAL SCANNER", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Describe and customize", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.PhotoLibrary, null); Text("Choose photo") }; OutlinedButton(onClick = { cameraPermission.launch(android.Manifest.permission.CAMERA) }) { Icon(Icons.Default.CameraAlt, null); Text("Camera") } }
        if (photoUri != null) Text("Photo selected: ${photoUri?.lastPathSegment ?: "meal image"}", color = Cyan, fontSize = 11.sp)
        SurfaceCard {
            OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Meal or takeout modifications") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Emerald, unfocusedBorderColor = Muted, focusedLabelColor = Emerald, unfocusedLabelColor = Muted))
            Button(onClick = {
                scope.launch {
                    if (s.settings.geminiKey.isBlank()) { ingredients = parseIngredients(description); onToast("Local meal estimate refreshed · add a Gemini key for photo AI") }
                    else {
                        analyzing = true
                        runCatching { analyzeMealWithGemini(context = context, apiKey = s.settings.geminiKey, text = description, imageUri = photoUri) }
                            .onSuccess { ingredients = it; onToast("Gemini ingredient estimate ready") }
                            .onFailure { onToast("AI analysis failed · ${it.message ?: "check key or connection"}") }
                        analyzing = false
                    }
                }
            }, enabled = !analyzing, colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Text(if (analyzing) "Analyzing…" else "Analyze meal") }
            Text(if (s.settings.geminiKey.isBlank()) "Offline local text estimates are active. Add a Gemini key in Profile to enable image and text AI." else "When you analyze, the meal description and attached photo are sent to Google Gemini. Estimates may be inaccurate." , color = Muted, fontSize = 10.sp)
        }
        Text("TAKEOUT QUICK CUSTOMIZATIONS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        customizationChips.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (label, kcal) -> AssistChip(onClick = {
            if (label == "Extra Chicken") ingredients = ingredients + IngredientDraft("Extra chicken", 190, 36, 0, 4)
            else if (label == "No Pita") ingredients = ingredients.map { if (it.name.contains("Grain")) it.copy(included = false) else it }
            else ingredients = ingredients + IngredientDraft(label, kcal.coerceAtLeast(0), if (label == "Extra Hummus") 3 else 0, 5, 4)
            onToast("Applied $label (${if (kcal >= 0) "+" else ""}$kcal kcal)")
        }, label = { Text("$label ${if (kcal >= 0) "+" else ""}$kcal", fontSize = 9.sp) }) } } }
        SurfaceCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Ingredient estimate", color = White, fontWeight = FontWeight.Bold); Text("${total.kcal} kcal", color = Emerald, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
            ingredients.forEachIndexed { index, item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.included, { ingredients = ingredients.toMutableList().also { list -> list[index] = list[index].copy(included = it) } }, colors = CheckboxDefaults.colors(checkedColor = Emerald))
                        Column(Modifier.weight(1f)) { Text(item.name, color = White, fontSize = 12.sp); Text("${item.kcal} kcal · ${item.p}P ${item.c}C ${item.f}F", color = Muted, fontSize = 10.sp) }
                        Text("${(item.kcal * item.multiplier).toInt()}", color = Cyan, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf(.5f, 1f, 1.5f, 2f).forEach { mult -> FilterChip(selected = item.multiplier == mult, onClick = { ingredients = ingredients.toMutableList().also { list -> list[index] = list[index].copy(multiplier = mult) } }, label = { Text("${mult}x", fontSize = 9.sp) }) } }
                }
            }
            Text("${total.protein}g protein · ${total.carbs}g carbs · ${total.fat}g fat", color = Muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Meal slot", color = Muted); Spacer(Modifier.width(8.dp)); listOf("Breakfast", "Lunch", "Dinner", "Snack").forEach { FilterChip(selected = slot == it, onClick = { slot = it }, label = { Text(it, fontSize = 9.sp) }) } }
            Button(onClick = { vm.logMeal(slot, description.take(80), total, ingredients.map { IngredientItem(mealId = "", name = it.name, baseKcal = it.kcal, baseProtein = it.p, baseCarbs = it.c, baseFat = it.f, multiplier = it.multiplier, included = it.included) }); onToast("Meal logged") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg)) { Text("Log meal · ${total.kcal} kcal") }
        }
        Text("TODAY'S MEALS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        s.meals.forEach { meal -> SurfaceCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${meal.slot} · ${meal.name}", color = White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text("${meal.protein}P · ${meal.carbs}C · ${meal.fat}F", color = Muted, fontSize = 10.sp) }; Text("${meal.kcal} kcal", color = Emerald); IconButton(onClick = { vm.deleteMeal(meal.id); onToast("Meal removed") }) { Icon(Icons.Default.Delete, "Remove meal", tint = Muted) } } } }
    }
    if (cameraOpen) CameraCaptureDialog(onDismiss = { cameraOpen = false }, onSaved = { uri -> photoUri = uri; cameraOpen = false; onToast("Photo captured. Add a description to estimate ingredients.") })
}

private suspend fun analyzeMealWithGemini(context: android.content.Context, apiKey: String, text: String, imageUri: Uri?): List<IngredientDraft> = withContext(Dispatchers.IO) {
    val parts = JSONArray()
    if (imageUri != null) {
        val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() } ?: error("Could not read selected photo")
        val mime = context.contentResolver.getType(imageUri) ?: "image/jpeg"
        parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
    }
    val prompt = "Estimate this meal's edible ingredients and nutrition. User description: ${text.ifBlank { "Analyze the attached meal photo." }}. Return ONLY a JSON array; each item must have name, kcal, protein, carbs, fat as numbers. Use realistic portions and no markdown."
    parts.put(JSONObject().put("text", prompt))
    val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
    val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", apiKey)
    }
    try {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) error("Gemini returned HTTP $code")
        val responseJson = JSONObject(response)
        val answer = responseJson.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val clean = answer.substringAfter('[', "[").let { "[" + it.substringBeforeLast(']', "[]") + "]" }
        val rows = JSONArray(clean)
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            IngredientDraft(row.optString("name", "Meal ingredient"), row.optInt("kcal"), row.optInt("protein"), row.optInt("carbs"), row.optInt("fat"))
        }.ifEmpty { error("Gemini did not return any ingredients") }
    } finally { connection.disconnect() }
}

@Composable private fun CameraCaptureDialog(onDismiss: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(previewView) {
        val view = previewView
        if (view != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching {
                val provider = future.get(); val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onFailure { error = it.message ?: "Camera could not start" } }, ContextCompat.getMainExecutor(context))
            onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
        } else onDispose { }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    Dialog(onDismissRequest = onDismiss) {
        SurfaceCard {
            Text("Capture meal photo", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AndroidView(factory = { ctx -> PreviewView(ctx).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 640); scaleType = PreviewView.ScaleType.FILL_CENTER; previewView = this } }, modifier = Modifier.fillMaxWidth().height(360.dp))
            error?.let { Text(it, color = Color(0xFFFDA4AF), fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    val output = File(context.cacheDir, "meal-${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(output).build()
                    capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) { ContextCompat.getMainExecutor(context).execute { onSaved(Uri.fromFile(output)) } }
                        override fun onError(exception: ImageCaptureException) { ContextCompat.getMainExecutor(context).execute { error = exception.message ?: "Could not save image" } }
                    })
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg)) { Icon(Icons.Default.CameraAlt, null); Text("Capture") }
            }
        }
    }
}

@Composable private fun StepsScreen(s: PulseFitUiState, vm: DashboardViewModel, onConnect: () -> Unit, onSensor: () -> Unit) {
    var manual by remember(s.steps) { mutableFloatStateOf(s.steps.toFloat()) }
    ScrollPage {
        Column { Text("HEALTH STEPS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Step sync & calibration", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        SurfaceCard { Text("${s.steps} steps", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp); Progress(s.steps.toFloat() / s.settings.stepGoal, Cyan); Text("Goal ${s.settings.stepGoal} · calibrated burn ${s.stepBurn} kcal", color = Emerald) }
        SurfaceCard {
            Text("HEALTH CONNECT", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 10.sp); Text(s.healthStatus, color = White, fontSize = 12.sp)
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Raised, contentColor = Cyan)) { Icon(Icons.Default.Sync, null); Text("Connect / sync Health Connect") }
            OutlinedButton(onClick = onSensor, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DirectionsRun, null); Text("Enable phone step sensor") }
            Text("The hardware step counter updates while PulseFit is open. Health Connect remains the primary all-day source.", color = Muted, fontSize = 10.sp)
            Slider(value = manual, onValueChange = { manual = it }, valueRange = 0f..30000f, steps = 199); Text("Manual: ${manual.toInt()} steps", color = White)
            Button(onClick = { vm.updateSteps(manual.toInt()) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg)) { Text("Save manual step count") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(500, 1800, 5000).forEach { delta -> OutlinedButton(onClick = { vm.updateSteps(s.steps + delta) }, modifier = Modifier.weight(1f)) { Text("+${delta}") } } }
        }
        SurfaceCard {
            Text("WEARABLE DISCREPANCY INSPECTOR", color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Verified personal baseline · 500 kcal / 15,000 steps", color = Emerald, fontSize = 12.sp)
            Text("Calibrated active burn: ${s.stepBurn} kcal", color = White)
            Text("Raw wrist estimate (700 / 15k): ${s.steps * 700 / 15000} kcal", color = Color(0xFFFDA4AF))
            Text("Potential overcount: ${s.steps * 200 / 15000} kcal. Wrist estimate may include resting energy.", color = Muted, fontSize = 11.sp)
            Row { listOf("personal_500" to "Verified", "tracker_high" to "Wrist 700", "conservative" to "Conservative").forEach { (id, label) -> FilterChip(selected = s.settings.calibrationMode == id, onClick = { vm.saveSettings(s.settings.copy(calibrationMode = id)) }, label = { Text(label, fontSize = 9.sp) }) } }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProfileSheet(settings: Settings, onDismiss: () -> Unit, onSave: (Settings) -> Unit) {
    var calories by remember(settings) { mutableStateOf(settings.calorieTarget.toString()) }; var protein by remember(settings) { mutableStateOf(settings.proteinTarget.toString()) }; var carbs by remember(settings) { mutableStateOf(settings.carbTarget.toString()) }; var fat by remember(settings) { mutableStateOf(settings.fatTarget.toString()) }
    var age by remember { mutableStateOf("30") }; var height by remember { mutableStateOf("175") }; var weight by remember { mutableStateOf("75") }; var sex by remember { mutableStateOf("Male") }; var activity by remember { mutableStateOf(1.45f) }; var goal by remember { mutableStateOf("Maintenance") }; var key by remember(settings) { mutableStateOf(settings.geminiKey) }
    val tdee = remember(age, height, weight, sex, activity) { val w = weight.toFloatOrNull() ?: 75f; val h = height.toFloatOrNull() ?: 175f; val a = age.toIntOrNull() ?: 30; ((10*w + 6.25f*h - 5*a + if (sex == "Male") 5 else -161) * activity).toInt() }
    val recommended = when (goal) { "Cut" -> tdee - 350; "Lean Bulk" -> tdee + 200; "Recomp" -> tdee - 100; else -> tdee }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Profile & TDEE", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumberField("Age", age, { age = it }); NumberField("Height cm", height, { height = it }); NumberField("Weight kg", weight, { weight = it }) } }
            item { Row { listOf("Male", "Female").forEach { FilterChip(selected = sex == it, onClick = { sex = it }, label = { Text(it) }) } } }
            item { Text("Activity factor ${"%.2f".format(activity)}", color = Muted); Slider(activity, { activity = it }, valueRange = 1.2f..1.9f) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("Cut", "Lean Bulk", "Recomp", "Maintenance").forEach { choice -> FilterChip(selected = goal == choice, onClick = { goal = choice; calories = when (choice) { "Cut" -> tdee - 350; "Lean Bulk" -> tdee + 200; "Recomp" -> tdee - 100; else -> tdee }.toString() }, label = { Text(choice, fontSize = 9.sp) }) } } }
            item { Text("Estimated TDEE $tdee kcal · $goal target $recommended kcal", color = Emerald, fontWeight = FontWeight.Bold) }
            item { NumberField("Daily calorie target", calories, { calories = it }) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { NumberField("Protein g", protein, { protein = it }); NumberField("Carbs g", carbs, { carbs = it }); NumberField("Fat g", fat, { fat = it }) } }
            item { NumberField("Gemini API key (optional)", key, { key = it }) }
            item { Text("The key is stored locally in DataStore. This build does not transmit it or call Gemini.", color = Muted, fontSize = 10.sp) }
            item { Button(onClick = { onSave(settings.copy(calorieTarget = calories.toIntOrNull() ?: settings.calorieTarget, proteinTarget = protein.toIntOrNull() ?: settings.proteinTarget, carbTarget = carbs.toIntOrNull() ?: settings.carbTarget, fatTarget = fat.toIntOrNull() ?: settings.fatTarget, geminiKey = key)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg)) { Text("Save profile & targets") } }
        }
    }
}
