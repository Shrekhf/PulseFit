package com.pulsefit.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val WorkoutBg = Color(0xFF0B1120)
private val WorkoutCard = Color(0xFF151E2E)
private val WorkoutRaised = Color(0xFF1E293B)
private val WorkoutGreen = Color(0xFF10B981)
private val WorkoutCyan = Color(0xFF06B6D4)
private val WorkoutMuted = Color(0xFF94A3B8)
private val WorkoutWhite = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(vm: WorkoutViewModel = viewModel(), onFeedback: (String) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val recovery by vm.recoverySnapshot.collectAsState()
    var splitMenu by remember { mutableStateOf(false) }
    var expandedExercise by remember { mutableStateOf<String?>(null) }
    var swapTarget by remember { mutableStateOf<ExerciseEntity?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.healthSyncStatus) { state.healthSyncStatus?.let(onFeedback) }

    LazyColumn(Modifier.fillMaxSize().background(WorkoutBg), contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SamsungHealthRecoveryBanner(recovery) }
        item {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI GYM PLANNER", color = WorkoutGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Train with a plan", color = WorkoutWhite, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                ExposedDropdownMenuBox(expanded = splitMenu, onExpandedChange = { splitMenu = it }) {
                    OutlinedTextField(value = state.splits.firstOrNull { it.id == state.selectedSplitId }?.name ?: "5-Day Bro Split", onValueChange = {}, readOnly = true, label = { Text("Training split") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(splitMenu) }, modifier = Modifier.fillMaxWidth().menuAnchor(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = WorkoutWhite, unfocusedTextColor = WorkoutWhite, focusedBorderColor = WorkoutGreen, unfocusedBorderColor = WorkoutMuted, focusedLabelColor = WorkoutGreen, unfocusedLabelColor = WorkoutMuted))
                    ExposedDropdownMenu(expanded = splitMenu, onDismissRequest = { splitMenu = false }) {
                        state.splits.forEach { split -> DropdownMenuItem(text = { Text(split.name) }, onClick = { vm.chooseSplit(split.id); splitMenu = false; expandedExercise = null }) }
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items((0..6).toList()) { index ->
                    val day = state.days.firstOrNull { it.dayIndex == index }
                    val focus = day?.focus?.substringBefore(" ·") ?: listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[index]
                    Surface(shape = RoundedCornerShape(14.dp), color = if (state.selectedDay == index) WorkoutGreen else WorkoutCard, modifier = Modifier.widthIn(min = 62.dp).clickable { vm.chooseDay(index) }) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")[index], color = if (state.selectedDay == index) WorkoutBg else WorkoutMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(focus.take(8), color = if (state.selectedDay == index) WorkoutBg else WorkoutWhite, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WorkoutCard, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.day?.focus ?: "Loading training day…", color = WorkoutWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(state.day?.notes ?: "Workout data is loading from your local database.", color = WorkoutMuted, fontSize = 11.sp)
                Text("Session volume  ${"%,d".format(state.totalVolume)} lb  ·  Workout burn ${state.day?.estimatedBurnCalories ?: 0} kcal", color = WorkoutCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (state.exercises.isNotEmpty()) {
                    Button(onClick = { vm.finishAndSyncWorkout() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WorkoutGreen, contentColor = WorkoutBg)) {
                        Text("Finish & Sync Workout to Samsung Health", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    state.healthSyncStatus?.let { Text(it, color = if (it.startsWith("Synced")) WorkoutGreen else WorkoutMuted, fontSize = 9.sp) }
                }
            }
        }
        if (state.exercises.isEmpty()) item {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WorkoutCard, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Recovery day", color = WorkoutWhite, fontWeight = FontWeight.Bold)
                Text("Easy walking, mobility, and recovery. Benchmark: 15,000 steps · 500 calibrated active kcal.", color = WorkoutMuted, fontSize = 12.sp)
            }
        }
        items(state.exercises, key = { it.exercise.id }) { session ->
            ExerciseCard(session, expanded = expandedExercise == session.exercise.id, equipment = state.equipment, onExpand = { expandedExercise = if (expandedExercise == session.exercise.id) null else session.exercise.id }, onSwap = { swapTarget = session.exercise }, vm = vm)
        }
    }
    swapTarget?.let { target ->
        SwapSheet(target, state.equipment, onEquipment = vm::chooseEquipment, onDismiss = { swapTarget = null }, onSwap = { alternative ->
            vm.swapExercise(target, alternative.name); swapTarget = null; onFeedback("Exercise changed to ${alternative.name}")
        })
    }
}

@Composable private fun SamsungHealthRecoveryBanner(snapshot: RecoverySnapshot?) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WorkoutCard, RoundedCornerShape(14.dp)).clickable { expanded = !expanded }.padding(11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SAMSUNG HEALTH RECOVERY", color = WorkoutCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Text(if (snapshot == null) "Recovery insights will appear here" else "${snapshot.sleepMinutes?.let { "${it / 60}h ${it % 60}m sleep" } ?: "Sleep unavailable"} · ${snapshot.restingHeartRate?.let { "$it bpm resting HR" } ?: "Resting HR unavailable"}", color = WorkoutWhite, fontSize = 11.sp)
            }
            Text(if (expanded) "⌃" else "⌄", color = WorkoutMuted)
        }
        AnimatedVisibility(expanded) {
            Text(snapshot?.autoRegulationTip ?: "Sleep, resting heart rate, and AI RPE guidance will populate when recovery sync is connected.", color = WorkoutMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable private fun ExerciseCard(session: ExerciseSession, expanded: Boolean, equipment: String, onExpand: () -> Unit, onSwap: () -> Unit, vm: WorkoutViewModel) {
    val exercise = session.exercise
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WorkoutCard, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(exercise.name, color = WorkoutWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${exercise.plannedSets} sets · ${exercise.repRange} reps · ${exercise.restSeconds}s rest", color = WorkoutMuted, fontSize = 10.sp)
                Text("${session.sets.count { it.isCompleted }} / ${session.sets.size} sets complete", color = WorkoutCyan, fontSize = 10.sp)
            }
            AssistChip(onClick = onSwap, label = { Text("AI Swap", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp)) }, colors = AssistChipDefaults.assistChipColors(labelColor = WorkoutCyan, leadingIconContentColor = WorkoutCyan))
            IconButton(onClick = onExpand) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse exercise" else "Expand exercise", tint = WorkoutMuted) }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("SET / TYPE", color = WorkoutMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text("WEIGHT · REPS", color = WorkoutMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text("DONE", color = WorkoutMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                session.sets.forEach { set -> SetRow(set, exercise, vm) }
                Text("Exercise volume: ${"%,d".format(session.sets.filter { it.isCompleted }.sumOf { (it.weightLbs * it.reps).toLong() })} lb", color = WorkoutCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable private fun SetRow(set: ExerciseSetEntity, exercise: ExerciseEntity, vm: WorkoutViewModel) {
    val haptics = LocalHapticFeedback.current
    var weightText by remember(set.id) { mutableStateOf(if (set.weightLbs == 0f) "" else set.weightLbs.toString().removeSuffix(".0")) }
    var repsText by remember(set.id) { mutableStateOf(if (set.reps == 0) "" else set.reps.toString()) }
    var weightFocused by remember(set.id) { mutableStateOf(false) }
    var repsFocused by remember(set.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(WorkoutRaised, RoundedCornerShape(12.dp)).padding(horizontal = 5.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { vm.cycleSetType(set) }, contentPadding = PaddingValues(horizontal = 3.dp)) {
            Text(when (set.setType) { SetType.WARMUP -> "[W] Warmup"; SetType.WORKING -> "[${set.setNumber}] Working"; SetType.DROP -> "[D] Drop"; SetType.FAILURE -> "[F] Failure" }, color = setTypeColor(set.setType), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        CompactNumberField(weightText, "lbs", { value -> weightText = value; vm.updateSet(set.copy(weightLbs = value.toFloatOrNull() ?: 0f)) }, Modifier.width(58.dp), weightFocused, { weightFocused = it })
        Text("×", color = WorkoutMuted, fontSize = 11.sp)
        CompactNumberField(repsText, "reps", { value -> repsText = value; vm.updateSet(set.copy(reps = value.toIntOrNull() ?: 0)) }, Modifier.width(52.dp), repsFocused, { repsFocused = it })
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("1RM", color = WorkoutMuted, fontSize = 7.sp)
            Text("${epley(set.weightLbs, set.reps).toInt()}", color = WorkoutCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        var showRpe by remember(set.id) { mutableStateOf(false) }
        if (showRpe) OutlinedTextField(value = set.rpe.toInt().toString(), onValueChange = { vm.updateSet(set.copy(rpe = it.toFloatOrNull() ?: set.rpe)) }, modifier = Modifier.width(42.dp).height(47.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("RPE", fontSize = 7.sp) })
        else IconButton(onClick = { showRpe = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Tune, "Edit RPE ${set.rpe}", tint = WorkoutMuted, modifier = Modifier.size(16.dp)) }
        Checkbox(checked = set.isCompleted, onCheckedChange = { checked -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.completeSet(set, checked, exercise.restSeconds) }, colors = CheckboxDefaults.colors(checkedColor = WorkoutGreen, uncheckedColor = WorkoutMuted), modifier = Modifier.size(34.dp))
    }
}

@Composable private fun CompactNumberField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier, focused: Boolean, focus: (Boolean) -> Unit) {
    BasicTextField(value = value, onValueChange = { onChange(it.filter(Char::isDigit)) }, modifier = modifier.height(35.dp).background(WorkoutBg, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp).wrapContentHeight(Alignment.CenterVertically).onFocusChanged { focus(it.isFocused) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = WorkoutWhite, fontSize = 12.sp), decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (value.isEmpty()) Text(placeholder, color = WorkoutMuted, fontSize = 8.sp); inner() } })
}

private data class SwapAlternative(val name: String, val equipment: String, val note: String)
private fun alternativesFor(exercise: ExerciseEntity): List<SwapAlternative> {
    val name = exercise.name.lowercase()
    val role = when { listOf("chest", "press", "pec", "dip", "fly").any { it in name } -> "press"; listOf("row", "back", "lat", "shrug", "prayer").any { it in name } -> "pull"; listOf("squat", "leg", "calf", "curl").any { it in name } -> "leg"; listOf("shoulder", "lateral", "face", "rear delt").any { it in name } -> "shoulder"; else -> "arm" }
    val pools = mapOf(
        "press" to listOf(SwapAlternative("Neutral-Grip Machine Chest Press", "Full Commercial Gym", "Neutral grip can reduce shoulder rotation demands."), SwapAlternative("Neutral-Grip Dumbbell Floor Press", "Dumbbells & Bench", "Floor range limits deep shoulder extension."), SwapAlternative("Close-Grip Barbell Bench Press", "Barbell Rack", "Keep elbows at a comfortable angle; reduce load if wrists or shoulders ache.")),
        "pull" to listOf(SwapAlternative("Chest-Supported Machine Row", "Full Commercial Gym", "Chest support reduces low-back loading."), SwapAlternative("Chest-Supported Dumbbell Row", "Dumbbells & Bench", "Support the torso and avoid twisting."), SwapAlternative("Barbell Row from Rack", "Barbell Rack", "Brace first and use a pain-free torso angle.")),
        "leg" to listOf(SwapAlternative("Selectorized Leg Press", "Full Commercial Gym", "Use a comfortable knee depth and controlled tempo."), SwapAlternative("Dumbbell Box Squat", "Dumbbells & Bench", "Use the bench as a depth target; keep effort pain-free."), SwapAlternative("Barbell Box Squat", "Barbell Rack", "Set safety pins and choose a knee-tolerable box height.")),
        "shoulder" to listOf(SwapAlternative("Cable Lateral Raise", "Full Commercial Gym", "Use a light load and stay below painful range."), SwapAlternative("Seated Dumbbell Scaption Raise", "Dumbbells & Bench", "Raise slightly forward of the body with thumbs up."), SwapAlternative("Landmine Press from Rack", "Barbell Rack", "The angled press path is often easier to tolerate.")),
        "arm" to listOf(SwapAlternative("Cable Rope Curl", "Full Commercial Gym", "Use neutral wrists and avoid swinging."), SwapAlternative("Seated Dumbbell Hammer Curl", "Dumbbells & Bench", "Neutral grip can be comfortable for wrists."), SwapAlternative("Barbell Reverse Curl", "Barbell Rack", "Use a light load and stop if wrists are irritated."))
    )
    return pools.getValue(role)
}
private fun setTypeColor(type: SetType) = when (type) { SetType.WARMUP -> Color(0xFFFBBF24); SetType.WORKING -> WorkoutGreen; SetType.DROP -> Color(0xFFF472B6); SetType.FAILURE -> Color(0xFFF87171) }
private fun epley(weight: Float, reps: Int) = if (reps > 0) weight * (1f + reps / 30f) else 0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SwapSheet(exercise: ExerciseEntity, selectedEquipment: String, onEquipment: (String) -> Unit, onDismiss: () -> Unit, onSwap: (SwapAlternative) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val candidates = alternativesFor(exercise).filter { it.equipment == selectedEquipment }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WorkoutCard) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI biomechanical swaps", color = WorkoutWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Current exercise · ${exercise.name}", color = WorkoutMuted, fontSize = 12.sp)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = selectedEquipment, onValueChange = {}, readOnly = true, label = { Text("Available equipment") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = WorkoutWhite, unfocusedTextColor = WorkoutWhite, focusedBorderColor = WorkoutGreen, unfocusedBorderColor = WorkoutMuted, focusedLabelColor = WorkoutGreen, unfocusedLabelColor = WorkoutMuted))
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("Full Commercial Gym", "Dumbbells & Bench", "Barbell Rack").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onEquipment(option); expanded = false }) }
                }
            }
            if (candidates.isEmpty()) Text("No alternatives for this equipment selection.", color = WorkoutMuted)
            candidates.forEach { option ->
                Surface(color = WorkoutRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { onSwap(option) }) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(option.name, color = WorkoutWhite, fontWeight = FontWeight.SemiBold); Text(option.note, color = WorkoutMuted, fontSize = 10.sp); Text("${option.equipment} · joint-friendly cue", color = WorkoutCyan, fontSize = 9.sp) }
                        Icon(Icons.Default.ArrowForward, null, tint = WorkoutGreen)
                    }
                }
            }
            Text("Suggestions are curated training alternatives; choose only movements that feel comfortable.", color = WorkoutMuted, fontSize = 10.sp)
        }
    }
}
