package com.pulsefit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

private val Bg = Color(0xFF0B1120)
private val Surface = Color(0xFF151E2E)
private val Surface2 = Color(0xFF1E293B)
private val Emerald = Color(0xFF34D399)
private val Cyan = Color(0xFF22D3EE)
private val Muted = Color(0xFF94A3B8)
private val White = Color(0xFFF8FAFC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PulseFitApp() }
    }
}

private data class Meal(val slot: String, val name: String, val kcal: Int, val p: Int, val c: Int, val f: Int)
private data class Exercise(val name: String, val sets: String, val weight: String, val rest: Int, var done: Boolean = false)
private data class WorkoutDay(val day: String, val focus: String, val burn: Int, val exercises: List<Exercise>)

@Composable
private fun PulseFitApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("pulsefit", 0) }
    var steps by remember { mutableIntStateOf(prefs.getInt("steps", 12450)) }
    var healthStatus by remember { mutableStateOf("Health Connect not connected") }
    val healthClient = remember { runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() }
    val stepPermission = remember { androidx.health.connect.client.permission.HealthPermission.getReadPermission(StepsRecord::class) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (stepPermission in granted) {
            healthStatus = "Connected · syncing today's steps"
        } else {
            healthStatus = "Permission declined · manual steps available"
        }
    }
    var page by remember { mutableIntStateOf(0) }
    var mealText by remember { mutableStateOf("Grilled chicken bowl with greens, black beans, corn, avocado and light dressing") }
    var estimate by remember { mutableStateOf(Meal("Dinner", "Chicken bowl with greens", 640, 52, 48, 24)) }
    var meals by remember {
        mutableStateOf(mutableListOf(
            Meal("Lunch", "Mixed chicken platter over greens (no pita)", 860, 72, 42, 41),
            Meal("Post-workout", "Chicken parm cutlet + side greens", 590, 58, 24, 28),
        ))
    }
    var timer by remember { mutableIntStateOf(90) }
    var timerRunning by remember { mutableStateOf(false) }
    val days = remember {
        listOf(
            WorkoutDay("MON", "Chest · Incline, mid & lower pecs", 215, listOf(Exercise("Incline barbell press", "4 × 8–10", "185 lb", 120, true), Exercise("Flat dumbbell press", "4 × 8–10", "80 lb", 90, true), Exercise("Weighted chest dips", "3 × 10–12", "BW + 25 lb", 90, true))),
            WorkoutDay("TUE", "Back · Width, thickness & traps", 225, listOf(Exercise("Lat pulldown", "4 × 8–10", "160 lb", 90, true), Exercise("Chest-supported row", "4 × 10–12", "145 lb", 90, true))),
            WorkoutDay("WED", "Shoulders · 3D delts", 200, listOf(Exercise("Seated shoulder press", "4 × 8–10", "65 lb", 120, true), Exercise("Cable lateral raise", "4 × 12–15", "25 lb", 60, true))),
            WorkoutDay("THU", "Legs · Quads, hamstrings & calves", 255, listOf(Exercise("Hack squat", "4 × 8–10", "225 lb", 120), Exercise("Dumbbell Romanian deadlift", "3 × 10–12", "90 lb", 90))),
            WorkoutDay("FRI", "Arms · Biceps & triceps", 205, listOf(Exercise("EZ-bar preacher curl", "4 × 8–10", "70 lb", 75), Exercise("Overhead rope extension", "3 × 12–15", "60 lb", 60))),
            WorkoutDay("SAT", "Active recovery · 15,000 step goal", 0, emptyList()),
            WorkoutDay("SUN", "Full rest & meal prep", 0, emptyList()),
        )
    }
    var selectedDay by remember { mutableIntStateOf(4) }
    LaunchedEffect(timerRunning) {
        while (timerRunning && timer > 0) { delay(1000); timer-- }
        if (timer == 0) timerRunning = false
    }
    LaunchedEffect(healthClient) {
        if (healthClient == null) healthStatus = "Health Connect isn't available on this device"
        else {
            val availability = HealthConnectClient.getSdkStatus(context)
            healthStatus = if (availability == HealthConnectClient.SDK_AVAILABLE) "Health Connect ready" else "Install or update Health Connect to sync"
        }
    }
    LaunchedEffect(healthClient, healthStatus) {
        if (healthClient != null && healthStatus == "Connected · syncing today's steps") {
            runCatching {
                val granted = healthClient.permissionController.getGrantedPermissions()
                if (stepPermission in granted) {
                    val zone = ZoneId.systemDefault()
                    val start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
                    val end = Instant.now()
                    val result = healthClient.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end)))
                    val total = result.records.sumOf { it.count }.toInt()
                    steps = total
                    prefs.edit().putInt("steps", total).apply()
                    healthStatus = "Synced from Health Connect"
                }
            }.onFailure { healthStatus = "Couldn't read steps · check Health Connect" }
        }
    }
    DisposableEffect(lifecycleOwner, healthClient) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && healthClient != null) {
                lifecycleOwner.lifecycleScope.launch {
                    val granted = runCatching { healthClient.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
                    if (stepPermission in granted) healthStatus = "Connected · syncing today's steps"
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Emerald, background = Bg, surface = Surface)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0F172A), contentColor = Muted) {
                    listOf(Icons.Default.Dashboard to "Today", Icons.Default.FitnessCenter to "Workouts", Icons.Default.Restaurant to "Meals", Icons.Default.DirectionsWalk to "Steps").forEachIndexed { i, pair ->
                        NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Icon(pair.first, pair.second) }, label = { Text(pair.second, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Bg, selectedTextColor = Emerald, indicatorColor = Emerald, unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header(timer, timerRunning, { timerRunning = !timerRunning }, { timer = 90; timerRunning = false })
                when (page) {
                    0 -> Dashboard(steps, meals, days[selectedDay], onSteps = { page = 3 }, onMeals = { page = 2 }, onWorkouts = { page = 1 })
                    1 -> Workouts(days, selectedDay, { selectedDay = it }, { exercise -> exercise.done = !exercise.done })
                    2 -> Meals(mealText, { mealText = it }, estimate, { estimate = estimateMeal(mealText) }, meals,
                        { meals = (meals + estimate.copy(slot = "Dinner")).toMutableList() }, { index -> meals = meals.toMutableList().also { it.removeAt(index) } })
                    else -> Steps(steps, { steps = it.coerceIn(0, 30000); prefs.edit().putInt("steps", steps).apply() }, healthStatus,
                        { if (healthClient == null) healthStatus = "Install Health Connect to enable sync" else permissionLauncher.launch(setOf(stepPermission)) })
                }
            }
        }
    }
}

@Composable
private fun Header(timer: Int, running: Boolean, toggle: () -> Unit, reset: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101827)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Emerald), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Bg) }
            Column {
                Text("PulseFit ", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("AI  ·  COACH + VISION", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("%02d:%02d".format(timer / 60, timer % 60), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            TextButton(onClick = toggle, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (running) "Pause" else "Start", color = Cyan, fontSize = 12.sp) }
            IconButton(onClick = reset, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Reset timer", tint = Muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = { item { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) } })
}

@Composable
private fun Dashboard(steps: Int, meals: List<Meal>, workout: WorkoutDay, onSteps: () -> Unit, onMeals: () -> Unit, onWorkouts: () -> Unit) {
    val intake = meals.sumOf { it.kcal }
    val stepBurn = steps * 500 / 15000
    val remaining = (2150 - intake + stepBurn).coerceAtLeast(0)
    PageColumn {
        Column {
            Text("YOUR DAY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Friday, November 13", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("CALORIE BUDGET", "$remaining", "kcal remaining", Emerald, Modifier.weight(1f), "$intake eaten · $stepBurn burned")
            StatCard("STEPS", "%s".format(steps.toString().reversed().chunked(3).joinToString(",").reversed()), "of 15,000 goal", Cyan, Modifier.weight(1f), "${(steps * 100 / 15000).coerceAtMost(100)}% complete")
        }
        SectionTitle("Today's workout", "VIEW PLAN", onWorkouts)
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Emerald.copy(alpha = .14f)), Alignment.Center) { Icon(Icons.Default.FitnessCenter, null, tint = Emerald) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("FRIDAY · ARM DAY", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text(workout.focus, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text("${workout.burn}\nkcal", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        SectionTitle("Meal diary", "+ LOG MEAL", onMeals)
        CardBlock {
            if (meals.isEmpty()) Text("No meals logged yet", color = Muted)
            meals.takeLast(3).forEach { meal ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(meal.slot.uppercase(), color = Emerald, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(meal.name, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text("${meal.kcal} kcal", color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                HorizontalDivider(color = Muted.copy(alpha = .12f))
            }
            Text("${meals.sumOf { it.p }}g protein  ·  ${meals.sumOf { it.c }}g carbs  ·  ${meals.sumOf { it.f }}g fat", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        SectionTitle("Step sync", "UPDATE", onSteps)
        CardBlock { Text("$steps / 15,000 steps", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.height(9.dp)); ProgressLine(steps / 15000f, Cyan); Text("~$stepBurn kcal active burn · 500 kcal per 15k steps", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun StatCard(title: String, value: String, suffix: String, color: Color, modifier: Modifier, detail: String) {
    CardBlock(modifier) {
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = .8.sp)
        Spacer(Modifier.height(10.dp)); Text(value, color = White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp); Text(suffix, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp)); ProgressLine(if (title == "STEPS") .83f else .45f, color); Text(detail, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Workouts(days: List<WorkoutDay>, selected: Int, chooseDay: (Int) -> Unit, toggle: (Exercise) -> Unit) {
    PageColumn {
        Column { Text("AI GYM PLANNER", color = Emerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Your weekly split", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { days.forEachIndexed { i, d -> Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (i == selected) Emerald else Surface2).clickable { chooseDay(i) }.padding(vertical = 12.dp), Alignment.Center) { Text(d.day, color = if (i == selected) Bg else Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp) } } }
        CardBlock {
            Text(days[selected].focus, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(if (days[selected].burn > 0) "Estimated burn · ${days[selected].burn} kcal" else "Recovery day · easy movement", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (days[selected].exercises.isEmpty()) Text("Take a relaxed walk, work on mobility, and give your body time to recover.", color = Muted, fontSize = 13.sp)
            days[selected].exercises.forEach { exercise ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { toggle(exercise) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = exercise.done, onCheckedChange = { toggle(exercise) }, colors = CheckboxDefaults.colors(checkedColor = Emerald, uncheckedColor = Muted))
                    Column(Modifier.weight(1f)) { Text(exercise.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text("${exercise.sets}  ·  ${exercise.weight}", color = Muted, fontSize = 11.sp) }
                    Text("${exercise.rest}s", color = Cyan, fontSize = 11.sp)
                }
            }
        }
        CardBlock { Text("REST TIMER", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text("Use the timer in the top bar between sets. Typical rest: 60–120 seconds.", color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun Meals(description: String, onDescription: (String) -> Unit, estimate: Meal, analyze: () -> Unit, meals: List<Meal>, log: () -> Unit, remove: (Int) -> Unit) {
    PageColumn {
        Column { Text("MEAL TRACKER", color = Emerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Scan & log a meal", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Describe what you ate for a nutrition estimate.", color = Muted, fontSize = 12.sp) }
        CardBlock {
            OutlinedTextField(value = description, onValueChange = onDescription, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Meal description") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Emerald, unfocusedBorderColor = Muted, focusedLabelColor = Emerald, unfocusedLabelColor = Muted))
            Button(onClick = analyze, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Bg), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(7.dp)); Text("Estimate nutrition", fontWeight = FontWeight.Bold) }
            Text("Estimates are heuristic; no AI service is connected.", color = Muted, fontSize = 10.sp)
        }
        CardBlock {
            Text("ESTIMATED NUTRITION", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(estimate.name, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${estimate.kcal} kcal", color = White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("${estimate.p}g protein   ·   ${estimate.c}g carbs   ·   ${estimate.f}g fat", color = Muted, fontSize = 12.sp)
            Button(onClick = log, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Emerald)) { Text("Add to today's diary") }
        }
        Text("TODAY'S DIARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        meals.forEachIndexed { index, meal -> CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(meal.slot.uppercase(), color = Emerald, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(meal.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text("${meal.p}P · ${meal.c}C · ${meal.f}F", color = Muted, fontSize = 10.sp) }
                Text("${meal.kcal} kcal", color = White, fontWeight = FontWeight.Bold, fontSize = 12.sp); IconButton(onClick = { remove(index) }) { Icon(Icons.Default.Close, "Remove meal", tint = Muted) }
            }
        } }
    }
}

@Composable
private fun Steps(steps: Int, setSteps: (Int) -> Unit, healthStatus: String, connectHealth: () -> Unit) {
    PageColumn {
        Column { Text("HEALTH STEP SYNC", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("Keep moving", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Step count is saved on this device.", color = Muted, fontSize = 12.sp) }
        CardBlock {
            Text("TODAY'S STEPS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(steps.toString().reversed().chunked(3).joinToString(",").reversed(), color = White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text("of 15,000 step goal", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); ProgressLine(steps / 15000f, Cyan)
            Text("Estimated active burn  ·  ${steps * 500 / 15000} kcal", color = Emerald, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        CardBlock {
            Text("MANUAL STEP ADJUSTMENT", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Slider(value = steps.toFloat(), onValueChange = { setSteps(it.toInt() / 150 * 150) }, valueRange = 0f..30000f, steps = 199, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(500 to "+500", 1800 to "+1,800", 5000 to "+5,000").forEach { (delta, label) -> OutlinedButton(onClick = { setSteps(steps + delta) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)) { Text(label, fontSize = 11.sp) } }
            }
            Text("500 kcal / 15,000 steps · personal baseline", color = Muted, fontSize = 11.sp)
        }
        CardBlock {
            Text("HEALTH CONNECT", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
            Text(healthStatus, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Read today's step total from Health Connect. Your health data stays on this device.", color = Muted, fontSize = 12.sp)
            Button(onClick = connectHealth, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Cyan)) {
                Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Connect / sync steps")
            }
        }
    }
}

private fun estimateMeal(text: String): Meal {
    val value = text.lowercase()
    val kcal = 420 + (if ("chicken" in value || "beef" in value || "salmon" in value || "tofu" in value) 180 else 0) + (if ("avocado" in value || "cheese" in value || "sauce" in value || "dressing" in value) 100 else 0) + (if ("rice" in value || "pasta" in value || "bread" in value || "beans" in value) 140 else 0) + (if ("double" in value || "large" in value || "extra" in value) 160 else 0) - (if ("salad" in value || "greens" in value) 80 else 0)
    val protein = if ("chicken" in value || "beef" in value || "salmon" in value || "tofu" in value) 42 else 18
    val title = text.trim().split(Regex("\\s+")).take(7).joinToString(" ").ifBlank { "Meal estimate" }
    return Meal("Dinner", title, kcal.coerceAtLeast(180), protein, 42, 20)
}

@Composable
private fun SectionTitle(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(action, color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClick() }.padding(4.dp))
    }
}

@Composable
private fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ProgressLine(value: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Surface2)) { Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color)) }
}
