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
    val workoutVm: WorkoutViewModel = viewModel()
    val mealVm: MealViewModel = viewModel()
    val workoutState by workoutVm.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }
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
                TopBar(workoutState.remainingSeconds, workoutState.timerTargetElapsedMs > 0L, workoutState.timerMuted, workoutVm::toggleTimer, workoutVm::resetTimer, workoutVm::toggleMute, { showProfile = true })
                when (tab) {
                    0 -> Dashboard(state, onWorkout = { tab = 1 }, onMeals = { tab = 2 }, onSteps = { tab = 3 }, onProfile = { showProfile = true })
                    1 -> WorkoutScreen(workoutVm, onFeedback = { scope.launch { snackbar.showSnackbar(it) } })
                    2 -> MealScannerScreen(state, mealVm, onToast = { scope.launch { snackbar.showSnackbar(it) } })
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
private fun TopBar(seconds: Int, running: Boolean, muted: Boolean, toggle: () -> Unit, reset: () -> Unit, toggleMute: () -> Unit, profile: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101827)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Emerald), Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Bg) }
            Column { Text("PulseFit AI", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text("COACH · VISION · HEALTH", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Cyan, fontWeight = FontWeight.Bold)
            TextButton(onClick = toggle, contentPadding = PaddingValues(horizontal = 5.dp)) { Text(if (running) "Pause" else "Start", color = Cyan) }
            IconButton(onClick = reset, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Reset rest timer", tint = Muted, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = toggleMute, modifier = Modifier.size(28.dp)) { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (muted) "Unmute timer alert" else "Mute timer alert", tint = if (muted) Muted else Emerald, modifier = Modifier.size(18.dp)) }
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

@Composable private fun NumberField(label: String, value: String, update: (String) -> Unit) { OutlinedTextField(value, update, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Emerald, unfocusedBorderColor = Muted, focusedLabelColor = Emerald, unfocusedLabelColor = Muted), modifier = Modifier.fillMaxWidth()) }

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
